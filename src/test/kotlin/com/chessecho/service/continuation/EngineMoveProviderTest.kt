package com.chessecho.service.continuation

import com.chessecho.service.EngineCandidate
import com.chessecho.service.EvalScore
import com.chessecho.service.PositionAnalysis
import com.chessecho.service.StockfishService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
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
}
