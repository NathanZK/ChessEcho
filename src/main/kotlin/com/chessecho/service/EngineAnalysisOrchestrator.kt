package com.chessecho.service

import com.chessecho.repository.PositionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class EngineAnalysisOrchestrator(
    private val positionRepository: PositionRepository,
    private val engineAnalysisService: EngineAnalysisService,
    @Value("\${chess.analysis.min-occurrences:5}")
    private val minOccurrences: Long = DEFAULT_MIN_OCCURRENCES,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEFAULT_MIN_OCCURRENCES = 5L
    }

    /**
     * Determines which affected positions meet the minimum occurrence threshold
     * using a single batch query, then delegates analysis per position with isolated failure handling.
     *
     * @param affectedPositionIds Set of distinct position IDs affected by the game import.
     */
    fun analyzeAffectedPositions(affectedPositionIds: Set<UUID>) {
        if (affectedPositionIds.isEmpty()) return

        val qualifyingPositions = positionRepository.findQualifyingPositions(affectedPositionIds, minOccurrences)
        if (qualifyingPositions.isEmpty()) {
            log.info("No affected positions met the minimum occurrence threshold of $minOccurrences")
            return
        }

        log.info("Found ${qualifyingPositions.size} qualifying positions for engine analysis")

        for (position in qualifyingPositions) {
            try {
                engineAnalysisService.analyzePosition(position)
            } catch (ex: Exception) {
                log.error("Failed to perform engine analysis for position ${position.id}", ex)
            }
        }
    }
}
