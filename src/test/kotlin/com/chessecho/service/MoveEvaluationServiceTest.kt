package com.chessecho.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MoveEvaluationServiceTest {
    private lateinit var stockfishService: StockfishService
    private lateinit var moveEvaluationService: MoveEvaluationService

    private val whiteFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
    private val blackFen = "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3"

    @BeforeEach
    fun setUp() {
        stockfishService = mock()
        moveEvaluationService =
            MoveEvaluationService(
                stockfishService = stockfishService,
                explorationMaxEvalLoss = 0.80,
            )
    }

    @Test
    fun `1 Best move has evalLoss = 0 and is acceptable`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = 80, mate = null)),
                EngineCandidate(move = "Bc4", score = EvalScore(cp = 65, mate = null)),
            ),
        )

        val result = moveEvaluationService.evaluateMove(whiteFen, "Bb5")

        assertEquals("Bb5", result.bestMove)
        assertEquals("Bb5", result.move)
        assertEquals(80, result.bestEvalCp)
        assertEquals(80, result.evalCp)
        assertEquals(0.0, result.evalLoss)
        assertEquals(0.80, result.maxEvalLoss)
        assertEquals(0.80, result.threshold)
        assertTrue(result.acceptable)
        verify(stockfishService, never()).evaluateSingleMove(any(), any(), any())
    }

    @Test
    fun `2 Exact user move within 0 80 is acceptable`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = 80, mate = null)),
                EngineCandidate(move = "Bc4", score = EvalScore(cp = 15, mate = null)),
            ),
        )

        val result = moveEvaluationService.evaluateMove(whiteFen, "Bc4")

        assertEquals("Bb5", result.bestMove)
        assertEquals("Bc4", result.move)
        assertEquals(80, result.bestEvalCp)
        assertEquals(15, result.evalCp)
        assertEquals(0.65, result.evalLoss)
        assertTrue(result.acceptable)
    }

    @Test
    fun `3 Exact user move above 0 80 is unacceptable`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = 80, mate = null)),
                EngineCandidate(move = "h3", score = EvalScore(cp = -10, mate = null)),
            ),
        )

        val result = moveEvaluationService.evaluateMove(whiteFen, "h3")

        assertEquals("Bb5", result.bestMove)
        assertEquals("h3", result.move)
        assertEquals(80, result.bestEvalCp)
        assertEquals(-10, result.evalCp)
        assertEquals(0.90, result.evalLoss)
        assertFalse(result.acceptable)
    }

    @Test
    fun `4 Exact 0 80 boundary is acceptable if using less-than-or-equal`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = 80, mate = null)),
                EngineCandidate(move = "a3", score = EvalScore(cp = 0, mate = null)),
            ),
        )

        val result = moveEvaluationService.evaluateMove(whiteFen, "a3")

        assertEquals(0.80, result.evalLoss)
        assertTrue(result.acceptable)
    }

    @Test
    fun `5 User move NOT present in MultiPV=5 can still be evaluated and accepted if eval loss is less-than-or-equal 0 80`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = 80, mate = null)),
                EngineCandidate(move = "Bc4", score = EvalScore(cp = 70, mate = null)),
            ),
        )
        whenever(stockfishService.evaluateSingleMove(eq(whiteFen), eq("Be2"), any())).thenReturn(
            PositionAnalysis(bestMove = "Bb5", score = EvalScore(cp = 20, mate = null)),
        )

        val result = moveEvaluationService.evaluateMove(whiteFen, "Be2")

        assertEquals("Bb5", result.bestMove)
        assertEquals("Be2", result.move)
        assertEquals(80, result.bestEvalCp)
        assertEquals(20, result.evalCp)
        assertEquals(0.60, result.evalLoss)
        assertTrue(result.acceptable)
        verify(stockfishService).evaluateSingleMove(eq(whiteFen), eq("Be2"), any())
    }

    @Test
    fun `6 User move NOT present in MultiPV=5 can be rejected if eval loss greater than 0 80`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = 80, mate = null)),
            ),
        )
        whenever(stockfishService.evaluateSingleMove(eq(whiteFen), eq("h4"), any())).thenReturn(
            PositionAnalysis(bestMove = "Bb5", score = EvalScore(cp = -25, mate = null)),
        )

        val result = moveEvaluationService.evaluateMove(whiteFen, "h4")

        assertEquals(1.05, result.evalLoss)
        assertFalse(result.acceptable)
        verify(stockfishService).evaluateSingleMove(eq(whiteFen), eq("h4"), any())
    }

    @Test
    fun `7 Illegal move throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            moveEvaluationService.evaluateMove(whiteFen, "e8")
        }
    }

    @Test
    fun `8 Correct FEN is passed to stockfishService`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = 50, mate = null)),
            ),
        )

        val result = moveEvaluationService.evaluateMove(whiteFen, "Bb5")

        assertEquals(whiteFen, result.fen)
        verify(stockfishService).analyzeMultiPv(eq(whiteFen), any(), eq(5))
    }

    @Test
    fun `9 Evaluation perspective and sign is correct for Black position`() {
        whenever(stockfishService.analyzeMultiPv(eq(blackFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "a6", score = EvalScore(cp = 40, mate = null)),
                EngineCandidate(move = "Nf6", score = EvalScore(cp = -30, mate = null)),
            ),
        )

        val result = moveEvaluationService.evaluateMove(blackFen, "Nf6")

        assertEquals("a6", result.bestMove)
        assertEquals("Nf6", result.move)
        assertEquals(40, result.bestEvalCp)
        assertEquals(-30, result.evalCp)
        assertEquals(0.70, result.evalLoss)
        assertTrue(result.acceptable)
    }

    @Test
    fun `10 Exploration threshold is independent from continuation threshold`() {
        val serviceWithCustomThreshold =
            MoveEvaluationService(
                stockfishService = stockfishService,
                explorationMaxEvalLoss = 0.30,
            )

        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = 80, mate = null)),
                EngineCandidate(move = "Bc4", score = EvalScore(cp = 30, mate = null)),
            ),
        )

        val result = serviceWithCustomThreshold.evaluateMove(whiteFen, "Bc4")

        assertEquals(0.50, result.evalLoss)
        assertEquals(0.30, result.threshold)
        assertFalse(result.acceptable)
    }

    @Test
    fun `11 Winning forced mate-in-1 scores higher than mate-in-2 and candidate is acceptable within tolerance`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = null, mate = 1)),
                EngineCandidate(move = "Bc4", score = EvalScore(cp = null, mate = 2)),
            ),
        )

        val result = moveEvaluationService.evaluateMove(whiteFen, "Bc4")

        assertEquals("Bb5", result.bestMove)
        assertEquals("Bc4", result.move)
        assertEquals(9990, result.bestEvalCp)
        assertEquals(9980, result.evalCp)
        assertEquals(0.10, result.evalLoss)
        assertTrue(result.acceptable)
    }

    @Test
    fun `12 Blunder from winning forced mate into centipawn advantage is rejected with large loss`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = null, mate = 1)),
                EngineCandidate(move = "h3", score = EvalScore(cp = 200, mate = null)),
            ),
        )

        val result = moveEvaluationService.evaluateMove(whiteFen, "h3")

        assertEquals("Bb5", result.bestMove)
        assertEquals("h3", result.move)
        assertEquals(9990, result.bestEvalCp)
        assertEquals(200, result.evalCp)
        assertEquals(97.90, result.evalLoss)
        assertFalse(result.acceptable)
    }

    @Test
    fun `13 Blunder from winning forced mate into opponent forced mate is rejected with large loss`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = null, mate = 1)),
                EngineCandidate(move = "h3", score = EvalScore(cp = null, mate = -1)),
            ),
        )

        val result = moveEvaluationService.evaluateMove(whiteFen, "h3")

        assertEquals("Bb5", result.bestMove)
        assertEquals("h3", result.move)
        assertEquals(9990, result.bestEvalCp)
        assertEquals(-9990, result.evalCp)
        assertEquals(199.80, result.evalLoss)
        assertFalse(result.acceptable)
    }

    @Test
    fun `14 Longer defense against forced mate scores higher than immediate checkmate and is acceptable within tolerance`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = null, mate = -2)),
                EngineCandidate(move = "Bc4", score = EvalScore(cp = null, mate = -1)),
            ),
        )

        val result = moveEvaluationService.evaluateMove(whiteFen, "Bc4")

        assertEquals("Bb5", result.bestMove)
        assertEquals("Bc4", result.move)
        assertEquals(-9980, result.bestEvalCp)
        assertEquals(-9990, result.evalCp)
        assertEquals(0.10, result.evalLoss)
        assertTrue(result.acceptable)
    }

    @Test
    fun `15 Blunder from neutral drawn position into opponent forced mate is rejected with large loss`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = 0, mate = null)),
                EngineCandidate(move = "h3", score = EvalScore(cp = null, mate = -1)),
            ),
        )

        val result = moveEvaluationService.evaluateMove(whiteFen, "h3")

        assertEquals("Bb5", result.bestMove)
        assertEquals("h3", result.move)
        assertEquals(0, result.bestEvalCp)
        assertEquals(-9990, result.evalCp)
        assertEquals(99.90, result.evalLoss)
        assertFalse(result.acceptable)
    }

    @Test
    fun `16 User move NOT present in MultiPV=5 can evaluate forced-mate score via single move evaluation`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = 80, mate = null)),
            ),
        )
        whenever(stockfishService.evaluateSingleMove(eq(whiteFen), eq("Be2"), any())).thenReturn(
            PositionAnalysis(bestMove = "Bb5", score = EvalScore(cp = null, mate = 1)),
        )

        val result = moveEvaluationService.evaluateMove(whiteFen, "Be2")

        assertEquals("Bb5", result.bestMove)
        assertEquals("Be2", result.move)
        assertEquals(80, result.bestEvalCp)
        assertEquals(9990, result.evalCp)
        assertEquals(0.0, result.evalLoss)
        assertTrue(result.acceptable)
        verify(stockfishService).evaluateSingleMove(eq(whiteFen), eq("Be2"), any())
    }

    @Test
    fun `17 Single move evaluation returning null score results in null evalCp, zero evalLoss, and acceptable`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = 100, mate = null)),
            ),
        )
        whenever(stockfishService.evaluateSingleMove(eq(whiteFen), eq("Be2"), any())).thenReturn(null)

        val result = moveEvaluationService.evaluateMove(whiteFen, "Be2")

        assertEquals("Bb5", result.bestMove)
        assertEquals("Be2", result.move)
        assertEquals(100, result.bestEvalCp)
        assertNull(result.evalCp)
        assertEquals(0.0, result.evalLoss)
        assertTrue(result.acceptable)
    }

    @Test
    fun `18 User candidate with non-null score but null cp and null mate returns null evalCp, zero evalLoss, and is acceptable`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(
            listOf(
                EngineCandidate(move = "Bb5", score = EvalScore(cp = 100, mate = null)),
                EngineCandidate(move = "Bc4", score = EvalScore(cp = null, mate = null)),
            ),
        )

        val result = moveEvaluationService.evaluateMove(whiteFen, "Bc4")

        assertEquals("Bb5", result.bestMove)
        assertEquals("Bc4", result.move)
        assertEquals(100, result.bestEvalCp)
        assertNull(result.evalCp)
        assertEquals(0.0, result.evalLoss)
        assertTrue(result.acceptable)
    }

    @Test
    fun `19 Invalid FEN position throws IllegalArgumentException with descriptive message`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                moveEvaluationService.evaluateMove("invalid-fen-string", "e4")
            }

        assertTrue(ex.message?.contains("Invalid FEN position") == true)
        assertNotNull(ex.cause)
    }

    @Test
    fun `20 Empty Stockfish MultiPV candidates throws IllegalStateException with descriptive message`() {
        whenever(stockfishService.analyzeMultiPv(eq(whiteFen), any(), eq(5))).thenReturn(emptyList())

        val ex =
            assertThrows(IllegalStateException::class.java) {
                moveEvaluationService.evaluateMove(whiteFen, "Bb5")
            }

        assertTrue(ex.message?.contains("Stockfish produced no candidate moves") == true)
    }

    @Test
    fun `21 Illegal or unparseable SAN move throws IllegalArgumentException with descriptive message`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                moveEvaluationService.evaluateMove(whiteFen, "e8")
            }

        assertTrue(ex.message?.contains("Illegal or unparseable move") == true)
    }
}
