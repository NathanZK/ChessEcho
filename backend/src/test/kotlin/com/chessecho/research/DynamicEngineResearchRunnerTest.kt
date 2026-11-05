package com.chessecho.research

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DynamicEngineResearchRunnerTest {
    @Test
    fun `runner enforces hard ceiling and cache hits do not increment counter`() {
        val tempCache = File.createTempFile("test_engine_cache", ".json").apply { deleteOnExit() }
        val runner =
            DynamicEngineResearchRunner(
                hardCeiling = 2,
                cacheFile = tempCache,
            )

        val pos1 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3"
        val pos2 = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6"
        val pos3 = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq -"

        val worker = runner.SingleEngineWorker("/opt/homebrew/bin/stockfish", 1)
        worker.use {
            // First search
            val res1 = runner.evaluateSingle(worker, pos1)
            assertEquals(1, runner.actualEngineSearchCalls.get())
            assertEquals(0, runner.cacheHits.get())
            assertNotNull(res1.bestMove)
            assertTrue(res1.principalVariations.isNotEmpty())
            assertTrue(res1.dynamicTokens.isNotEmpty())
            assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1", res1.normalizedFen)

            // Cache hit on pos1
            val res1Cached = runner.evaluateSingle(worker, pos1)
            assertEquals(1, runner.actualEngineSearchCalls.get(), "Cache hit must not increment search calls")
            assertEquals(1, runner.cacheHits.get(), "Cache hit counter must increment")
            assertEquals(res1.bestMove, res1Cached.bestMove)

            // Second search (at ceiling = 2)
            val res2 = runner.evaluateSingle(worker, pos2)
            assertEquals(2, runner.actualEngineSearchCalls.get())

            // Third search should exceed ceiling and throw IllegalStateException
            val ex =
                assertThrows(IllegalStateException::class.java) {
                    runner.evaluateSingle(worker, pos3)
                }
            assertTrue(ex.message!!.contains("Strict Stockfish invocation ceiling of 2 exceeded"))
        }
    }
}
