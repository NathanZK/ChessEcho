package com.chessecho.service

import com.chessecho.dto.AcceptableMove
import com.chessecho.dto.MoveBreakdown
import com.chessecho.dto.WeaknessResponse
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.max

@Service
class WeaknessCalculationService(
    private val chessAccountRepository: ChessAccountRepository,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
) {
    /**
     * Finds and ranks the recurring weaknesses for a specific user, platform, and color.
     *
     * @param acceptableThreshold moves the user played where average eval loss is below this
     *   threshold are considered acceptable alternatives and returned in [WeaknessResponse.acceptableMoves].
     *   Defaults to 0.5 pawns — roughly the boundary between an acceptable imprecision and a real inaccuracy.
     */
    @Transactional(readOnly = true)
    fun getWeaknesses(
        platform: String,
        username: String,
        playerColor: String,
        minEvalLoss: Double,
        acceptableThreshold: Double = 0.3,
        minMistakeCount: Int = 3,
    ): List<WeaknessResponse> {
        val account =
            chessAccountRepository.findByPlatformAndUsernameIgnoreCase(platform, username)
                ?: throw NoSuchElementException("Chess account not found")

        // 1. Get all occurrences for this player and color.
        val occurrences = positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, playerColor.uppercase())

        if (occurrences.isEmpty()) return emptyList()

        // Group by position
        val groupedByPosition = occurrences.groupBy { it.position.id }

        val weaknesses = mutableListOf<WeaknessResponse>()

        for ((positionId, posOccurrences) in groupedByPosition) {
            val position = posOccurrences.first().position

            // 2. Fetch the cached engine analysis for this position
            val analysis = engineAnalysisRepository.findByPositionId(positionId) ?: continue

            val baselineCp = analysis.baselineEvalCp
            val bestMoveEvalCp = analysis.bestMoveEvalCp

            var unweightedTotalLoss = 0.0
            var priorityScore = 0.0
            var mistakeCount = 0
            val mistakeUrls = mutableListOf<String>()

            // Track total eval loss and play count per distinct move played
            // Used to build the per-move breakdown and to identify acceptable alternatives
            val moveStats = mutableMapOf<String, Pair<Double, Int>>()

            for (occ in posOccurrences) {
                // Find the engine evaluation for the move played
                val moveEval = analysis.moveEvaluations.find { it.move == occ.movePlayed } ?: continue

                // Calculate evaluation loss
                val evalLoss =
                    calculateEvalLoss(
                        playerColor = playerColor,
                        bestMoveEvalCp = bestMoveEvalCp,
                        resultCp = moveEval.evalCp,
                    )

                // Accumulate per-move stats for all moves (regardless of whether they qualify as mistakes)
                val (prevLoss, prevCount) = moveStats.getOrDefault(occ.movePlayed, Pair(0.0, 0))
                moveStats[occ.movePlayed] = Pair(prevLoss + evalLoss, prevCount + 1)

                // Enforce Playable Safety Net [-1.0, 1.0]
                // If a move drops evaluation but remains perfectly balanced, we don't punish theory.
                val resultingPawnEval = moveEval.evalCp?.div(100.0) ?: 0.0
                val isPlayable = resultingPawnEval in -1.0..1.0

                if (evalLoss >= minEvalLoss && !isPlayable) {
                    unweightedTotalLoss += evalLoss

                    val playedAt = occ.game.playedAt
                    val weight =
                        if (playedAt != null) {
                            val daysOld = ChronoUnit.DAYS.between(playedAt, Instant.now())
                            max(0.1, 1.0 - (daysOld / 365.0))
                        } else {
                            1.0
                        }
                    priorityScore += (evalLoss * weight)

                    mistakeCount++

                    val url =
                        when {
                            occ.game.platformGameId.startsWith("http") -> occ.game.platformGameId
                            account.platform == "CHESS_COM" -> "https://www.chess.com/game/live/${occ.game.platformGameId}"
                            account.platform == "LICHESS" -> "https://lichess.org/${occ.game.platformGameId}"
                            else -> occ.game.platformGameId
                        }
                    mistakeUrls.add(url)
                }
            }

            if (mistakeCount >= minMistakeCount) {
                // All engine-evaluated candidate moves for this position whose evaluation loss
                // compared to the best move is within acceptableThreshold (default 0.5 pawns).
                // Useful for puzzle generation to know which player responses are acceptable solutions.
                val acceptableMoves =
                    analysis.moveEvaluations
                        .map { moveEval ->
                            val loss =
                                calculateEvalLoss(
                                    playerColor = playerColor,
                                    bestMoveEvalCp = bestMoveEvalCp,
                                    resultCp = moveEval.evalCp,
                                )
                            AcceptableMove(move = moveEval.move, evalLoss = loss)
                        }
                        .filter { it.evalLoss < acceptableThreshold }
                        .sortedBy { it.evalLoss }

                // Per-move breakdown — only include moves that crossed the minEvalLoss bar (real mistakes).
                // Sorted by timesPlayed descending, then averageLoss descending, capped at top 3.
                val movesPlayed =
                    moveStats.entries
                        .map { (move, stats) ->
                            MoveBreakdown(
                                move = move,
                                timesPlayed = stats.second,
                                averageLoss = stats.first / stats.second,
                            )
                        }
                        .filter { it.averageLoss >= minEvalLoss }
                        .sortedWith(compareByDescending<MoveBreakdown> { it.timesPlayed }.thenByDescending { it.averageLoss })
                        .take(3)

                val rawRate = mistakeCount.toDouble() / posOccurrences.size
                val mistakeRatePercentage = kotlin.math.round(rawRate * 10000.0) / 100.0
                weaknesses.add(
                    WeaknessResponse(
                        positionId = positionId,
                        fen = position.fen,
                        timesReached = posOccurrences.size,
                        mistakeCount = mistakeCount,
                        mistakeRate = mistakeRatePercentage,
                        averageLoss = unweightedTotalLoss / mistakeCount,
                        priority = priorityScore * rawRate,
                        bestMove = analysis.bestMove,
                        acceptableMoves = acceptableMoves,
                        movesPlayed = movesPlayed,
                        gameUrls = mistakeUrls.distinct().take(10),
                        evalCp = analysis.baselineEvalCp,
                    ),
                )
            }
        }

        // Sort by Priority descending, excluding zero-priority positions
        return weaknesses.filter { it.priority > 0 }.sortedByDescending { it.priority }
    }

    /**
     * Calculates the evaluation loss between the best move evaluation and a resulting evaluation.
     * Always returns a positive value representing the loss from the perspective of the player who moved.
     *
     * eval_loss_from_best = how many pawns worse this historical move is compared with the engine's best move,
     * from the perspective of the player making the move.
     *
     * Example: If best_move_eval_cp = 20 (+0.20 pawns) and a historical move has eval_cp = -80 (-0.80 pawns),
     * the loss is 1.00 pawn.
     */
    private fun calculateEvalLoss(
        playerColor: String,
        bestMoveEvalCp: Int?,
        resultCp: Int?,
    ): Double {
        if (bestMoveEvalCp == null || resultCp == null) return 0.0

        val bestMovePawns = bestMoveEvalCp / 100.0
        val resultPawns = resultCp / 100.0

        return if (playerColor.equals("WHITE", ignoreCase = true)) {
            max(0.0, bestMovePawns - resultPawns)
        } else {
            max(0.0, resultPawns - bestMovePawns)
        }
    }
}
