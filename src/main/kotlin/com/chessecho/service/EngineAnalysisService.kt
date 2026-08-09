package com.chessecho.service

import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class EngineAnalysisService(
    private val positionRepository: PositionRepository,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    private val stockfishService: StockfishService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Executes engine analysis for a single candidate position.
     * Evaluates baseline and historical moves played from this position.
     * For existing positions, only missing historical moves are analyzed.
     */
    @Transactional
    fun analyzePosition(positionId: UUID) {
        val position =
            positionRepository.findById(positionId)
                .orElseThrow { IllegalStateException("Position not found: $positionId") }

        val historicalMoves = positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)
        if (historicalMoves.isEmpty()) {
            return
        }

        val existingAnalysis = engineAnalysisRepository.findByPositionId(positionId)
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

            historicalMoves.forEach { move ->
                val result = analysisResults[move]
                if (result != null) {
                    val evalLoss = calculateEvalLoss(bestMoveEvalCp, result.score.cp)
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
            val evaluatedMoves = engineAnalysisRepository.findEvaluatedMovesByPositionId(positionId).toSet()
            val missingMoves = historicalMoves.filter { it !in evaluatedMoves }

            if (missingMoves.isEmpty()) {
                log.debug("Position $positionId has no missing historical moves to analyze")
                return
            }

            log.info("Analyzing ${missingMoves.size} missing historical moves for position $positionId")
            val analysisResults = stockfishService.analyze(position.fen, depth, missingMoves)

            val bestMoveEvalCp = existingAnalysis.bestMoveEvalCp

            missingMoves.forEach { move ->
                val result = analysisResults[move]
                if (result != null) {
                    val evalLoss = calculateEvalLoss(bestMoveEvalCp, result.score.cp)
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

    private fun calculateEvalLoss(
        bestMoveEvalCp: Int?,
        moveEvalCp: Int?,
    ): Double? {
        if (bestMoveEvalCp == null || moveEvalCp == null) return null
        return maxOf(0.0, (bestMoveEvalCp - moveEvalCp) / 100.0)
    }
}
