package com.chessecho.service

import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class EngineAnalysisJob(
    private val positionRepository: PositionRepository,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    private val stockfishService: StockfishService,
) {
    private val logger = LoggerFactory.getLogger(EngineAnalysisJob::class.java)

    /**
     * Periodically queries the database for unanalyzed candidate positions
     * and delegates them to be processed by Stockfish.
     */
    @Scheduled(fixedDelay = 10000) // Run every 10 seconds
    fun processCandidatePositions() {
        val minOccurrences = 5L
        val batchSize = 100

        val candidates =
            positionRepository.findUnanalyzedCandidatePositions(
                minOccurrences = minOccurrences,
                pageable = PageRequest.of(0, batchSize),
            )

        if (candidates.isEmpty()) return
        logger.info("Analyzing ${candidates.size} candidate positions...")

        for (position in candidates) {
            try {
                processPosition(position.id)
            } catch (e: Exception) {
                logger.error("Failed to analyze position ${position.id}", e)
            }
        }
    }

    /**
     * Executes the engine analysis for a single candidate position.
     * Evaluates the baseline position and all unique historical moves played from this position.
     *
     * @param positionId The UUID of the position to analyze
     */
    @Transactional
    fun processPosition(positionId: java.util.UUID) {
        val position = positionRepository.findById(positionId).orElseThrow()
        // Get all unique historical moves from this position
        val historicalMoves =
            positionOccurrenceRepository.findByPositionId(positionId)
                .map { it.movePlayed }
                .distinct()

        val depth = 16
        val analysisResults = stockfishService.analyze(position.fen, depth, historicalMoves)

        val baselineResult =
            analysisResults["baseline"]
                ?: throw IllegalStateException("Baseline analysis missing for position $positionId")

        val engineAnalysis =
            EngineAnalysis(
                position = position,
                depth = depth,
                baselineEvalCp = baselineResult.score.cp,
                baselineEvalMate = baselineResult.score.mate,
                bestMove = baselineResult.bestMove,
                analyzedAt = Instant.now(),
            )

        historicalMoves.forEach { move ->
            val result = analysisResults[move]
            if (result != null) {
                engineAnalysis.moveEvaluations.add(
                    MoveEvaluation(
                        engineAnalysis = engineAnalysis,
                        move = move,
                        evalCp = result.score.cp,
                        evalLossFromBest = null,
                    ),
                )
            }
        }

        engineAnalysisRepository.save(engineAnalysis)
    }
}
