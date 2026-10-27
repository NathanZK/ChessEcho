package com.chessecho.service.continuation

import com.chessecho.service.EngineCandidate
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
        val bestCp = candidates.firstOrNull()?.score?.cp
        val candidateResults = extractMultiPvCandidates(fen, candidates, bestCp).toMutableList()

        if (candidateResults.isEmpty()) {
            log.info("MultiPV search yielded no valid moves for FEN: {}. Running baseline fallback analysis.", fen)
            resolveFallbackCandidate(fen)?.let { candidateResults.add(it) }
        }

        log.info("EngineMoveProvider generated {} continuation candidates for FEN: {}", candidateResults.size, fen)
        return candidateResults
    }

    internal fun extractMultiPvCandidates(
        fen: String,
        candidates: List<EngineCandidate>,
        bestCp: Int?,
    ): List<ContinuationCandidate> = candidates.mapNotNull { toContinuationCandidate(fen, it, bestCp) }

    internal fun toContinuationCandidate(
        fen: String,
        candidate: EngineCandidate,
        bestCp: Int?,
    ): ContinuationCandidate? {
        val move = candidate.move
        if (!isValidEngineMove(move)) return null

        val candidateCp = candidate.score.cp
        val evalLoss = calculateEvalLoss(bestCp, candidateCp)
        if (evalLoss != null && evalLoss > maxEvalLoss) {
            log.debug(
                "Engine candidate move '{}' excluded by maxEvalLoss threshold: loss={} > maxEvalLoss={}",
                move,
                evalLoss,
                maxEvalLoss,
            )
            return null
        }

        val resultingFen = applyMove(fen, move) ?: return null
        val continuation = runCatching { stockfishService.resolveForcingPvContinuation(resultingFen) }.getOrNull()
        return ContinuationCandidate(
            move = move,
            resultingFen = resultingFen,
            providerType = providerType,
            evalCp = candidateCp,
            evalLoss = evalLoss,
            continuationMoves = continuation?.moves ?: emptyList(),
            continuationSanMoves = continuation?.sanMoves ?: emptyList(),
            terminalFen = continuation?.terminalFen ?: resultingFen,
            pliesResolved = continuation?.pliesResolved ?: 0,
            isTerminal = continuation?.isTerminal ?: false,
            terminationReason = continuation?.terminationReason,
        )
    }

    internal fun resolveFallbackCandidate(fen: String): ContinuationCandidate? {
        val fallbackAnalysis = stockfishService.analyze(fen = fen, depth = 16, historicalSanMoves = emptyList())
        val baseline = fallbackAnalysis["baseline"] ?: return null
        val bestMove = baseline.bestMove?.takeIf { isValidEngineMove(it) } ?: return null
        val resultingFen = applyMove(fen, bestMove) ?: return null
        val continuation = runCatching { stockfishService.resolveForcingPvContinuation(resultingFen) }.getOrNull()

        return ContinuationCandidate(
            move = bestMove,
            resultingFen = resultingFen,
            providerType = providerType,
            evalCp = baseline.score.cp,
            evalLoss = 0.0,
            continuationMoves = continuation?.moves ?: emptyList(),
            continuationSanMoves = continuation?.sanMoves ?: emptyList(),
            terminalFen = continuation?.terminalFen ?: resultingFen,
            pliesResolved = continuation?.pliesResolved ?: 0,
            isTerminal = continuation?.isTerminal ?: false,
            terminationReason = continuation?.terminationReason,
        )
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
