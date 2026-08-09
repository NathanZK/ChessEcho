package com.chessecho.service

import com.chessecho.repository.PositionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class EngineAnalysisOrchestrator(
    private val positionRepository: PositionRepository,
    private val engineAnalysisService: EngineAnalysisService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MIN_OCCURRENCES = 5L
    }

    /**
     * Determines which affected positions meet the minimum occurrence threshold
     * using a single batch query, then delegates analysis per position with isolated failure handling.
     *
     * @param affectedPositionIds Set of distinct position IDs affected by the game import.
     */
    fun analyzeAffectedPositions(affectedPositionIds: Set<UUID>) {
        if (affectedPositionIds.isEmpty()) return

        val qualifyingPositionIds = positionRepository.findQualifyingPositionIds(affectedPositionIds, MIN_OCCURRENCES)
        if (qualifyingPositionIds.isEmpty()) {
            log.info("No affected positions met the minimum occurrence threshold of $MIN_OCCURRENCES")
            return
        }

        log.info("Found ${qualifyingPositionIds.size} qualifying positions for engine analysis")

        for (positionId in qualifyingPositionIds) {
            try {
                engineAnalysisService.analyzePosition(positionId)
            } catch (ex: Exception) {
                log.error("Failed to perform engine analysis for position $positionId", ex)
            }
        }
    }
}
