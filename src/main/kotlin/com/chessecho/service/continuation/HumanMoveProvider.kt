package com.chessecho.service.continuation

import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.github.bhlangonijr.chesslib.Board
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component("humanMoveProvider")
class HumanMoveProvider(
    private val positionRepository: PositionRepository,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
) : MoveProvider {
    private val log = LoggerFactory.getLogger(javaClass)

    override val providerType: String = "HUMAN"

    override fun getContinuationCandidates(fen: String): List<ContinuationCandidate> {
        log.debug("HumanMoveProvider looking up historical move candidates for FEN: {}", fen)

        val hash = generateHash(fen)
        val position =
            positionRepository.findByHash(hash) ?: run {
                log.info("HumanMoveProvider: position hash {} not found in repository", hash)
                return emptyList()
            }

        val historicalStats = positionOccurrenceRepository.findHistoricalMoveStatsByPositionId(position.id)
        if (historicalStats.isEmpty()) {
            log.info("HumanMoveProvider: no historical occurrences found for positionId={}", position.id)
            return emptyList()
        }

        val candidates = mutableListOf<ContinuationCandidate>()
        for (stat in historicalStats) {
            val sanMove = stat.movePlayed
            val resultingFen = applyMove(fen, sanMove)
            if (resultingFen != null) {
                candidates.add(
                    ContinuationCandidate(
                        move = sanMove,
                        resultingFen = resultingFen,
                        providerType = providerType,
                        timesPlayed = stat.timesPlayed.toInt(),
                    ),
                )
            }
        }

        log.info("HumanMoveProvider selected {} historical move candidates for positionId={}", candidates.size, position.id)
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
