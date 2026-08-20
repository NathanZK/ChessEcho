package com.chessecho.service.continuation

import com.chessecho.domain.HumanMoveDistribution
import com.chessecho.domain.RatingBand
import com.chessecho.repository.HumanMoveDistributionRepository
import com.chessecho.repository.PositionRepository
import com.github.bhlangonijr.chesslib.Board
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component("humanMoveProvider")
class HumanMoveProvider(
    private val positionRepository: PositionRepository,
    private val humanMoveDistributionRepository: HumanMoveDistributionRepository,
    @Value("\${human.continuation.min-observations:10}")
    private val minObservations: Int = 10,
) : MoveProvider {
    private val log = LoggerFactory.getLogger(javaClass)

    override val providerType: String = "HUMAN"

    override fun getContinuationCandidates(
        fen: String,
        ratingBand: String?,
    ): List<ContinuationCandidate> {
        log.debug("HumanMoveProvider looking up historical move candidates for FEN: {} in band: {}", fen, ratingBand)

        if (ratingBand == null || !RatingBand.isValid(ratingBand)) {
            log.info("HumanMoveProvider: invalid or missing rating band '{}'", ratingBand)
            return emptyList()
        }

        val requestedBand = RatingBand.fromValue(ratingBand)!!

        val hash = generateHash(fen)
        val position =
            positionRepository.findByHash(hash) ?: run {
                log.info("HumanMoveProvider: position hash {} not found in repository", hash)
                return emptyList()
            }

        // 1. Try exact requested band
        var distribution = humanMoveDistributionRepository.findByPositionIdAndRatingBand(position.id, requestedBand.value)
        if (isEligible(distribution)) {
            log.info("HumanMoveProvider: found eligible distribution for positionId={} in exact band {}", position.id, requestedBand.value)
            return mapToCandidates(fen, distribution)
        }

        // 2. Try adjacent bands if exact is insufficient
        val adjacentBands = RatingBand.getAdjacentBands(requestedBand)
        for (adjacentBand in adjacentBands) {
            val adjacentDistribution = humanMoveDistributionRepository.findByPositionIdAndRatingBand(position.id, adjacentBand.value)
            if (isEligible(adjacentDistribution)) {
                log.info(
                    "HumanMoveProvider: found eligible distribution for positionId={} in adjacent band {}",
                    position.id,
                    adjacentBand.value,
                )
                return mapToCandidates(fen, adjacentDistribution)
            }
        }

        log.info(
            "HumanMoveProvider: no eligible historical occurrences found for positionId={} in band {} or adjacents",
            position.id,
            ratingBand,
        )
        return emptyList()
    }

    private fun isEligible(distribution: List<HumanMoveDistribution>): Boolean {
        if (distribution.isEmpty()) return false
        val totalObservations = distribution.sumOf { it.observationCount }
        return totalObservations >= minObservations
    }

    private fun mapToCandidates(
        fen: String,
        distribution: List<HumanMoveDistribution>,
    ): List<ContinuationCandidate> {
        val candidates = mutableListOf<ContinuationCandidate>()

        // Order by observation count descending
        val sortedDistribution = distribution.sortedByDescending { it.observationCount }

        for (dist in sortedDistribution) {
            val sanMove = dist.movePlayed
            val resultingFen = applyMove(fen, sanMove)
            if (resultingFen != null) {
                candidates.add(
                    ContinuationCandidate(
                        move = sanMove,
                        resultingFen = resultingFen,
                        providerType = providerType,
                        timesPlayed = dist.observationCount,
                    ),
                )
            }
        }

        return candidates
    }

    private fun applyMove(
        fen: String,
        sanMove: String,
    ): String? {
        return try {
            val board = Board()
            board.loadFromFen(fen)
            if (board.doMove(sanMove)) {
                board.fen
            } else {
                null
            }
        } catch (e: Exception) {
            log.error("Exception applying move $sanMove to FEN $fen", e)
            null
        }
    }

    /**
     * Generates standard 4-part FEN SHA-256 hash matching GameParserService.
     */
    private fun generateHash(fen: String): String {
        val parts = fen.split(" ")
        val cleanedFen =
            if (parts.size >= 4) {
                "${parts[0]} ${parts[1]} ${parts[2]} ${parts[3]}"
            } else {
                fen
            }
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(cleanedFen.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
