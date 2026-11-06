package com.chessecho.research

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class EngineTranspositionTablePolicyTest {

    private val binaryPath = "/opt/homebrew/bin/stockfish"
    private var worker: Depth8EngineWorker? = null

    @BeforeEach
    fun setUp() {
        if (File(binaryPath).exists()) {
            worker = Depth8EngineWorker(binaryPath = binaryPath, configuredMultiPv = 1)
        }
    }

    @AfterEach
    fun tearDown() {
        worker?.close()
    }

    @Test
    fun `test transposition table reset produces order-independent deterministic evaluation`() {
        val w = worker ?: return // Skip if binary unavailable in environment

        // Two distinct positions
        val fenA = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val fenB = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2"

        // Evaluation Sequence 1: A then B
        val resA1 = w.evaluate(fenA)
        val resB1 = w.evaluate(fenB)

        // Evaluation Sequence 2: B then A
        val resB2 = w.evaluate(fenB)
        val resA2 = w.evaluate(fenA)

        // Verify deterministic identity across order
        assertEquals(resA1.bestMove, resA2.bestMove, "Position A bestmove must be order-independent")
        assertEquals(resA1.scoreCp, resA2.scoreCp, "Position A scoreCp must be order-independent")
        assertEquals(resA1.dynamicTokens, resA2.dynamicTokens, "Position A dynamic tokens must be identical")

        assertEquals(resB1.bestMove, resB2.bestMove, "Position B bestmove must be order-independent")
        assertEquals(resB1.scoreCp, resB2.scoreCp, "Position B scoreCp must be order-independent")
        assertEquals(resB1.dynamicTokens, resB2.dynamicTokens, "Position B dynamic tokens must be identical")
    }
}
