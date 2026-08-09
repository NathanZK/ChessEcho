package com.chessecho.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StockfishServiceTest {
    @Test
    fun `test parseEngineScore for baseline position`() {
        // Stockfish outputs +150 for baseline player to move.
        val line = "info depth 16 seldepth 22 multipv 1 score cp 150 nodes 12345 nps 123456 hashfull 12 tbhits 0 time 10 pv e2e4"
        val score = StockfishService.parseEngineScore(line, invert = false)
        assertEquals(150, score?.cp)
        assertNull(score?.mate)
    }

    @Test
    fun `test parseEngineScore for move evaluation`() {
        // Stockfish outputs +150 for opponent after move (meaning player to move is losing by 1.50).
        // We expect it to be inverted to -150 to represent score from player to move's perspective.
        val line = "info depth 16 seldepth 22 multipv 1 score cp 150 nodes 12345 nps 123456 hashfull 12 tbhits 0 time 10 pv e7e5"
        val score = StockfishService.parseEngineScore(line, invert = true)
        assertEquals(-150, score?.cp)
        assertNull(score?.mate)
    }

    @Test
    fun `test parseEngineScore with negative score for move evaluation`() {
        // Stockfish outputs -124 for opponent after move (meaning opponent is losing by 1.24, player to move is winning by 1.24).
        // We expect it to be inverted to +124.
        val line = "info depth 16 seldepth 22 multipv 1 score cp -124 nodes 12345 nps 123456 hashfull 12 tbhits 0 time 10 pv e7e5"
        val score = StockfishService.parseEngineScore(line, invert = true)
        assertEquals(124, score?.cp)
        assertNull(score?.mate)
    }

    @Test
    fun `test parseEngineScore with mate score for move evaluation`() {
        // Stockfish outputs mate 3 for opponent after move.
        // We expect it to be inverted to -3.
        val line = "info depth 16 seldepth 22 multipv 1 score mate 3 nodes 12345 nps 123456 hashfull 12 tbhits 0 time 10 pv e7e5"
        val score = StockfishService.parseEngineScore(line, invert = true)
        assertNull(score?.cp)
        assertEquals(-3, score?.mate)
    }
}
