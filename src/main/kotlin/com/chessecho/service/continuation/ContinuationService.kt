package com.chessecho.service.continuation

import com.chessecho.domain.ContinuationMode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

data class ContinuationResult(
    val fen: String,
    val requestedMode: ContinuationMode,
    val effectiveProvider: String,
    val candidates: List<ContinuationCandidate>,
)

@Service
class ContinuationService(
    @Qualifier("engineMoveProvider")
    private val engineMoveProvider: MoveProvider,
    @Qualifier("humanMoveProvider")
    private val humanMoveProvider: MoveProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Orchestrates obtaining continuation candidate moves for the specified position and mode,
     * managing provider selection and HUMAN -> ENGINE fallback while explicitly tracking effective provider type.
     *
     * @param fen The baseline position in FEN notation.
     * @param mode The requested domain continuation mode (ENGINE or HUMAN). Defaults to ContinuationMode.ENGINE.
     * @return [ContinuationResult] containing starting FEN, requestedMode, effectiveProvider, and collection of [ContinuationCandidate]s.
     */
    fun getContinuation(
        fen: String,
        mode: ContinuationMode = ContinuationMode.ENGINE,
        ratingBand: String? = null,
    ): ContinuationResult {
        log.debug("Continuation request received for FEN: {} with mode: {} and ratingBand: {}", fen, mode, ratingBand)

        val (effectiveProvider, candidates) =
            when (mode) {
                ContinuationMode.ENGINE -> {
                    engineMoveProvider.providerType to engineMoveProvider.getContinuationCandidates(fen, ratingBand)
                }

                ContinuationMode.HUMAN -> {
                    val humanCandidates = humanMoveProvider.getContinuationCandidates(fen, ratingBand)
                    if (humanCandidates.isNotEmpty()) {
                        humanMoveProvider.providerType to humanCandidates
                    } else {
                        log.info("HUMAN mode requested for FEN '{}', but no historical moves exist. Falling back to ENGINE mode.", fen)
                        engineMoveProvider.providerType to engineMoveProvider.getContinuationCandidates(fen, ratingBand)
                    }
                }
            }

        return ContinuationResult(
            fen = fen,
            requestedMode = mode,
            effectiveProvider = effectiveProvider,
            candidates = candidates,
        )
    }
}
