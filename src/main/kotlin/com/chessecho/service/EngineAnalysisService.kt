package com.chessecho.service

import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class EngineAnalysisService(
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    private val stockfishService: StockfishService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Executes Stockfish engine analysis for a single position.
     *
     * Architectural Invariants & Key Design Rationale:
     * 1. Query & Transaction Optimization:
     *    Receives an already-managed [Position] entity from the orchestrator, avoiding a per-position `findById` DB lookup.
     *    Calls `findByPositionIdWithMoveEvaluations()` to load the existing analysis and its associated [MoveEvaluation]
     *    collection in a single `LEFT JOIN FETCH` query, allowing already-evaluated moves to be determined in memory.
     *
     * 2. Baseline vs Historical Moves:
     *    The original position is analyzed independently to establish the engine's objective baseline evaluation,
     *    best move, and best-move score. Historical moves obtained from [PositionOccurrence] represent moves actually
     *    played by users in games and are evaluated relative to that objective baseline.
     *
     * 3. Historical Move Persistence & Weakness Tracking:
     *    Every evaluated historical/user move MUST be persisted in [MoveEvaluation]. Severe blunders MUST NOT be
     *    discarded based on evaluation loss because historical blunders are essential evidence for ChessEcho's
     *    weakness detection algorithm. `evalLossFromBest` is an objective numeric measurement, not a binary
     *    acceptable/unacceptable classification.
     *
     * 4. Incremental Analysis:
     *    Engine analysis is global per position, while historical moves accumulate over time as more games are imported.
     *    For existing [EngineAnalysis] entities, only unanalyzed historical moves (`historicalMoves - evaluatedMoves`)
     *    are sent to Stockfish. The existing baseline evaluation is reused and is NOT recomputed.
     */
    @Transactional
    fun analyzePosition(position: Position) {
        val positionId = position.id
        val historicalMoves = positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)
        if (historicalMoves.isEmpty()) {
            return
        }

        // Fetch existing analysis with moveEvaluations in a single query via LEFT JOIN FETCH
        val existingAnalysis = engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)
        val depth = 16

        if (existingAnalysis == null) {
            log.info("Performing full engine analysis for new position $positionId")
            val analysisResults = stockfishService.analyze(position.fen, depth, historicalMoves)

            val baselineResult =
                analysisResults["baseline"]
                    ?: throw IllegalStateException("Baseline analysis missing for position $positionId")

            val bestMove = baselineResult.bestMove
            val bestMoveEvalCp = analysisResults[bestMove]?.score?.cp ?: baselineResult.score.cp

            val engineAnalysis =
                EngineAnalysis(
                    position = position,
                    depth = depth,
                    baselineEvalCp = baselineResult.score.cp,
                    bestMove = bestMove,
                    bestMoveEvalCp = bestMoveEvalCp,
                    analyzedAt = Instant.now(),
                )

            // Persist all user-played historical moves regardless of eval loss (blunders are vital for weakness detection)
            historicalMoves.forEach { move ->
                val result = analysisResults[move]
                if (result != null) {
                    val evalLoss = calculateEvalLoss(bestMoveEvalCp, result.score.cp) ?: 0.0
                    engineAnalysis.moveEvaluations.add(
                        MoveEvaluation(
                            engineAnalysis = engineAnalysis,
                            move = move,
                            evalCp = result.score.cp,
                            evalLossFromBest = evalLoss,
                        ),
                    )
                }
            }

            engineAnalysisRepository.save(engineAnalysis)
        } else {
            // Determine evaluated moves in memory from pre-fetched collection
            val evaluatedMoves = existingAnalysis.moveEvaluations.map { it.move }.toSet()
            val missingMoves = historicalMoves.filter { it !in evaluatedMoves }

            if (missingMoves.isEmpty()) {
                log.debug("Position $positionId has no missing historical moves to analyze")
                return
            }

            log.info("Analyzing ${missingMoves.size} missing historical moves for position $positionId")
            // Analyze only newly discovered historical moves; reuse existing baseline evaluation
            val analysisResults = stockfishService.analyze(position.fen, depth, missingMoves)
            val bestMove = existingAnalysis.bestMove

            val newBestMoveEval =
                if (bestMove != null && analysisResults.containsKey(bestMove)) {
                    analysisResults[bestMove]?.score?.cp
                } else {
                    null
                }

            val refBestMoveEvalCp = newBestMoveEval ?: existingAnalysis.bestMoveEvalCp

            if (newBestMoveEval != null) {
                existingAnalysis.bestMoveEvalCp = newBestMoveEval
            }

            missingMoves.forEach { move ->
                val result = analysisResults[move]
                if (result != null) {
                    val evalLoss = calculateEvalLoss(refBestMoveEvalCp, result.score.cp) ?: 0.0
                    existingAnalysis.moveEvaluations.add(
                        MoveEvaluation(
                            engineAnalysis = existingAnalysis,
                            move = move,
                            evalCp = result.score.cp,
                            evalLossFromBest = evalLoss,
                        ),
                    )
                }
            }

            engineAnalysisRepository.save(existingAnalysis)
        }
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
