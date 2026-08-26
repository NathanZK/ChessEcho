package com.chessecho.service

import com.chessecho.domain.RatingBand
import com.chessecho.dto.HumanMoveFinalizeRequest
import com.chessecho.dto.HumanMoveFinalizeResponse
import com.chessecho.repository.HumanMoveDistributionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Explicit finalization of the human-move distribution corpus.
 *
 * BFS collection does not apply any observation-count threshold; every observed
 * (position, move) pair is persisted so that observations arriving in later
 * batches or later BFS invocations can accumulate against the same position.
 * This service applies the threshold globally, in a single set-based DELETE,
 * once the corpus is considered sufficiently populated.
 *
 * The operation is band-scoped, transactional, and idempotent: rerunning it
 * with the same arguments after a successful invocation removes zero further
 * rows because every remaining position already meets the threshold.
 */
@Service
class HumanMoveDistributionFinalizationService(
    private val humanMoveDistributionRepository: HumanMoveDistributionRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun finalize(request: HumanMoveFinalizeRequest): HumanMoveFinalizeResponse {
        RatingBand.fromValue(request.ratingBand)
            ?: throw IllegalArgumentException("Invalid rating band: ${request.ratingBand}")
        require(request.minObservations >= 1) {
            "minObservations must be >= 1 (got ${request.minObservations})"
        }

        val band = request.ratingBand
        val minObs = request.minObservations

        val positionsEvaluated = humanMoveDistributionRepository.countDistinctPositionsForBand(band).toInt()
        val positionsRemoved =
            humanMoveDistributionRepository
                .subThresholdPositionCountsForBand(band, minObs)
                .size
        val rowsRemoved = humanMoveDistributionRepository.deleteSubThresholdRowsForBand(band, minObs)

        val positionsRetained = positionsEvaluated - positionsRemoved

        log.info(
            "Finalized human_move_distribution for band={} minObs={}: " +
                "positionsEvaluated={} positionsRemoved={} rowsRemoved={} positionsRetained={}",
            band,
            minObs,
            positionsEvaluated,
            positionsRemoved,
            rowsRemoved,
            positionsRetained,
        )

        return HumanMoveFinalizeResponse(
            ratingBand = band,
            minObservations = minObs,
            positionsEvaluated = positionsEvaluated,
            positionsRemoved = positionsRemoved,
            rowsRemoved = rowsRemoved,
            positionsRetained = positionsRetained,
        )
    }
}
