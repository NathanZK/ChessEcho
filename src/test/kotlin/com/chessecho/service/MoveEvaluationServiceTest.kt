package com.chessecho.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
}
