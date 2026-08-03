package com.chessecho.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StockfishServiceTest {
    @Test
    fun `test parseEngineScore when evaluating White`() {
        // Stockfish outputs +150 for White. evaluatingWhite is true.
        val line = "info depth 16 seldepth 22 multipv 1 score cp 150 nodes 12345 nps 123456 hashfull 12 tbhits 0 time 10 pv e2e4"
        val score = StockfishService.parseEngineScore(line, evaluatingWhite = true)
        assertEquals(150, score?.cp)
        assertNull(score?.mate)
    }

    @Test
    fun `test parseEngineScore when evaluating Black`() {
        // Stockfish outputs +150 for Black (meaning Black is winning by 1.50). evaluatingWhite is false.
        // We expect it to be negated to -150 so it represents the absolute score from White's perspective.
        val line = "info depth 16 seldepth 22 multipv 1 score cp 150 nodes 12345 nps 123456 hashfull 12 tbhits 0 time 10 pv e7e5"
        val score = StockfishService.parseEngineScore(line, evaluatingWhite = false)
        assertEquals(-150, score?.cp)
        assertNull(score?.mate)
    }

    @Test
    fun `test parseEngineScore with negative score when evaluating Black`() {
        // Stockfish outputs -124 for Black (meaning Black is losing by 1.24). evaluatingWhite is false.
        // We expect it to be negated to +124.
        val line = "info depth 16 seldepth 22 multipv 1 score cp -124 nodes 12345 nps 123456 hashfull 12 tbhits 0 time 10 pv e7e5"
        val score = StockfishService.parseEngineScore(line, evaluatingWhite = false)
        assertEquals(124, score?.cp)
        assertNull(score?.mate)
    }

    @Test
    fun `test parseEngineScore with mate score when evaluating Black`() {
        // Stockfish outputs mate 3 for Black (Black has mate in 3). evaluatingWhite is false.
        // We expect it to be negated to -3.
        val line = "info depth 16 seldepth 22 multipv 1 score mate 3 nodes 12345 nps 123456 hashfull 12 tbhits 0 time 10 pv e7e5"
        val score = StockfishService.parseEngineScore(line, evaluatingWhite = false)
        assertNull(score?.cp)
        assertEquals(-3, score?.mate)
    }
}
