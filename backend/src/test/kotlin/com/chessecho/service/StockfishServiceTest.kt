package com.chessecho.service

import com.chessecho.service.continuation.TerminationReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.BufferedReader
import java.io.OutputStreamWriter
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

    private fun simulatedReader(vararg lines: String): BufferedReader {
        return BufferedReader(StringReader(lines.joinToString("\n")))
    }

    private fun isStockfishOnPath(): Boolean {
        return try {
            val process = ProcessBuilder("stockfish").start()
            val writer = OutputStreamWriter(process.outputStream)
            writer.write("quit\n")
            writer.flush()
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Test
    fun `resolveForcingPvContinuation resolves forcing recapture and stops at quiet move`() {
        val stockfishService = StockfishService()
        // Ruy Lopez post Bxc6: black must recapture (e.g. dxc6)
        val fen = "r1bqkbnr/1ppp1ppp/p1B5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 4"
        val reader =
            simulatedReader(
                "info depth 8 score cp -40 nodes 500 pv d7c6 d2d4",
                "bestmove d7c6",
            )
        val result =
            stockfishService.resolveForcingPvContinuation(
                fen = fen,
                options = QuiescenceOptions(timeoutMs = 1000L),
                reader = reader,
            )

        assertEquals(1, result.pliesResolved)
        assertEquals(listOf("d7c6"), result.moves)
        assertEquals(listOf("dxc6"), result.sanMoves)
        assertFalse(result.isTerminal)
        assertEquals(TerminationReason.QUIET_STOP, result.terminationReason)
        assertNotNull(result.terminalFen)
        assertTrue(result.terminalFen.startsWith("r1bqkbnr/1pp2ppp/p1p5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R"))
    }

    @Test
    fun `resolveForcingPvContinuation halts with 0 plies when first PV move is quiet`() {
        val stockfishService = StockfishService()
        // Ruy Lopez post Ba4: black's standard reply is Nf6 (quiet)
        val fen = "r1bqkbnr/1ppp1ppp/p1n5/4p3/B3P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 1 4"
        val reader =
            simulatedReader(
                "info depth 8 score cp -35 nodes 500 pv g8f6 e1g1",
                "bestmove g8f6",
            )
        val result =
            stockfishService.resolveForcingPvContinuation(
                fen = fen,
                options = QuiescenceOptions(timeoutMs = 1000L),
                reader = reader,
            )

        assertEquals(0, result.pliesResolved)
        assertTrue(result.moves.isEmpty())
        assertTrue(result.sanMoves.isEmpty())
        assertEquals(fen, result.terminalFen)
        assertFalse(result.isTerminal)
        assertEquals(TerminationReason.QUIET_STOP, result.terminationReason)
    }

    @Test
    fun `resolveForcingPvContinuation handles checkmate terminal root immediately`() {
        val stockfishService = StockfishService()
        // Fool's mate terminal position
        val checkmateFen = "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3"
        val result = stockfishService.resolveForcingPvContinuation(checkmateFen)

        assertEquals(0, result.pliesResolved)
        assertTrue(result.moves.isEmpty())
        assertTrue(result.sanMoves.isEmpty())
        assertEquals(checkmateFen, result.terminalFen)
        assertTrue(result.isTerminal)
        assertEquals(TerminationReason.TERMINAL, result.terminationReason)
        assertNull(result.score)
    }

    @Test
    fun `resolveForcingPvContinuation handles stalemate terminal root immediately`() {
        val stockfishService = StockfishService()
        // Stalemate position (black king has no legal moves and is not in check)
        val stalemateFen = "k7/8/1Q6/8/8/8/8/7K b - - 0 1"
        val result = stockfishService.resolveForcingPvContinuation(stalemateFen)

        assertEquals(0, result.pliesResolved)
        assertTrue(result.moves.isEmpty())
        assertTrue(result.sanMoves.isEmpty())
        assertEquals(stalemateFen, result.terminalFen)
        assertTrue(result.isTerminal)
        assertEquals(TerminationReason.TERMINAL, result.terminationReason)
        assertNull(result.score)
    }

    @Test
    fun `resolveForcingPvContinuation throws IllegalArgumentException on malformed FEN`() {
        val stockfishService = StockfishService()
        assertThrows<IllegalArgumentException> {
            stockfishService.resolveForcingPvContinuation("invalid-fen-string")
        }
    }

    @Test
    fun `resolveForcingPvContinuation timeout bounds execution and preserves service usability`() {
        var destroyed = false
        val hangingProcess =
            object : Process() {
                private val pipeIn = java.io.PipedInputStream()
                private val pipeOut = java.io.PipedOutputStream(pipeIn)

                override fun getOutputStream(): java.io.OutputStream = pipeOut

                override fun getInputStream(): java.io.InputStream = pipeIn

                override fun getErrorStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))

                override fun waitFor(): Int = 0

                override fun exitValue(): Int = 0

                override fun destroy() {
                    destroyed = true
                    try {
                        pipeOut.close()
                    } catch (_: Exception) {
                    }
                    try {
                        pipeIn.close()
                    } catch (_: Exception) {
                    }
                }

                override fun destroyForcibly(): Process {
                    destroy()
                    return this
                }
            }

        val stockfishService = StockfishService(processStarter = { hangingProcess })
        val fen = "r1bqkbnr/1ppp1ppp/p1B5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 4"

        val startTime = System.currentTimeMillis()
        val timeoutResult =
            stockfishService.resolveForcingPvContinuation(
                fen = fen,
                options = QuiescenceOptions(timeoutMs = 1L),
            )
        val duration = System.currentTimeMillis() - startTime

        assertTrue(duration <= 300L, "Expected execution within timeout budget but took $duration ms")
        assertEquals(0, timeoutResult.pliesResolved)
        assertTrue(timeoutResult.moves.isEmpty())
        assertTrue(timeoutResult.sanMoves.isEmpty())
        assertEquals(fen, timeoutResult.terminalFen)
        assertNull(timeoutResult.score)
        assertEquals(TerminationReason.TIMEOUT, timeoutResult.terminationReason)
        assertTrue(destroyed, "Expected hanging process to be destroyed on timeout")

        // Verify service remains healthy and usable for subsequent calls
        val responsiveProcess =
            object : Process() {
                private val inputStream =
                    java.io.ByteArrayInputStream(
                        "info depth 8 score cp -40 nodes 500 pv d7c6 d2d4\nbestmove d7c6\n".toByteArray(),
                    )
                private val outputStream = java.io.ByteArrayOutputStream()

                override fun getOutputStream(): java.io.OutputStream = outputStream

                override fun getInputStream(): java.io.InputStream = inputStream

                override fun getErrorStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))

                override fun waitFor(): Int = 0

                override fun exitValue(): Int = 0

                override fun destroy() {}

                override fun destroyForcibly(): Process = this
            }
        val healthyService = StockfishService(processStarter = { responsiveProcess })
        val normalResult =
            healthyService.resolveForcingPvContinuation(
                fen = fen,
                options = QuiescenceOptions(timeoutMs = 1000L),
            )
        assertEquals(TerminationReason.QUIET_STOP, normalResult.terminationReason)
        assertEquals(1, normalResult.pliesResolved)
    }

    @Test
    fun `resolveForcingPvContinuation handles process launch failure with ENGINE_ERROR`() {
        val failingService =
            StockfishService(
                processStarter = { throw java.io.IOException("Cannot run program stockfish") },
            )
        val fen = "r1bqkbnr/1ppp1ppp/p1B5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 4"
        val result = failingService.resolveForcingPvContinuation(fen)
        assertEquals(0, result.pliesResolved)
        assertTrue(result.moves.isEmpty())
        assertEquals(fen, result.terminalFen)
        assertEquals(TerminationReason.ENGINE_ERROR, result.terminationReason)
    }

    @Test
    fun `resolveForcingPvContinuation resolves exactly three forcing plies on Opera sacrifice`() {
        val stockfishService = StockfishService()
        // Morphy Opera Game post 13. Rxd7:
        // Position: 3rkb1r/p2R1ppp/4qn2/1B2p1B1/4P3/1Q6/PPP2PPP/2K4R b k - 0 13
        val operaAfterRxd7 = "3rkb1r/p2R1ppp/4qn2/1B2p1B1/4P3/1Q6/PPP2PPP/2K4R b k - 0 13"
        val reader =
            simulatedReader(
                "info depth 8 score cp 800 nodes 1200 pv d8d7 b3e6 f7e6 c1b1",
                "bestmove d8d7",
            )
        val result =
            stockfishService.resolveForcingPvContinuation(
                fen = operaAfterRxd7,
                options = QuiescenceOptions(timeoutMs = 1000L),
                reader = reader,
            )

        assertEquals(3, result.pliesResolved)
        assertEquals(listOf("d8d7", "b3e6", "f7e6"), result.moves)
        assertEquals(listOf("Rxd7", "Qxe6+", "fxe6"), result.sanMoves)
        assertFalse(result.isTerminal)
        assertEquals(TerminationReason.QUIET_STOP, result.terminationReason)
        assertEquals("4kb1r/p2r2pp/4pn2/1B2p1B1/4P3/8/PPP2PPP/2K4R w k - 0 15", result.terminalFen)
    }

    @Test
    fun `resolveForcingPvContinuation detects checkmate delivered during replay and marks TERMINAL`() {
        val stockfishService = StockfishService()
        // Scholar's mate attack: White to move, Qxf7# is immediate checkmate on replay
        val scholarsMateFen = "r1bqk1nr/pppp1ppp/2n5/2b1p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
        val reader =
            simulatedReader(
                "info depth 1 score mate 1 nodes 50 pv f3f7",
                "bestmove f3f7",
            )
        val result =
            stockfishService.resolveForcingPvContinuation(
                fen = scholarsMateFen,
                options = QuiescenceOptions(timeoutMs = 1000L),
                reader = reader,
            )

        assertEquals(1, result.pliesResolved)
        assertEquals(listOf("f3f7"), result.moves)
        assertEquals(listOf("Qxf7#"), result.sanMoves)
        assertTrue(result.isTerminal)
        assertEquals(TerminationReason.TERMINAL, result.terminationReason)
        assertEquals("r1bqk1nr/pppp1Qpp/2n5/2b1p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4", result.terminalFen)
    }

    @Test
    fun `resolveForcingPvContinuation truncates forcing line when maxPlies limit is reached`() {
        val stockfishService = StockfishService()
        // Morphy Opera sacrifice line normally produces 3 plies: Rxd7, Qxe6+, fxe6
        val operaAfterRxd7 = "3rkb1r/p2R1ppp/4qn2/1B2p1B1/4P3/1Q6/PPP2PPP/2K4R b k - 0 13"
        val reader =
            simulatedReader(
                "info depth 8 score cp 800 nodes 1200 pv d8d7 b3e6 f7e6",
                "bestmove d8d7",
            )
        val result =
            stockfishService.resolveForcingPvContinuation(
                fen = operaAfterRxd7,
                options = QuiescenceOptions(maxPlies = 2, timeoutMs = 1000L),
                reader = reader,
            )

        assertEquals(2, result.pliesResolved)
        assertEquals(listOf("d8d7", "b3e6"), result.moves)
        assertEquals(listOf("Rxd7", "Qxe6+"), result.sanMoves)
        assertFalse(result.isTerminal)
        assertEquals(TerminationReason.PLY_LIMIT, result.terminationReason)
        assertEquals("4kb1r/p2r1ppp/4Qn2/1B2p1B1/4P3/8/PPP2PPP/2K4R b k - 0 14", result.terminalFen)
    }

    @Test
    fun `resolveForcingPvContinuation classifies quiet check evasion as forcing and applies it`() {
        val stockfishService = StockfishService()
        // Position: Black King on e8 in check from White Queen on h5, e8d8 is quiet check evasion
        val checkEvasionFen = "4k3/4p3/8/7Q/8/8/8/6K1 b - - 0 1"
        val reader =
            simulatedReader(
                "info depth 4 score cp -500 nodes 100 pv e8d8 g1f2",
                "bestmove e8d8",
            )
        val result =
            stockfishService.resolveForcingPvContinuation(
                fen = checkEvasionFen,
                options = QuiescenceOptions(timeoutMs = 1000L),
                reader = reader,
            )

        assertEquals(1, result.pliesResolved)
        assertEquals(listOf("e8d8"), result.moves)
        assertEquals(listOf("Kd8"), result.sanMoves)
        assertFalse(result.isTerminal)
        assertEquals(TerminationReason.QUIET_STOP, result.terminationReason)
        assertEquals("3k4/4p3/8/7Q/8/8/8/6K1 w - - 1 2", result.terminalFen)
    }

    @Test
    fun `resolveForcingPvContinuation executes live Stockfish subprocess when available on PATH`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(isStockfishOnPath(), "Stockfish binary not installed on host PATH")
        val stockfishService = StockfishService()
        val opera = "3rkb1r/p2R1ppp/4qn2/1B2p1B1/4P3/1Q6/PPP2PPP/2K4R b k - 0 13"
        val result = stockfishService.resolveForcingPvContinuation(opera, QuiescenceOptions(timeoutMs = 5000L))
        assertEquals(3, result.pliesResolved)
        assertEquals(listOf("d8d7", "b3e6", "f7e6"), result.moves)
        assertEquals(listOf("Rxd7", "Qxe6+", "fxe6"), result.sanMoves)
    }
}
