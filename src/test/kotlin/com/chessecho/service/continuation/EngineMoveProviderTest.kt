package com.chessecho.service.continuation

import com.chessecho.service.EngineCandidate
import com.chessecho.service.EvalScore
import com.chessecho.service.PositionAnalysis
import com.chessecho.service.PvContinuationResult
import com.chessecho.service.StockfishService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineMoveProviderTest {
    private val stockfishService: StockfishService = mock()
    private val engineMoveProvider = EngineMoveProvider(stockfishService)

    @Test
    fun `getContinuationCandidates filters MultiPV moves using default maxEvalLoss threshold of 0 50 pawns`() {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        // rank 1: 100cp -> evalLoss = 0.00 (included)
        // rank 2: 70cp  -> evalLoss = 0.30 (included)
        // rank 3: 50cp  -> evalLoss = 0.50 (included, exactly at 0.50 threshold)
        // rank 4: 48cp  -> evalLoss = 0.52 (excluded > 0.50)
        // rank 5: 30cp  -> evalLoss = 0.70 (excluded > 0.50)
        val candidates =
            listOf(
                EngineCandidate("e5", EvalScore(cp = 100, mate = null)),
                EngineCandidate("c5", EvalScore(cp = 70, mate = null)),
                EngineCandidate("e6", EvalScore(cp = 50, mate = null)),
                EngineCandidate("d6", EvalScore(cp = 48, mate = null)),
                EngineCandidate("Nf6", EvalScore(cp = 30, mate = null)),
            )

        whenever(stockfishService.analyzeMultiPv(eq(fen), eq(16), eq(5))).thenReturn(candidates)

        val result = engineMoveProvider.getContinuationCandidates(fen)

        // Verify only ranks 1 to 3 survive the 0.50 threshold
        assertEquals(3, result.size)

        // Rank 1 (0.00 loss)
        assertEquals("e5", result[0].move)
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2", result[0].resultingFen)
        assertEquals(100, result[0].evalCp)
        assertEquals(0.00, result[0].evalLoss!!, 0.001)
        assertEquals("ENGINE", result[0].providerType)

        // Rank 2 (0.30 loss)
        assertEquals("c5", result[1].move)
        assertEquals(70, result[1].evalCp)
        assertEquals(0.30, result[1].evalLoss!!, 0.001)

        // Rank 3 (0.50 loss)
        assertEquals("e6", result[2].move)
        assertEquals(50, result[2].evalCp)
        assertEquals(0.50, result[2].evalLoss!!, 0.001)

        verify(stockfishService).analyzeMultiPv(fen, 16, 5)
    }

    @Test
    fun `getContinuationCandidates includes candidate exactly at 0 50 maxEvalLoss threshold and excludes candidate above 0 50`() {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        // rank 1: 50cp -> evalLoss = 0.00
        // rank 2: 0cp  -> evalLoss = 0.50 (exactly at threshold, included via <= comparison)
        // rank 3: -2cp -> evalLoss = 0.52 (excluded > 0.50)
        val candidates =
            listOf(
                EngineCandidate("e5", EvalScore(cp = 50, mate = null)),
                EngineCandidate("c5", EvalScore(cp = 0, mate = null)),
                EngineCandidate("e6", EvalScore(cp = -2, mate = null)),
            )

        whenever(stockfishService.analyzeMultiPv(eq(fen), eq(16), eq(5))).thenReturn(candidates)

        val result = engineMoveProvider.getContinuationCandidates(fen)

        assertEquals(2, result.size)
        assertEquals("e5", result[0].move)
        assertEquals("c5", result[1].move)
        assertEquals(0.50, result[1].evalLoss!!, 0.001)
    }

    @Test
    fun `getContinuationCandidates preserves Stockfish rank ordering`() {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        val candidates =
            listOf(
                EngineCandidate("e5", EvalScore(cp = 50, mate = null)),
                EngineCandidate("c5", EvalScore(cp = 40, mate = null)),
                EngineCandidate("e6", EvalScore(cp = 30, mate = null)),
            )

        whenever(stockfishService.analyzeMultiPv(eq(fen), eq(16), eq(5))).thenReturn(candidates)

        val result = engineMoveProvider.getContinuationCandidates(fen)

        assertEquals(3, result.size)
        assertEquals("e5", result[0].move)
        assertEquals("c5", result[1].move)
        assertEquals("e6", result[2].move)
    }

    @Test
    fun `rank 1 candidate always survives the quality threshold`() {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        val candidates =
            listOf(
                EngineCandidate("e5", EvalScore(cp = -100, mate = null)),
            )

        whenever(stockfishService.analyzeMultiPv(eq(fen), eq(16), eq(5))).thenReturn(candidates)

        val result = engineMoveProvider.getContinuationCandidates(fen)

        assertEquals(1, result.size)
        assertEquals("e5", result[0].move)
        assertEquals(0.0, result[0].evalLoss)
    }

    @Test
    fun `getContinuationCandidates falls back to analyze baseline when MultiPV returns empty list`() {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        whenever(stockfishService.analyzeMultiPv(eq(fen), eq(16), eq(5))).thenReturn(emptyList())

        val fallbackMap = mapOf("baseline" to PositionAnalysis(bestMove = "c5", score = EvalScore(cp = 15, mate = null)))
        whenever(stockfishService.analyze(fen, 16, emptyList())).thenReturn(fallbackMap)

        val result = engineMoveProvider.getContinuationCandidates(fen)

        assertEquals(1, result.size)
        assertEquals("c5", result[0].move)
        assertEquals("rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2", result[0].resultingFen)
        assertEquals("ENGINE", result[0].providerType)
        assertEquals(15, result[0].evalCp)
        assertEquals(0.0, result[0].evalLoss)
    }

    @Test
    fun `getContinuationCandidates returns empty list when engine yields no valid moves`() {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        whenever(stockfishService.analyzeMultiPv(eq(fen), eq(16), eq(5))).thenReturn(emptyList())
        whenever(stockfishService.analyze(fen, 16, emptyList())).thenReturn(emptyMap())

        val result = engineMoveProvider.getContinuationCandidates(fen)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getContinuationCandidates retains candidates with mate scores and null centipawn evaluations`() {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        val candidates =
            listOf(
                EngineCandidate("e5", EvalScore(cp = null, mate = 1)),
                EngineCandidate("c5", EvalScore(cp = null, mate = 2)),
            )

        whenever(stockfishService.analyzeMultiPv(eq(fen), eq(16), eq(5))).thenReturn(candidates)

        val result = engineMoveProvider.getContinuationCandidates(fen)

        assertEquals(2, result.size)
        assertEquals("e5", result[0].move)
        assertNull(result[0].evalCp)
        assertNull(result[0].evalLoss)

        assertEquals("c5", result[1].move)
        assertNull(result[1].evalCp)
        assertNull(result[1].evalLoss)
    }

    @Test
    fun `getContinuationCandidates excludes invalid engine moves in MultiPV analysis`() {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        val candidates =
            listOf(
                EngineCandidate("(none)", EvalScore(cp = 100, mate = null)),
                EngineCandidate("", EvalScore(cp = 80, mate = null)),
                EngineCandidate("   ", EvalScore(cp = 70, mate = null)),
                EngineCandidate("e5", EvalScore(cp = 60, mate = null)),
            )

        whenever(stockfishService.analyzeMultiPv(eq(fen), eq(16), eq(5))).thenReturn(candidates)

        val result = engineMoveProvider.getContinuationCandidates(fen)

        assertEquals(1, result.size)
        assertEquals("e5", result[0].move)
    }

    @Test
    fun `getContinuationCandidates excludes invalid engine moves in baseline fallback analysis`() {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        whenever(stockfishService.analyzeMultiPv(eq(fen), eq(16), eq(5))).thenReturn(emptyList())

        val fallbackMap = mapOf("baseline" to PositionAnalysis(bestMove = "(none)", score = EvalScore(cp = 50, mate = null)))
        whenever(stockfishService.analyze(fen, 16, emptyList())).thenReturn(fallbackMap)

        val result = engineMoveProvider.getContinuationCandidates(fen)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getContinuationCandidates drops candidates whose moves cannot be legally applied to the board`() {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        val candidates =
            listOf(
                EngineCandidate("O-O", EvalScore(cp = 100, mate = null)),
                EngineCandidate("invalidMove", EvalScore(cp = 90, mate = null)),
                EngineCandidate("e5", EvalScore(cp = 80, mate = null)),
            )

        whenever(stockfishService.analyzeMultiPv(eq(fen), eq(16), eq(5))).thenReturn(candidates)

        val result = engineMoveProvider.getContinuationCandidates(fen)

        assertEquals(1, result.size)
        assertEquals("e5", result[0].move)
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2", result[0].resultingFen)
    }

    @Test
    fun `isValidEngineMove rejects invalid engine moves including none blank and empty`() {
        val method =
            EngineMoveProvider::class.java.getDeclaredMethod("isValidEngineMove", String::class.java).apply {
                isAccessible = true
            }

        fun invokeIsValid(move: String?): Boolean = method.invoke(engineMoveProvider, move) == true

        assertFalse(invokeIsValid("(none)"))
        assertFalse(invokeIsValid(""))
        assertFalse(invokeIsValid("   "))
        assertFalse(invokeIsValid(null))
        assertTrue(invokeIsValid("e5"))
    }

    @Test
    fun `extractMultiPvCandidates extracts valid candidates within threshold`() {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        val candidates =
            listOf(
                EngineCandidate("e5", EvalScore(cp = 100, mate = null)),
                EngineCandidate("c5", EvalScore(cp = 70, mate = null)),
            )
        val extracted = engineMoveProvider.extractMultiPvCandidates(fen, candidates, 100)
        assertEquals(2, extracted.size)
        assertEquals("e5", extracted[0].move)
        assertEquals("c5", extracted[1].move)
    }

    @Test
    fun `resolveFallbackCandidate resolves baseline best move`() {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        val fallbackMap = mapOf("baseline" to PositionAnalysis(bestMove = "c5", score = EvalScore(cp = 15, mate = null)))
        whenever(stockfishService.analyze(fen, 16, emptyList())).thenReturn(fallbackMap)

        val fallback = engineMoveProvider.resolveFallbackCandidate(fen)
        assertEquals("c5", fallback?.move)
        assertEquals(15, fallback?.evalCp)
        assertEquals(0.0, fallback?.evalLoss)
    }

    @Test
    fun `getContinuationCandidates invokes independent continuation search per candidate move`() {
        // Ruy Lopez root FEN: 1. e4 e5 2. Nf3 Nc6 3. Bb5 a6
        val fen = "r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4"
        val candidates =
            listOf(
                EngineCandidate("Ba4", EvalScore(cp = 30, mate = null)),
                EngineCandidate("Bxc6", EvalScore(cp = 20, mate = null)),
            )
        whenever(stockfishService.analyzeMultiPv(eq(fen), eq(16), eq(5))).thenReturn(candidates)

        val ba4Fen = "r1bqkbnr/1ppp1ppp/p1n5/4p3/B3P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 1 4"
        val bxc6Fen = "r1bqkbnr/1ppp1ppp/p1B5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 4"

        val ba4Continuation =
            PvContinuationResult(
                originalFen = ba4Fen,
                terminalFen = ba4Fen,
                moves = emptyList(),
                sanMoves = emptyList(),
                score = EvalScore(cp = 30, mate = null),
                pliesResolved = 0,
                isTerminal = false,
                terminationReason = TerminationReason.QUIET_STOP,
            )
        val bxc6Continuation =
            PvContinuationResult(
                originalFen = bxc6Fen,
                terminalFen = "r1bqkbnr/1pp2ppp/p1p5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 5",
                moves = listOf("d7c6"),
                sanMoves = listOf("dxc6"),
                score = EvalScore(cp = 20, mate = null),
                pliesResolved = 1,
                isTerminal = false,
                terminationReason = TerminationReason.QUIET_STOP,
            )

        whenever(stockfishService.resolveForcingPvContinuation(eq(ba4Fen), any())).thenReturn(ba4Continuation)
        whenever(stockfishService.resolveForcingPvContinuation(eq(bxc6Fen), any())).thenReturn(bxc6Continuation)

        val results = engineMoveProvider.getContinuationCandidates(fen)

        assertEquals(2, results.size)

        // Candidate 1: Ba4 (0 plies)
        val cand1 = results[0]
        assertEquals("Ba4", cand1.move)
        assertEquals(ba4Fen, cand1.resultingFen)
        assertEquals(0, cand1.pliesResolved)
        assertTrue(cand1.continuationMoves.isEmpty())
        assertTrue(cand1.continuationSanMoves.isEmpty())
        assertEquals(ba4Fen, cand1.terminalFen)
        assertEquals(TerminationReason.QUIET_STOP, cand1.terminationReason)

        // Candidate 2: Bxc6 (1 ply recapture)
        val cand2 = results[1]
        assertEquals("Bxc6", cand2.move)
        assertEquals(bxc6Fen, cand2.resultingFen)
        assertEquals(1, cand2.pliesResolved)
        assertEquals(listOf("d7c6"), cand2.continuationMoves)
        assertEquals(listOf("dxc6"), cand2.continuationSanMoves)
        assertEquals("r1bqkbnr/1pp2ppp/p1p5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 5", cand2.terminalFen)
        assertEquals(TerminationReason.QUIET_STOP, cand2.terminationReason)

        // Verify independent search calls
        verify(stockfishService).resolveForcingPvContinuation(eq(ba4Fen), any())
        verify(stockfishService).resolveForcingPvContinuation(eq(bxc6Fen), any())
    }

    @Test
    fun `getContinuationCandidates isolates exception in one candidate continuation and preserves others`() {
        val fen = "r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4"
        val candidates =
            listOf(
                EngineCandidate("Ba4", EvalScore(cp = 30, mate = null)),
                EngineCandidate("Bxc6", EvalScore(cp = 20, mate = null)),
            )
        whenever(stockfishService.analyzeMultiPv(eq(fen), eq(16), eq(5))).thenReturn(candidates)

        val ba4Fen = "r1bqkbnr/1ppp1ppp/p1n5/4p3/B3P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 1 4"
        val bxc6Fen = "r1bqkbnr/1ppp1ppp/p1B5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 4"

        val bxc6Continuation =
            PvContinuationResult(
                originalFen = bxc6Fen,
                terminalFen = "r1bqkbnr/1pp2ppp/p1p5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 5",
                moves = listOf("d7c6"),
                sanMoves = listOf("dxc6"),
                score = EvalScore(cp = 20, mate = null),
                pliesResolved = 1,
                isTerminal = false,
                terminationReason = TerminationReason.QUIET_STOP,
            )

        // Ba4 continuation throws an exception (e.g. process crash or I/O failure)
        whenever(stockfishService.resolveForcingPvContinuation(eq(ba4Fen), any()))
            .thenThrow(RuntimeException("Engine subprocess crash"))
        whenever(stockfishService.resolveForcingPvContinuation(eq(bxc6Fen), any()))
            .thenReturn(bxc6Continuation)

        val results = engineMoveProvider.getContinuationCandidates(fen)

        assertEquals(2, results.size)

        // Candidate 1: Ba4 falls back safely to its resulting FEN with 0 plies and null terminationReason
        val cand1 = results[0]
        assertEquals("Ba4", cand1.move)
        assertEquals(ba4Fen, cand1.resultingFen)
        assertEquals(0, cand1.pliesResolved)
        assertTrue(cand1.continuationMoves.isEmpty())
        assertTrue(cand1.continuationSanMoves.isEmpty())
        assertEquals(ba4Fen, cand1.terminalFen)
        assertNull(cand1.terminationReason)

        // Candidate 2: Bxc6 is unaffected and retains its full continuation
        val cand2 = results[1]
        assertEquals("Bxc6", cand2.move)
        assertEquals(bxc6Fen, cand2.resultingFen)
        assertEquals(1, cand2.pliesResolved)
        assertEquals(listOf("d7c6"), cand2.continuationMoves)
        assertEquals(listOf("dxc6"), cand2.continuationSanMoves)
        assertEquals("r1bqkbnr/1pp2ppp/p1p5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 5", cand2.terminalFen)
        assertEquals(TerminationReason.QUIET_STOP, cand2.terminationReason)
    }
}
