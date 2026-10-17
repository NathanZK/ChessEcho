package com.chessecho.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.StringReader

class StockfishServiceTest {
    @Test
    fun `test parseEngineScore for baseline position`() {
        val line = "info depth 16 seldepth 22 multipv 1 score cp 150 nodes 12345 nps 123456 hashfull 12 tbhits 0 time 10 pv e2e4"
        val score = StockfishService.parseEngineScore(line, invert = false)
        assertEquals(150, score?.cp)
        assertNull(score?.mate)
    }

    @Test
    fun `test parseEngineScore for move evaluation`() {
        val line = "info depth 16 seldepth 22 multipv 1 score cp 150 nodes 12345 nps 123456 hashfull 12 tbhits 0 time 10 pv e7e5"
        val score = StockfishService.parseEngineScore(line, invert = true)
        assertEquals(-150, score?.cp)
        assertNull(score?.mate)
    }

    @Test
    fun `test parseEngineScore with negative score for move evaluation`() {
        val line = "info depth 16 seldepth 22 multipv 1 score cp -124 nodes 12345 nps 123456 hashfull 12 tbhits 0 time 10 pv e7e5"
        val score = StockfishService.parseEngineScore(line, invert = true)
        assertEquals(124, score?.cp)
        assertNull(score?.mate)
    }

    @Test
    fun `parseEngineScore maps candidate terminal checkmate score mate 0 with invert = true to mate = 1`() {
        // Opponent is checkmated on the board (0 moves left for opponent).
        // From baseline player perspective, the move delivered mate in 1.
        val line = "info depth 0 score mate 0"
        val score = StockfishService.parseEngineScore(line, invert = true)
        assertNotNull(score)
        assertNull(score?.cp)
        assertEquals(1, score?.mate)
    }

    @Test
    fun `parseEngineScore maps candidate winning mate in 2 with invert = true to mate = 2`() {
        // Post-move opponent gets mated in 1 (score mate -1).
        // Root-relative distance is 1 + 1 = 2 moves from baseline.
        val line = "info depth 15 seldepth 4 multipv 1 score mate -1 pv g6 g7"
        val score = StockfishService.parseEngineScore(line, invert = true)
        assertNotNull(score)
        assertNull(score?.cp)
        assertEquals(2, score?.mate)
    }

    @Test
    fun `parseEngineScore maps candidate winning mate in 3 with invert = true to mate = 3`() {
        // Post-move opponent gets mated in 2 (score mate -2).
        // Root-relative distance is 2 + 1 = 3 moves from baseline.
        val line = "info depth 16 seldepth 6 multipv 1 score mate -2 pv a6 b6"
        val score = StockfishService.parseEngineScore(line, invert = true)
        assertNotNull(score)
        assertNull(score?.cp)
        assertEquals(3, score?.mate)
    }

    @Test
    fun `parseEngineScore maps candidate losing mate in 1 with invert = true to mate = -1`() {
        // Post-move opponent mates in 1 (score mate 1).
        // Player gets mated in 1 from baseline.
        val line = "info depth 14 seldepth 2 multipv 1 score mate 1 pv h5f7"
        val score = StockfishService.parseEngineScore(line, invert = true)
        assertNotNull(score)
        assertNull(score?.cp)
        assertEquals(-1, score?.mate)
    }

    @Test
    fun `parseEngineScore maps candidate losing mate in 3 with invert = true to mate = -3`() {
        // Post-move opponent mates in 3 (score mate 3).
        // Player gets mated in 3 from baseline.
        val line = "info depth 16 seldepth 6 multipv 1 score mate 3 pv h5f7"
        val score = StockfishService.parseEngineScore(line, invert = true)
        assertNotNull(score)
        assertNull(score?.cp)
        assertEquals(-3, score?.mate)
    }

    @Test
    fun `parseEngineScore returns null for baseline score mate 0 with invert = false`() {
        val line = "info depth 0 score mate 0"
        val score = StockfishService.parseEngineScore(line, invert = false)
        assertNull(score)
    }

    @Test
    fun `test parseMultiPvLine correctly parses rank, uciMove, and centipawn score`() {
        val line = "info depth 16 seldepth 22 multipv 2 score cp 35 nodes 12345 nps 123456 hashfull 12 tbhits 0 time 10 pv d2d4 d7d5"
        val triple = StockfishService.parseMultiPvLine(line)
        assertEquals(2, triple?.first)
        assertEquals("d2d4", triple?.second)
        assertEquals(35, triple?.third?.cp)
        assertNull(triple?.third?.mate)
    }

    @Test
    fun `test parseMultiPvLine correctly parses mate score`() {
        val line = "info depth 16 seldepth 22 multipv 1 score mate 4 nodes 12345 nps 123456 hashfull 12 tbhits 0 time 10 pv e2e4 e7e5"
        val triple = StockfishService.parseMultiPvLine(line)
        assertEquals(1, triple?.first)
        assertEquals("e2e4", triple?.second)
        assertNull(triple?.third?.cp)
        assertEquals(4, triple?.third?.mate)
    }

    @Test
    fun `runGoDepth captures terminal checkmate at depth 0 and clears stale centipawns`() {
        val stockfishService = StockfishService()
        val fen = "rnbqkbnr/ppppp2p/5p2/6p1/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 3"
        val simulatedOutput =
            """
            info depth 1 seldepth 1 score cp 200 nodes 10 time 1 pv d1h5
            info depth 0 score mate 0
            bestmove (none)
            """.trimIndent()
        val reader = BufferedReader(StringReader(simulatedOutput))

        val result =
            stockfishService.runGoDepth(
                fen = fen,
                uciMove = "d1h5",
                depth = 16,
                reader = reader,
                sendCommand = {},
            )

        assertNotNull(result)
        assertNull(result.score.cp)
        assertEquals(1, result.score.mate)
        assertEquals("(none)", result.bestMove)
    }
}
