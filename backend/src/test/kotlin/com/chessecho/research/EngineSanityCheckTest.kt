package com.chessecho.research

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class EngineSanityCheckTest {
    @Test
    fun `verify dynamic engine runner output format and token extraction`() {
        val tempCache = File.createTempFile("sanity_engine_cache", ".json").apply { deleteOnExit() }
        val runner =
            DynamicEngineResearchRunner(
                hardCeiling = 10,
                cacheFile = tempCache,
            )

        // Test position with White to move
        val fenW = "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R w KQkq - 1 5"
        val worker = runner.SingleEngineWorker("/opt/homebrew/bin/stockfish", 1)
        worker.use {
            val recW = runner.evaluateSingle(worker, fenW)
            assertNotNull(recW.bestMove)
            assertTrue(recW.principalVariations.isNotEmpty())
            assertTrue(recW.dynamicTokens.isNotEmpty())
            println("White position bestMove: ${recW.bestMove}, score: ${recW.scoreCp}, tokens: ${recW.dynamicTokens.size}")
            println("Tokens sample: ${recW.dynamicTokens.entries.take(5)}")

            // Test position with Black to move
            val fenB = "6rk/p2RR2p/1p4P1/3p4/3Kb3/PP6/8/8 b - - 0 1"
            val recB = runner.evaluateSingle(worker, fenB)
            assertNotNull(recB.bestMove)
            assertTrue(recB.principalVariations.isNotEmpty())
            assertTrue(recB.dynamicTokens.isNotEmpty())
            println("Black position bestMove: ${recB.bestMove}, score: ${recB.scoreCp}, tokens: ${recB.dynamicTokens.size}")
            println("Tokens sample: ${recB.dynamicTokens.entries.take(5)}")
        }
    }
}
