package com.chessecho.research

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StockfishResearchRunnerTest {

    @Test
    fun `evaluates standard starting position across depths 8 12 16 20`() {
        val runner = StockfishResearchRunner()
        runner.use { engine ->
            val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

            val eval8 = engine.evaluatePosition(startFen, 8)
            println("Depth 8: bestMove=${eval8.bestMove}, scoreCp=${eval8.scoreCpWhite}, nodes=${eval8.nodes}, time=${eval8.elapsedMs}ms, nps=${eval8.nps}")
            assertTrue(eval8.passedDepthIntegrity)
            assertFalse(eval8.timedOut)
            assertTrue(eval8.reachedDepth >= 8)

            val eval12 = engine.evaluatePosition(startFen, 12)
            println("Depth 12: bestMove=${eval12.bestMove}, scoreCp=${eval12.scoreCpWhite}, nodes=${eval12.nodes}, time=${eval12.elapsedMs}ms, nps=${eval12.nps}")
            assertTrue(eval12.passedDepthIntegrity)
            assertTrue(eval12.reachedDepth >= 12)

            val eval16 = engine.evaluatePosition(startFen, 16)
            println("Depth 16: bestMove=${eval16.bestMove}, scoreCp=${eval16.scoreCpWhite}, nodes=${eval16.nodes}, time=${eval16.elapsedMs}ms, nps=${eval16.nps}")
            assertTrue(eval16.passedDepthIntegrity)
            assertTrue(eval16.reachedDepth >= 16)

            val eval20 = engine.evaluatePosition(startFen, 20)
            println("Depth 20: bestMove=${eval20.bestMove}, scoreCp=${eval20.scoreCpWhite}, nodes=${eval20.nodes}, time=${eval20.elapsedMs}ms, nps=${eval20.nps}")
            assertTrue(eval20.passedDepthIntegrity)
            assertTrue(eval20.reachedDepth >= 20)
        }
    }
}
