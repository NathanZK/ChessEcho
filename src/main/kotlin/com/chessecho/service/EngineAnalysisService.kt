package com.chessecho.service

import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class EngineAnalysisService(
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    private val stockfishService: StockfishService,
    @Value("\${engine.analysis.multi-pv:5}")
    private val multiPv: Int = DEFAULT_MULTI_PV,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEFAULT_MULTI_PV = 5
    }

    /**
     * Executes Stockfish engine analysis for a single position.
     *
     * Architectural Invariants & Key Design Rationale:
     * 1. Query & Transaction Optimization:
     *    Receives an already-managed [Position] entity from the orchestrator, avoiding a per-position `findById` DB lookup.
     *    Calls `findByPositionIdWithMoveEvaluations()` to load the existing analysis and its associated [MoveEvaluation]
     *    collection in a single `LEFT JOIN FETCH` query, allowing already-evaluated moves to be determined in memory.
     *
     * 2. Baseline vs Candidate Moves (Historical + MultiPV Engine Moves):
     *    The original position is analyzed independently to establish the engine's objective baseline evaluation,
     *    best move, and best-move score. Historical moves obtained from [PositionOccurrence] and top-N engine candidates
     *    obtained via Stockfish MultiPV search are merged and deduplicated. Every candidate is evaluated relative to that objective baseline.
     *
     * 3. Candidate Persistence & Weakness Tracking:
     *    Every evaluated candidate move MUST be persisted in [MoveEvaluation]. Severe blunders MUST NOT be
     *    discarded based on evaluation loss because historical blunders are essential evidence for ChessEcho's
     *    weakness detection algorithm. `evalLossFromBest` is an objective numeric measurement, not a binary
     *    acceptable/unacceptable classification.
     *
     * 4. Incremental Analysis:
     *    Engine analysis is global per position, while historical moves accumulate over time as more games are imported.
     *    For existing [EngineAnalysis] entities, only unanalyzed candidates (`mergedCandidates - evaluatedMoves`)
     *    are sent to Stockfish. The existing baseline evaluation is reused and is NOT recomputed.
     */
    @Transactional
    fun analyzePosition(position: Position) {
        val totalStart = System.currentTimeMillis()
        val positionId = position.id
        val historicalMoves = positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)
        if (historicalMoves.isEmpty()) {
            return
        }

        // Fetch existing analysis with moveEvaluations in a single query via LEFT JOIN FETCH
        val existingAnalysis = engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)
        val depth = 16

        // 1. BEFORE MultiPV analysis
        log.info(
            "BEFORE MultiPV analysis: positionId={} multiPv={} depth={}",
            positionId,
            multiPv,
            depth,
        )
        log.debug("MultiPV analysis position FEN: positionId={} fen='{}'", positionId, position.fen)

        val multiPvStart = System.currentTimeMillis()
        val engineCandidates = stockfishService.analyzeMultiPv(position.fen, depth, multiPv)
        val multiPvDurationMs = System.currentTimeMillis() - multiPvStart

        val engineCandidateMoves = engineCandidates.map { it.move }
        val multiPvScoreMap = engineCandidates.associate { it.move to it.score }

        // AFTER MultiPV analysis
        log.info(
            "AFTER MultiPV analysis: positionId={} count={} durationMs={}",
            positionId,
            engineCandidates.size,
            multiPvDurationMs,
        )

        val historicalSet = historicalMoves.toSet()
        val engineSet = engineCandidateMoves.toSet()

        val mergedCandidates = (historicalMoves + engineCandidateMoves).distinct()
        val remainingHistoricalMoves = historicalMoves.filter { it !in multiPvScoreMap }

        // Candidate merge summary logging (INFO for aggregate counts, DEBUG for full move lists)
        log.info(
            "Candidate merge summary: positionId={} historicalCount={} engineCount={} mergedCount={} secondaryAnalysisCount={}",
            positionId,
            historicalMoves.size,
            engineCandidateMoves.size,
            mergedCandidates.size,
            remainingHistoricalMoves.size,
        )
        log.debug(
            "Candidate merge details: positionId={} engineCandidates={} historicalCandidates={} " +
                "reusedFromMultiPv={} requiringIndividualAnalysis={}",
            positionId,
            engineCandidateMoves,
            historicalMoves,
            engineCandidateMoves,
            remainingHistoricalMoves,
        )

        mergedCandidates.forEach { move ->
            val origin =
                when {
                    move in historicalSet && move in engineSet -> "both"
                    move in historicalSet -> "historical"
                    else -> "engine"
                }
            val evalSource = if (move in multiPvScoreMap) "MULTIPV" else "HISTORICAL_SINGLE"
            log.debug("Merged candidate origin: positionId={} move={} origin={} evalSource={}", positionId, move, origin, evalSource)
        }

        if (existingAnalysis == null) {
            log.info("Performing full engine analysis for new position $positionId")

            val rank1Candidate = engineCandidates.firstOrNull()

            val evaluatedMap = mutableMapOf<String, EvalScore>()
            var bestMove: String? = null
            var bestMoveEvalCp: Int? = null
            var candidateEvalDurationMs = 0L

            if (rank1Candidate != null) {
                // Primary path: MultiPV search returned engine candidates (rank 1 = best move)
                bestMove = rank1Candidate.move
                bestMoveEvalCp = rank1Candidate.score.cp
                engineCandidates.forEach { candidate ->
                    evaluatedMap[candidate.move] = candidate.score
                }

                log.info(
                    "BEFORE baseline Stockfish analysis: positionId={} bestMove={} baselineEvalCp={} depth={}",
                    positionId,
                    bestMove,
                    bestMoveEvalCp,
                    depth,
                )

                if (remainingHistoricalMoves.isNotEmpty()) {
                    log.info(
                        "BEFORE candidate evaluation: positionId={} candidateCount={}",
                        positionId,
                        remainingHistoricalMoves.size,
                    )
                    log.debug("Candidates requiring secondary analysis: positionId={} moves={}", positionId, remainingHistoricalMoves)
                    val evalStart = System.currentTimeMillis()
                    val remainingResults = stockfishService.analyze(position.fen, depth, remainingHistoricalMoves)
                    candidateEvalDurationMs = System.currentTimeMillis() - evalStart
                    remainingHistoricalMoves.forEach { move ->
                        val result = remainingResults[move]
                        if (result != null) {
                            evaluatedMap[move] = result.score
                        }
                    }
                } else {
                    log.info(
                        "Skipping secondary Stockfish search: all historical candidates captured in MultiPV top-N for positionId={}",
                        positionId,
                    )
                }
            } else {
                // Fallback path: MultiPV returned empty list, run full stockfishService.analyze
                log.info("BEFORE baseline Stockfish analysis: positionId={} depth={}", positionId, depth)
                log.info(
                    "BEFORE candidate evaluation: positionId={} candidateCount={}",
                    positionId,
                    mergedCandidates.size,
                )
                log.debug("Fallback candidate moves: positionId={} moves={}", positionId, mergedCandidates)
                val evalStart = System.currentTimeMillis()
                val analysisResults = stockfishService.analyze(position.fen, depth, mergedCandidates)
                candidateEvalDurationMs = System.currentTimeMillis() - evalStart

                val baselineResult =
                    analysisResults["baseline"]
                        ?: throw IllegalStateException("Baseline analysis missing for position $positionId")
                bestMove = baselineResult.bestMove
                bestMoveEvalCp = analysisResults[bestMove]?.score?.cp ?: baselineResult.score.cp

                mergedCandidates.forEach { move ->
                    val result = analysisResults[move]
                    if (result != null) {
                        evaluatedMap[move] = result.score
                    }
                }
            }

            // AFTER baseline Stockfish analysis
            log.info(
                "Baseline analysis completed: positionId={} bestMove={} baselineEvalCp={} depth={} durationMs={}",
                positionId,
                bestMove,
                bestMoveEvalCp,
                depth,
                candidateEvalDurationMs,
            )

            val engineAnalysis =
                EngineAnalysis(
                    position = position,
                    depth = depth,
                    baselineEvalCp = bestMoveEvalCp,
                    bestMove = bestMove ?: "",
                    bestMoveEvalCp = bestMoveEvalCp,
                    analyzedAt = Instant.now(),
                )

            var successfullyEvaluatedCount = 0
            val newEvaluatedMoves = mutableListOf<String>()

            // AFTER candidate analysis (log per evaluated candidate at DEBUG level)
            mergedCandidates.forEach { move ->
                val score = evaluatedMap[move]
                if (score != null) {
                    val evalLoss = calculateEvalLoss(bestMoveEvalCp, score.cp) ?: 0.0
                    val evalStr = score.cp?.let { "${it}cp" } ?: score.mate?.let { "mate $it" } ?: "N/A"
                    val origin =
                        when {
                            move in historicalSet && move in engineSet -> "both"
                            move in historicalSet -> "historical"
                            else -> "engine"
                        }
                    val evalSource = if (move in multiPvScoreMap) "MULTIPV" else "HISTORICAL_SINGLE"
                    log.debug(
                        "Evaluated candidate: positionId={} move={} origin={} evalSource={} eval={} evalLossFromBest={}",
                        positionId,
                        move,
                        origin,
                        evalSource,
                        evalStr,
                        evalLoss,
                    )
                    engineAnalysis.moveEvaluations.add(
                        MoveEvaluation(
                            engineAnalysis = engineAnalysis,
                            move = move,
                            evalCp = score.cp,
                            evalLossFromBest = evalLoss,
                        ),
                    )
                    successfullyEvaluatedCount++
                    newEvaluatedMoves.add(move)
                }
            }

            log.info(
                "Candidate evaluation completed: positionId={} count={} durationMs={}",
                positionId,
                successfullyEvaluatedCount,
                candidateEvalDurationMs,
            )

            // BEFORE/AFTER MoveEvaluation persistence
            log.info(
                "BEFORE MoveEvaluation persistence: positionId={} count={}",
                positionId,
                newEvaluatedMoves.size,
            )
            log.debug("Moves to persist: positionId={} moves={}", positionId, newEvaluatedMoves)

            engineAnalysisRepository.save(engineAnalysis)

            log.info(
                "AFTER MoveEvaluation persistence: positionId={} totalSaved={}",
                positionId,
                engineAnalysis.moveEvaluations.size,
            )
        } else {
            // Determine evaluated moves in memory from pre-fetched collection
            val evaluatedMoves = existingAnalysis.moveEvaluations.map { it.move }.toSet()
            val missingMoves = mergedCandidates.filter { it !in evaluatedMoves }

            if (missingMoves.isEmpty()) {
                log.debug("Position $positionId has no missing moves to analyze")
                val totalDurationMs = System.currentTimeMillis() - totalStart
                log.info("Total position analysis completed: positionId={} durationMs={}", positionId, totalDurationMs)
                return
            }

            log.info("Analyzing ${missingMoves.size} missing moves for position $positionId")

            val evaluatedMap = mutableMapOf<String, EvalScore>()
            val missingFromMultiPv = missingMoves.filter { it !in multiPvScoreMap }

            missingMoves.forEach { move ->
                if (move in multiPvScoreMap) {
                    evaluatedMap[move] = multiPvScoreMap[move]!!
                }
            }

            var candidateEvalDurationMs = 0L
            if (missingFromMultiPv.isNotEmpty()) {
                // BEFORE candidate evaluation (incremental)
                log.info(
                    "BEFORE candidate evaluation (incremental): positionId={} candidateCount={}",
                    positionId,
                    missingFromMultiPv.size,
                )
                log.debug("Missing candidate moves requiring secondary analysis: positionId={} moves={}", positionId, missingFromMultiPv)

                val evalStart = System.currentTimeMillis()
                val analysisResults = stockfishService.analyze(position.fen, depth, missingFromMultiPv)
                candidateEvalDurationMs = System.currentTimeMillis() - evalStart

                missingFromMultiPv.forEach { move ->
                    val result = analysisResults[move]
                    if (result != null) {
                        evaluatedMap[move] = result.score
                    }
                }
            }

            val bestMove = existingAnalysis.bestMove
            val refBestMoveEvalCp = existingAnalysis.bestMoveEvalCp

            var successfullyEvaluatedCount = 0
            val newEvaluatedMoves = mutableListOf<String>()

            // AFTER candidate analysis (log per evaluated candidate at DEBUG level)
            missingMoves.forEach { move ->
                val score = evaluatedMap[move]
                if (score != null) {
                    val evalLoss = calculateEvalLoss(refBestMoveEvalCp, score.cp) ?: 0.0
                    val evalStr = score.cp?.let { "${it}cp" } ?: score.mate?.let { "mate $it" } ?: "N/A"
                    val origin =
                        when {
                            move in historicalSet && move in engineSet -> "both"
                            move in historicalSet -> "historical"
                            else -> "engine"
                        }
                    val evalSource = if (move in multiPvScoreMap) "MULTIPV" else "HISTORICAL_SINGLE"
                    log.debug(
                        "Evaluated candidate (incremental): positionId={} move={} origin={} evalSource={} eval={} evalLossFromBest={}",
                        positionId,
                        move,
                        origin,
                        evalSource,
                        evalStr,
                        evalLoss,
                    )
                    existingAnalysis.moveEvaluations.add(
                        MoveEvaluation(
                            engineAnalysis = existingAnalysis,
                            move = move,
                            evalCp = score.cp,
                            evalLossFromBest = evalLoss,
                        ),
                    )
                    successfullyEvaluatedCount++
                    newEvaluatedMoves.add(move)
                }
            }

            log.info(
                "Candidate evaluation completed: positionId={} count={} durationMs={}",
                positionId,
                successfullyEvaluatedCount,
                candidateEvalDurationMs,
            )

            // BEFORE/AFTER MoveEvaluation persistence
            log.info(
                "BEFORE MoveEvaluation persistence: positionId={} count={}",
                positionId,
                newEvaluatedMoves.size,
            )
            log.debug("Missing moves to persist: positionId={} moves={}", positionId, newEvaluatedMoves)

            engineAnalysisRepository.save(existingAnalysis)

            log.info(
                "AFTER MoveEvaluation persistence: positionId={} totalSaved={}",
                positionId,
                existingAnalysis.moveEvaluations.size,
            )
        }

        val totalDurationMs = System.currentTimeMillis() - totalStart
        val avoidedSearches = mergedCandidates.size - remainingHistoricalMoves.size
        log.info(
            "Optimization summary: positionId={} totalCandidates={} reusedFromMultiPv={} " +
                "requiringIndividualAnalysis={} avoidedStockfishSearches={} totalDurationMs={}",
            positionId,
            mergedCandidates.size,
            engineCandidateMoves.size,
            remainingHistoricalMoves.size,
            avoidedSearches,
            totalDurationMs,
        )
    }

    /**
     * Calculates evaluation loss in pawns relative to Stockfish's best move.
     *
     * Scores are pre-normalized by [StockfishService] to the perspective of the player to move in the baseline position
     * (positive scores indicate advantage for the player to move). Therefore, `bestMoveEvalCp - moveEvalCp` is valid
     * for both White and Black positions.
     */
    private fun calculateEvalLoss(
        bestMoveEvalCp: Int?,
        moveEvalCp: Int?,
    ): Double? {
        if (bestMoveEvalCp == null || moveEvalCp == null) return null
        return maxOf(0.0, (bestMoveEvalCp - moveEvalCp) / 100.0)
    }
}
