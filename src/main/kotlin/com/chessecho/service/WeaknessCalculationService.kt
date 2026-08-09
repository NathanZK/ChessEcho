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
    companion object {
        const val DEFAULT_MISTAKE_THRESHOLD = 0.8
        const val DEFAULT_MIN_TIMES_REACHED = 3
        const val DEFAULT_MIN_MISTAKE_COUNT = 3
    }

    /**
     * Dynamically calculates recurring weaknesses for a specific user, platform, and color at request time.
     *
     * Architectural Design & Domain Semantics:
     * - Dynamic Thresholding: Weakness calculation is an interpretation of objective engine-analysis data.
     *   `MoveEvaluation.evalLossFromBest` is the sole source of truth. No threshold-specific `UserPositionWeakness`
     *   records are persisted, allowing different thresholds (e.g. 0.3 vs 0.8) to operate on the exact same stored data.
     * - Database Aggregation: Filtering candidate positions (by `UserPositionStats.timesReached >= minTimesReached`),
     *   evaluating mistakes (`evalLossFromBest >= mistakeThreshold`), and computing `mistakeCount` and `averageLoss`
     *   are performed via a database-level JPQL aggregation query. Zero Stockfish calls are executed.
     * - Weakness Metrics:
     *   - `mistakeCount`: Number of occurrences where user's move had `evalLossFromBest >= mistakeThreshold`.
     *   - `mistakeRate`: `(mistakeCount / timesReached) * 100.0` percentage.
     *   - `averageLoss`: Average `evalLossFromBest` (in pawns) across mistake occurrences.
     *   - `priority`: Time-decay weighted loss total multiplied by raw mistake rate (`mistakeCount / timesReached`).
     */
    @Transactional(readOnly = true)
    fun getWeaknesses(
        platform: String,
        username: String,
        playerColor: String,
        mistakeThreshold: Double = DEFAULT_MISTAKE_THRESHOLD,
        minMistakeCount: Int = DEFAULT_MIN_MISTAKE_COUNT,
        minTimesReached: Int = DEFAULT_MIN_TIMES_REACHED,
    ): List<WeaknessResponse> {
        require(mistakeThreshold >= 0.0) { "mistakeThreshold must be non-negative" }

        val account =
            chessAccountRepository.findByPlatformAndUsernameIgnoreCase(platform, username)
                ?: throw NoSuchElementException("Chess account not found")

        val color = playerColor.uppercase()

        // 1. Perform database-level aggregation to filter qualifying positions and compute core mistake metrics
        val aggregations =
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = account.id,
                playerColor = color,
                mistakeThreshold = mistakeThreshold,
                minTimesReached = minTimesReached,
                minMistakeCount = minMistakeCount.toLong(),
            )

        if (aggregations.isEmpty()) return emptyList()

        val occurrences = positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, color)
        val groupedOccurrences = occurrences.groupBy { it.position.id }

        val weaknesses = mutableListOf<WeaknessResponse>()

        for (agg in aggregations) {
            val positionId = agg.positionId
            val posOccurrences = groupedOccurrences[positionId] ?: continue

            val analysis = engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId) ?: continue

            val bestMoveEvalCp = analysis.bestMoveEvalCp

            var unweightedTotalLoss = 0.0
            var priorityScore = 0.0
            var mistakeCount = 0
            val mistakeUrls = mutableListOf<String>()
            val moveStats = mutableMapOf<String, Pair<Double, Int>>()

            for (occ in posOccurrences) {
                val moveEval = analysis.moveEvaluations.find { it.move == occ.movePlayed } ?: continue
                val evalLoss = moveEval.evalLossFromBest ?: calculateEvalLoss(bestMoveEvalCp, moveEval.evalCp)

                val (prevLoss, prevCount) = moveStats.getOrDefault(occ.movePlayed, Pair(0.0, 0))
                moveStats[occ.movePlayed] = Pair(prevLoss + evalLoss, prevCount + 1)

                if (evalLoss >= mistakeThreshold) {
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
                val acceptableMoves =
                    analysis.moveEvaluations
                        .map { moveEval ->
                            val loss = moveEval.evalLossFromBest ?: calculateEvalLoss(bestMoveEvalCp, moveEval.evalCp)
                            AcceptableMove(move = moveEval.move, evalLoss = loss)
                        }
                        .filter { it.evalLoss < mistakeThreshold }
                        .sortedBy { it.evalLoss }

                val movesPlayed =
                    moveStats.entries
                        .map { (move, stats) ->
                            MoveBreakdown(
                                move = move,
                                timesPlayed = stats.second,
                                averageLoss = stats.first / stats.second,
                            )
                        }
                        .filter { it.averageLoss >= mistakeThreshold }
                        .sortedWith(compareByDescending<MoveBreakdown> { it.timesPlayed }.thenByDescending { it.averageLoss })
                        .take(3)

                val rawRate = mistakeCount.toDouble() / agg.timesReached
                val mistakeRatePercentage = kotlin.math.round(rawRate * 10000.0) / 100.0

                weaknesses.add(
                    WeaknessResponse(
                        positionId = positionId,
                        fen = agg.fen,
                        timesReached = agg.timesReached,
                        mistakeCount = mistakeCount,
                        mistakeRate = mistakeRatePercentage,
                        averageLoss = if (mistakeCount > 0) unweightedTotalLoss / mistakeCount else (agg.averageLoss ?: 0.0),
                        priority = priorityScore * rawRate,
                        bestMove = agg.bestMove,
                        acceptableMoves = acceptableMoves,
                        movesPlayed = movesPlayed,
                        gameUrls = mistakeUrls.distinct().take(10),
                        evalCp = agg.baselineEvalCp,
                    ),
                )
            }
        }

        return weaknesses.filter { it.priority > 0 }.sortedByDescending { it.priority }
    }

    /**
     * Fallback calculation for evaluation loss if evalLossFromBest is null.
     */
    private fun calculateEvalLoss(
        bestMoveEvalCp: Int?,
        resultCp: Int?,
    ): Double {
        if (bestMoveEvalCp == null || resultCp == null) return 0.0
        return maxOf(0.0, (bestMoveEvalCp - resultCp) / 100.0)
    }
}
