package com.chessprobe.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StockfishServiceBatchTest {
    @Test
    fun `1 normal completion at requested depth`() {
        val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val candFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val candId = UUID.randomUUID()

        val process =
            createFakeProcess { cmd, out ->
                when {
                    cmd == "uci" -> out.println("uciok")
                    cmd.startsWith("setoption name MultiPV") -> {}
                    cmd == "isready" -> out.println("readyok")
                    cmd.startsWith("go depth") -> {
                        out.println("info depth 8 multipv 1 score cp 30 pv e2e4 e7e5")
                        out.println("bestmove e2e4")
                    }
                }
            }

        val service = StockfishService(processStarter = { process })
        val result =
            service.batchResolveContinuations(
                queryFen = queryFen,
                candidates = listOf(candId to candFen),
                options = DynamicAnalysisOptions(depth = 8, multiPv = 1),
            )

        assertNull(result.fallbackReason)
        assertEquals(SearchStatus.COMPLETED, result.queryResult.status)
        assertEquals(8, result.queryResult.achievedDepth)
        assertEquals(1, result.queryResult.principalVariations.size)
        assertEquals(listOf("e2e4", "e7e5"), result.queryResult.principalVariations[0].moves)

        val candResult = checkNotNull(result.candidateResults[candId])
        assertEquals(SearchStatus.COMPLETED, candResult.status)
        assertEquals(8, candResult.achievedDepth)
        assertEquals(1, candResult.principalVariations.size)
        assertEquals(1, result.engineCompletedCount)
        assertEquals(1, result.engineAttemptedCount)
    }

    @Test
    fun `2 MultiPV tracking across iterative-deepening updates retaining latest info per rank`() {
        val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        val process =
            createFakeProcess { cmd, out ->
                when {
                    cmd == "uci" -> out.println("uciok")
                    cmd.startsWith("setoption name MultiPV") -> {}
                    cmd == "isready" -> out.println("readyok")
                    cmd.startsWith("go depth") -> {
                        // Iterative deepening depth 4
                        out.println("info depth 4 multipv 1 score cp 20 pv e2e4")
                        out.println("info depth 4 multipv 2 score cp 10 pv d2d4")
                        // Iterative deepening depth 8 with deeper lines
                        out.println("info depth 8 multipv 1 score cp 35 pv e2e4 e7e5 g1f3")
                        out.println("info depth 8 multipv 2 score cp 25 pv d2d4 d7d5 c2c4")
                        out.println("bestmove e2e4")
                    }
                }
            }

        val service = StockfishService(processStarter = { process })
        val result =
            service.batchResolveContinuations(
                queryFen = queryFen,
                candidates = emptyList(),
                options = DynamicAnalysisOptions(depth = 8, multiPv = 2),
            )

        assertEquals(SearchStatus.COMPLETED, result.queryResult.status)
        assertEquals(8, result.queryResult.achievedDepth)
        assertEquals(2, result.queryResult.principalVariations.size)

        val pv1 = result.queryResult.principalVariations.first { it.rank == 1 }
        assertEquals(8, pv1.achievedDepth)
        assertEquals(listOf("e2e4", "e7e5", "g1f3"), pv1.moves)

        val pv2 = result.queryResult.principalVariations.first { it.rank == 2 }
        assertEquals(8, pv2.achievedDepth)
        assertEquals(listOf("d2d4", "d7d5", "c2c4"), pv2.moves)
    }

    @Test
    fun `3 watchdog timeout followed by successful stop and bestmove drain`() {
        val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val cand1 = UUID.randomUUID() to "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val cand2 = UUID.randomUUID() to "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3"

        var currentFen = ""
        val process =
            createFakeProcess { cmd, out ->
                when {
                    cmd == "uci" -> out.println("uciok")
                    cmd == "isready" -> out.println("readyok")
                    cmd.startsWith("position fen $queryFen") -> currentFen = queryFen
                    cmd.startsWith("position fen ${cand1.second}") -> currentFen = cand1.second
                    cmd.startsWith("position fen ${cand2.second}") -> currentFen = cand2.second
                    cmd.startsWith("go depth") -> {
                        when (currentFen) {
                            queryFen -> {
                                out.println("info depth 8 multipv 1 score cp 30 pv e2e4")
                                out.println("bestmove e2e4")
                            }
                            cand1.second -> {
                                // Hang; do not emit bestmove until stop is issued
                            }
                            cand2.second -> {
                                out.println("info depth 8 multipv 1 score cp 20 pv d2d4")
                                out.println("bestmove d2d4")
                            }
                        }
                    }
                    cmd == "stop" -> {
                        out.println("bestmove d2d4")
                    }
                }
            }

        val service = StockfishService(processStarter = { process })
        val result =
            service.batchResolveContinuations(
                queryFen = queryFen,
                candidates = listOf(cand1, cand2),
                options = DynamicAnalysisOptions(depth = 8, watchdogTimeoutMs = 50L, drainTimeoutMs = 300L),
            )

        assertEquals(SearchStatus.COMPLETED, result.queryResult.status)
        val cand1Result = checkNotNull(result.candidateResults[cand1.first])
        assertEquals(SearchStatus.INTERRUPTED, cand1Result.status)
        assertTrue(cand1Result.principalVariations.isEmpty())

        val cand2Result = checkNotNull(result.candidateResults[cand2.first])
        assertEquals(SearchStatus.COMPLETED, cand2Result.status)
        assertEquals(listOf("d2d4"), cand2Result.principalVariations[0].moves)
    }

    @Test
    fun `4 drain timeout causing process termination and exactly one restart`() {
        val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val cand1 = UUID.randomUUID() to "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val cand2 = UUID.randomUUID() to "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3"

        val spawnCount = AtomicInteger(0)
        var queryDone = false
        val p1 =
            createFakeProcess { cmd, out ->
                when {
                    cmd == "uci" -> out.println("uciok")
                    cmd == "isready" -> out.println("readyok")
                    cmd.startsWith("go depth") -> {
                        if (!queryDone) {
                            queryDone = true
                            out.println("info depth 8 multipv 1 score cp 30 pv e2e4")
                            out.println("bestmove e2e4")
                        }
                    }
                }
            }

        val p2 =
            createFakeProcess { cmd, out ->
                when {
                    cmd == "uci" -> out.println("uciok")
                    cmd == "isready" -> out.println("readyok")
                    cmd.startsWith("go depth") -> {
                        out.println("info depth 8 multipv 1 score cp 15 pv c2c4")
                        out.println("bestmove c2c4")
                    }
                }
            }

        val controlledService =
            StockfishService(
                processStarter = {
                    if (spawnCount.getAndIncrement() == 0) p1 else p2
                },
            )

        val result =
            controlledService.batchResolveContinuations(
                queryFen = queryFen,
                candidates = listOf(cand1, cand2),
                options = DynamicAnalysisOptions(depth = 8, watchdogTimeoutMs = 50L, drainTimeoutMs = 50L),
            )

        assertEquals(SearchStatus.COMPLETED, result.queryResult.status)
        assertEquals(SearchStatus.FAILED, result.candidateResults.getValue(cand1.first).status)
        assertTrue(p1.isForciblyDestroyed.get(), "First process must be destroyed forcibly on drain timeout")
        assertEquals(2, spawnCount.get(), "Exactly one replacement process should be spawned")
        assertEquals(SearchStatus.COMPLETED, result.candidateResults.getValue(cand2.first).status)
    }

    @Test
    fun `5 successful replacement-process UCI handshake`() {
        val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val cand1 = UUID.randomUUID() to "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val cand2 = UUID.randomUUID() to "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3"

        val handshakeReceived = ConcurrentLinkedQueue<String>()
        val p1 =
            createFakeProcess { cmd, out ->
                if (cmd.startsWith("position fen ${cand1.second}")) {
                    triggerCrash()
                } else if (cmd == "uci") {
                    out.println("uciok")
                } else if (cmd == "isready") {
                    out.println("readyok")
                } else if (cmd.startsWith("go depth")) {
                    out.println("bestmove e2e4")
                }
            }

        val p2 =
            createFakeProcess { cmd, out ->
                handshakeReceived.add(cmd)
                when {
                    cmd == "uci" -> out.println("uciok")
                    cmd.startsWith("setoption name MultiPV") -> {}
                    cmd == "isready" -> out.println("readyok")
                    cmd.startsWith("go depth") -> out.println("bestmove d2d4")
                }
            }

        val spawns = AtomicInteger(0)
        val service =
            StockfishService(
                processStarter = {
                    if (spawns.getAndIncrement() == 0) p1 else p2
                },
            )

        val result =
            service.batchResolveContinuations(
                queryFen = queryFen,
                candidates = listOf(cand1, cand2),
                options = DynamicAnalysisOptions(depth = 8, multiPv = 2),
            )

        assertEquals(SearchStatus.FAILED, result.candidateResults.getValue(cand1.first).status)
        assertEquals(SearchStatus.COMPLETED, result.candidateResults.getValue(cand2.first).status)
        assertTrue(handshakeReceived.contains("uci"), "Replacement must receive 'uci'")
        assertTrue(handshakeReceived.any { it.startsWith("setoption name MultiPV") }, "Replacement must set MultiPV")
        assertTrue(handshakeReceived.contains("isready"), "Replacement must receive 'isready'")
    }

    @Test
    fun `6 replacement-process startup failure aborts batch`() {
        val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val cand1 = UUID.randomUUID() to "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val cand2 = UUID.randomUUID() to "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3"

        val p1 =
            createFakeProcess { cmd, out ->
                if (cmd.startsWith("position fen ${cand1.second}")) {
                    triggerCrash()
                } else if (cmd == "uci") {
                    out.println("uciok")
                } else if (cmd == "isready") {
                    out.println("readyok")
                } else if (cmd.startsWith("go depth")) {
                    out.println("bestmove e2e4")
                }
            }

        val spawns = AtomicInteger(0)
        val service =
            StockfishService(
                processStarter = {
                    if (spawns.getAndIncrement() == 0) {
                        p1
                    } else {
                        throw java.io.IOException("Failed to spawn replacement stockfish")
                    }
                },
            )

        val result =
            service.batchResolveContinuations(
                queryFen = queryFen,
                candidates = listOf(cand1, cand2),
                options = DynamicAnalysisOptions(depth = 8),
            )

        assertNotNull(result.fallbackReason)
        assertEquals("ENGINE_UNRESPONSIVE", result.fallbackReason)
        assertEquals(SearchStatus.FAILED, result.candidateResults.getValue(cand1.first).status)
        assertFalse(result.candidateResults.containsKey(cand2.first), "Subsequent candidates skipped on abort")
    }

    @Test
    fun `7 query failure aborts the batch immediately`() {
        val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val candId = UUID.randomUUID()

        val p =
            createFakeProcess { cmd, out ->
                when {
                    cmd == "uci" -> out.println("uciok")
                    cmd == "isready" -> out.println("readyok")
                    cmd.startsWith("go depth") -> {
                        // Hang on query search!
                    }
                    cmd == "stop" -> {
                        // Drain timeout
                    }
                }
            }

        val service = StockfishService(processStarter = { p })
        val result =
            service.batchResolveContinuations(
                queryFen = queryFen,
                candidates = listOf(candId to "some-fen"),
                options = DynamicAnalysisOptions(watchdogTimeoutMs = 50L, drainTimeoutMs = 50L),
            )

        assertNotNull(result.fallbackReason)
        assertEquals(SearchStatus.FAILED, result.queryResult.status)
        assertTrue(result.candidateResults.isEmpty(), "No candidate evaluated when query fails")
    }

    @Test
    fun `8 candidate timeout remaining isolated when the process is healthy`() {
        val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val cand1 = UUID.randomUUID() to "fen1"
        val cand2 = UUID.randomUUID() to "fen2"

        var currentFen = ""
        val p =
            createFakeProcess { cmd, out ->
                when {
                    cmd == "uci" -> out.println("uciok")
                    cmd == "isready" -> out.println("readyok")
                    cmd.startsWith("position fen") -> currentFen = cmd.removePrefix("position fen ")
                    cmd.startsWith("go depth") -> {
                        if (currentFen == "fen1") {
                            // cand1 times out, but drains cleanly on stop
                        } else {
                            out.println("info depth 8 multipv 1 score cp 10 pv e2e4")
                            out.println("bestmove e2e4")
                        }
                    }
                    cmd == "stop" -> {
                        out.println("bestmove (none)")
                    }
                }
            }

        val service = StockfishService(processStarter = { p })
        val result =
            service.batchResolveContinuations(
                queryFen = queryFen,
                candidates = listOf(cand1, cand2),
                options = DynamicAnalysisOptions(watchdogTimeoutMs = 50L, drainTimeoutMs = 200L),
            )

        assertEquals(SearchStatus.COMPLETED, result.queryResult.status)
        assertEquals(SearchStatus.INTERRUPTED, result.candidateResults.getValue(cand1.first).status)
        assertEquals(SearchStatus.COMPLETED, result.candidateResults.getValue(cand2.first).status)
        assertEquals(2, result.engineAttemptedCount)
        assertEquals(1, result.engineCompletedCount)
    }

    @Test
    fun `9 process cleanup on every exit path`() {
        val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val p =
            createFakeProcess { cmd, out ->
                when {
                    cmd == "uci" -> out.println("uciok")
                    cmd == "isready" -> out.println("readyok")
                    cmd.startsWith("go depth") -> out.println("bestmove e2e4")
                }
            }

        val service = StockfishService(processStarter = { p })
        service.batchResolveContinuations(queryFen, emptyList())

        assertTrue(p.isDestroyed.get(), "Subprocess must be destroyed upon batch exit")
    }

    @Test
    fun `10 restart limit aborts when replacement process fails on second candidate`() {
        val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val candA = UUID.randomUUID() to "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val candB = UUID.randomUUID() to "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3"
        val candC = UUID.randomUUID() to "r1bqkbnr/pppp1ppp/2n5/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 2 3"

        val spawnCount = AtomicInteger(0)
        val p1 =
            createFakeProcess { cmd, out ->
                when {
                    cmd == "uci" -> out.println("uciok")
                    cmd == "isready" -> out.println("readyok")
                    cmd.startsWith("go depth") -> out.println("bestmove e2e4")
                    cmd.startsWith("position fen ${candA.second}") -> triggerCrash()
                }
            }

        val p2 =
            createFakeProcess { cmd, out ->
                when {
                    cmd == "uci" -> out.println("uciok")
                    cmd == "isready" -> out.println("readyok")
                    cmd.startsWith("position fen ${candB.second}") -> triggerCrash()
                    cmd.startsWith("go depth") -> out.println("bestmove d2d4")
                }
            }

        val service =
            StockfishService(
                processStarter = {
                    if (spawnCount.getAndIncrement() == 0) p1 else p2
                },
            )

        val result =
            service.batchResolveContinuations(
                queryFen = queryFen,
                candidates = listOf(candA, candB, candC),
                options = DynamicAnalysisOptions(depth = 8),
            )

        assertNotNull(result.fallbackReason)
        assertEquals("ENGINE_UNRESPONSIVE", result.fallbackReason)
        assertEquals(SearchStatus.FAILED, result.candidateResults[candA.first]?.status)
        assertEquals(SearchStatus.FAILED, result.candidateResults[candB.first]?.status)
        assertFalse(result.candidateResults.containsKey(candC.first), "Candidate C must not be attempted")
        assertEquals(2, spawnCount.get(), "Must spawn exactly 2 processes (initial + 1 replacement)")
        assertTrue(p1.isDestroyed.get(), "Initial process p1 must be destroyed")
        assertTrue(p2.isDestroyed.get(), "Replacement process p2 must be destroyed on cleanup")
    }

    @Test
    fun `batchResolveContinuations skips query position search when knownQueryResult is provided`() {
        val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val candidateFen = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
        val candA = UUID.randomUUID() to candidateFen
        val searches = mutableListOf<String>()
        val proc =
            createFakeProcess { cmd, out ->
                if (cmd == "uci") {
                    out.println("id name FakeStockfish")
                    out.println("uciok")
                } else if (cmd == "isready") {
                    out.println("readyok")
                } else if (cmd.startsWith("position fen ")) {
                    searches.add(cmd.removePrefix("position fen "))
                } else if (cmd.startsWith("go ")) {
                    out.println("info depth 16 multipv 1 score cp 20 pv g1f3")
                    out.println("bestmove g1f3")
                }
            }
        val service = StockfishService(processStarter = { proc })
        val knownQuery = ContinuationResult(SearchStatus.COMPLETED, 16, listOf(PrincipalVariation(1, listOf("e2e4"), 16)))

        val result =
            service.batchResolveContinuations(
                queryFen = queryFen,
                candidates = listOf(candA),
                options = DynamicAnalysisOptions(depth = 16),
                knownQueryResult = knownQuery,
            )

        assertNull(result.fallbackReason)
        assertEquals(knownQuery, result.queryResult)
        assertEquals(1, searches.size)
        assertEquals(candidateFen, searches[0])
        assertFalse(searches.contains(queryFen), "Query FEN must not be searched by the engine when knownQueryResult is provided")
    }

    private fun createFakeProcess(handler: FakeProcess.(String, PrintWriter) -> Unit): FakeProcess {
        val inPipe = PipedInputStream()
        val outPipe = PipedOutputStream(inPipe)
        val procOut = PrintWriter(outPipe, true)

        val toProcPipeIn = PipedInputStream()
        val toProcPipeOut = PipedOutputStream(toProcPipeIn)

        val proc = FakeProcess(inPipe, toProcPipeOut, handler)

        Thread {
            val reader = BufferedReader(InputStreamReader(toProcPipeIn))
            try {
                reader.forEachLine { rawLine ->
                    val cmd = rawLine.trim()
                    if (cmd == "quit") {
                        proc.destroy()
                        return@forEachLine
                    }
                    if (cmd.isNotEmpty()) {
                        with(proc) { commandHandler(cmd, procOut) }
                    }
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }

        return proc
    }

    private class FakeProcess(
        private val inStream: InputStream,
        private val outStream: OutputStream,
        var commandHandler: FakeProcess.(String, PrintWriter) -> Unit,
    ) : Process() {
        val isDestroyed = AtomicBoolean(false)
        val isForciblyDestroyed = AtomicBoolean(false)

        override fun isAlive(): Boolean = !isDestroyed.get()

        override fun getOutputStream(): OutputStream = outStream

        override fun getInputStream(): InputStream = inStream

        override fun getErrorStream(): InputStream = java.io.ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() {
            isDestroyed.set(true)
            try {
                inStream.close()
            } catch (_: Exception) {
            }
            try {
                outStream.close()
            } catch (_: Exception) {
            }
        }

        override fun destroyForcibly(): Process {
            isForciblyDestroyed.set(true)
            destroy()
            return this
        }

        fun triggerCrash() {
            destroyForcibly()
        }
    }
}
