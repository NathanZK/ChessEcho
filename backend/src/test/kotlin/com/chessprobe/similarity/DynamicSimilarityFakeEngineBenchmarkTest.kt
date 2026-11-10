package com.chessprobe.similarity

import com.chessprobe.service.DynamicAnalysisOptions
import com.chessprobe.service.SearchStatus
import com.chessprobe.service.StockfishService
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.util.concurrent.atomic.AtomicBoolean

class DynamicSimilarityFakeEngineBenchmarkTest {
    private val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    @Test
    fun `deterministic fake-engine integration benchmark on 100 candidates`() {
        val candidates = generateBenchmarkCandidates(100)
        val proc = createFakeBenchmarkProcess()
        val service = StockfishService(processStarter = { proc })
        val depth = 8
        val multiPv = 1

        val startTotalNs = System.nanoTime()
        val result =
            service.batchResolveContinuations(
                queryFen = queryFen,
                candidates = candidates,
                options = DynamicAnalysisOptions(depth = depth, multiPv = multiPv),
            )
        val totalElapsedMs = (System.nanoTime() - startTotalNs) / 1_000_000.0

        printReport(
            suiteName = "Deterministic Fake-Engine Integration Benchmark",
            totalElapsedMs = totalElapsedMs,
            engineAttempted = result.engineAttemptedCount,
            engineCompleted = result.engineCompletedCount,
            depth = depth,
            multiPv = multiPv,
        )

        assertNull(result.fallbackReason)
        assertEquals(SearchStatus.COMPLETED, result.queryResult.status)
        assertEquals(100, result.shortlistedCount)
        assertEquals(100, result.engineAttemptedCount)
        assertEquals(100, result.engineCompletedCount)
        assertEquals(100, result.candidateResults.size)
        assertTrue(result.candidateResults.values.all { it.status == SearchStatus.COMPLETED })
    }

    private fun generateBenchmarkCandidates(count: Int): List<Pair<UUID, String>> =
        (0 until count).map { i ->
            val file1 = i % 8
            val file2 = (i / 8) % 8
            val r6 = formatFenRank(file1, 'n')
            val r3 = formatFenRank(file2, 'N')
            val fen =
                if (i < 64) {
                    "4k3/pppppppp/$r6/8/8/$r3/PPPPPPPP/4K3 w - - 0 1"
                } else {
                    val r5 = formatFenRank(file1, 'b')
                    "4k3/pppppppp/8/$r5/8/$r3/PPPPPPPP/4K3 w - - 0 1"
                }
            UUID.randomUUID() to fen
        }

    private fun formatFenRank(
        file: Int,
        piece: Char,
    ): String =
        buildString {
            if (file > 0) append(file)
            append(piece)
            if (7 - file > 0) append(7 - file)
        }

    private fun printReport(
        suiteName: String,
        totalElapsedMs: Double,
        engineAttempted: Int,
        engineCompleted: Int,
        depth: Int,
        multiPv: Int,
    ) {
        println(
            """
            |=== $suiteName ===
            | Total Elapsed:        %.2f ms
            | Engine Attempted:     $engineAttempted
            | Engine Completed:     $engineCompleted
            | Configured Depth:     $depth
            | Configured MultiPV:   $multiPv
            |==================================================
            """.trimMargin().format(totalElapsedMs),
        )
    }

    private fun createFakeBenchmarkProcess(): Process {
        val inPipe = PipedInputStream()
        val outPipe = PipedOutputStream(inPipe)
        val procOut = PrintWriter(outPipe, true)
        val toProcIn = PipedInputStream()
        val toProcOut = PipedOutputStream(toProcIn)

        val proc =
            object : Process() {
                val isDestroyed = AtomicBoolean(false)

                override fun isAlive(): Boolean = !isDestroyed.get()

                override fun getOutputStream(): OutputStream = toProcOut

                override fun getInputStream(): InputStream = inPipe

                override fun getErrorStream(): InputStream = java.io.ByteArrayInputStream(ByteArray(0))

                override fun waitFor(): Int = 0

                override fun exitValue(): Int = 0

                override fun destroy() {
                    isDestroyed.set(true)
                }

                override fun destroyForcibly(): Process {
                    destroy()
                    return this
                }
            }

        Thread {
            try {
                BufferedReader(InputStreamReader(toProcIn)).forEachLine { line ->
                    val cmd = line.trim()
                    when {
                        cmd == "quit" -> proc.destroy()
                        cmd == "uci" -> procOut.println("uciok")
                        cmd == "isready" -> procOut.println("readyok")
                        cmd.startsWith("go depth") -> {
                            procOut.println("info depth 8 multipv 1 pv e2e4")
                            procOut.println("bestmove e2e4")
                        }
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
}
