package com.chessecho.service.continuation

import com.chessecho.service.StockfishService
import com.github.bhlangonijr.chesslib.Board
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component("engineMoveProvider")
class EngineMoveProvider(
    private val stockfishService: StockfishService,
    @Value("\${engine.continuation.multi-pv:5}")
    private val multiPv: Int = DEFAULT_MULTI_PV,
    @Value("\${engine.continuation.max-eval-loss:0.50}")
    private val maxEvalLoss: Double = DEFAULT_MAX_EVAL_LOSS,
) : MoveProvider {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEFAULT_MULTI_PV = 5
        const val DEFAULT_MAX_EVAL_LOSS = 0.50
    }

    override val providerType: String = "ENGINE"

    override fun getContinuationCandidates(
        fen: String,
        ratingBand: String?,
    ): List<ContinuationCandidate> {
        val configuredMultiPv = maxOf(2, multiPv)
        log.debug(
            "Requesting engine continuation candidates for FEN: {} with MultiPV={} and maxEvalLoss={}",
            fen,
            configuredMultiPv,
            maxEvalLoss,
        )

        val candidates = stockfishService.analyzeMultiPv(fen = fen, depth = 16, multiPv = configuredMultiPv)
        val candidateResults = mutableListOf<ContinuationCandidate>()

        val rank1Candidate = candidates.firstOrNull()
        val bestCp = rank1Candidate?.score?.cp

        for (candidate in candidates) {
            val move = candidate.move
            if (isValidEngineMove(move)) {
                val candidateCp = candidate.score.cp
                val evalLoss = calculateEvalLoss(bestCp, candidateCp)

                // Filter candidates by evaluation loss relative to rank 1 (using <= threshold comparison)
                if (evalLoss == null || evalLoss <= maxEvalLoss) {
                    val resultingFen = applyMove(fen, move)
                    if (resultingFen != null) {
                        candidateResults.add(
                            ContinuationCandidate(
                                move = move,
                                resultingFen = resultingFen,
                                providerType = providerType,
                                evalCp = candidateCp,
                                evalLoss = evalLoss,
                            ),
                        )
                    }
                } else {
                    log.debug(
                        "Engine candidate move '{}' excluded by maxEvalLoss threshold: loss={} > maxEvalLoss={}",
                        move,
                        evalLoss,
                        maxEvalLoss,
                    )
                }
            }
        }

        if (candidateResults.isEmpty()) {
            // Fallback to baseline analysis if MultiPV returned empty candidate set
            log.info("MultiPV search yielded no valid moves for FEN: {}. Running baseline fallback analysis.", fen)
            val fallbackAnalysis = stockfishService.analyze(fen = fen, depth = 16, historicalSanMoves = emptyList())
            val baseline = fallbackAnalysis["baseline"]
            val bestMove = baseline?.bestMove?.takeIf { isValidEngineMove(it) }
            if (bestMove != null) {
                val resultingFen = applyMove(fen, bestMove)
                if (resultingFen != null) {
                    candidateResults.add(
                        ContinuationCandidate(
                            move = bestMove,
                            resultingFen = resultingFen,
                            providerType = providerType,
                            evalCp = baseline.score.cp,
                            evalLoss = 0.0,
                        ),
                    )
                }
            }
        }

        log.info("EngineMoveProvider generated {} continuation candidates for FEN: {}", candidateResults.size, fen)
        return candidateResults
    }

    private fun calculateEvalLoss(
        bestCp: Int?,
        candidateCp: Int?,
    ): Double? {
        if (bestCp == null || candidateCp == null) return null
        return maxOf(0.0, (bestCp - candidateCp) / 100.0)
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

    private fun isValidEngineMove(move: String?): Boolean = !move.isNullOrBlank() && move != "(none)"
}
