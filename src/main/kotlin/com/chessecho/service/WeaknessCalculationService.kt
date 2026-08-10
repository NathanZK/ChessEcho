package com.chessecho.service

import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.dto.AcceptableMove
import com.chessecho.dto.MoveBreakdown
import com.chessecho.dto.WeaknessResponse
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEFAULT_MIN_EVAL_LOSS = 0.8
        const val DEFAULT_MIN_TIMES_REACHED = 3
        const val DEFAULT_MIN_MISTAKE_COUNT = 3
    }

    @Transactional(readOnly = true)
    fun getWeaknesses(
        platform: Platform,
        username: String,
        playerColor: PlayerColor,
        minEvalLoss: Double = DEFAULT_MIN_EVAL_LOSS,
        minMistakeCount: Int = DEFAULT_MIN_MISTAKE_COUNT,
        minTimesReached: Int = DEFAULT_MIN_TIMES_REACHED,
    ): List<WeaknessResponse> {
        val startTime = System.currentTimeMillis()
        require(minEvalLoss >= 0.0) { "minEvalLoss must be non-negative" }

        val account =
            chessAccountRepository.findByPlatformAndUsernameIgnoreCase(platform.name, username)
                ?: run {
                    log.info(
                        "Weakness calculation failed: account not found for platform={} username={} playerColor={} minEvalLoss={}",
                        platform, username, playerColor, minEvalLoss,
                    )
                    throw NoSuchElementException("Chess account not found")
                }

        val color = playerColor.name

        // Diagnostic counts for structured observability
        val totalOccurrences = positionOccurrenceRepository.countByChessAccountId(account.id)
        val colorFilteredOccurrences = positionOccurrenceRepository.countByChessAccountIdAndPlayerColorOrBoth(account.id, color)

        // 1. Perform database-level aggregation to filter qualifying positions and compute core mistake metrics
        val aggregations =
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = account.id,
                playerColor = color,
                minEvalLoss = minEvalLoss,
                minTimesReached = minTimesReached,
                minMistakeCount = minMistakeCount.toLong(),
            )

        if (aggregations.isEmpty()) {
            val durationMs = System.currentTimeMillis() - startTime
            log.info(
                "Weakness calculation pipeline: accountId={} platform={} username={} playerColor={} minEvalLoss={} " +
                    "minTimesReached={} minMistakeCount={} totalOccurrences={} colorFilteredOccurrences={} qualifyingPositions=0 " +
                    "finalResultCount=0 durationMs={}",
                account.id,
                platform,
                username,
                playerColor,
                minEvalLoss,
                minTimesReached,
                minMistakeCount,
                totalOccurrences,
                colorFilteredOccurrences,
                durationMs,
            )
            return emptyList()
        }

        val positionIds = aggregations.map { it.positionId }.toSet()

        // 2. Batch fetch position occurrences for only the qualifying positions
        val occurrences =
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                chessAccountId = account.id,
                playerColor = color,
                positionIds = positionIds,
            )
        val groupedOccurrences = occurrences.groupBy { it.position.id }

        // 3. Batch fetch engine analyses with move evaluations for only qualifying positions
        val engineAnalyses = engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(positionIds)
        val groupedAnalyses = engineAnalyses.associateBy { it.position.id }

        val weaknesses = mutableListOf<WeaknessResponse>()

        for (agg in aggregations) {
            val positionId = agg.positionId
            val posOccurrences = groupedOccurrences[positionId] ?: continue
            val analysis = groupedAnalyses[positionId] ?: continue

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

                if (evalLoss >= minEvalLoss) {
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
                        .filter { it.evalLoss < minEvalLoss }
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
                        .filter { it.averageLoss >= minEvalLoss }
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

        val result = weaknesses.filter { it.priority > 0 }.sortedByDescending { it.priority }
        val durationMs = System.currentTimeMillis() - startTime

        log.info(
            "Weakness calculation pipeline: accountId={} platform={} username={} playerColor={} minEvalLoss={} " +
                "minTimesReached={} minMistakeCount={} totalOccurrences={} colorFilteredOccurrences={} qualifyingPositions={} " +
                "finalResultCount={} durationMs={}",
            account.id,
            platform,
            username,
            playerColor,
            minEvalLoss,
            minTimesReached,
            minMistakeCount,
            totalOccurrences,
            colorFilteredOccurrences,
            aggregations.size,
            result.size,
            durationMs,
        )

        return result
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
