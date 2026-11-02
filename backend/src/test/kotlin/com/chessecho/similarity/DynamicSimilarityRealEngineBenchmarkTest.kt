package com.chessecho.similarity

import com.chessecho.service.DynamicAnalysisOptions
import com.chessecho.service.SearchStatus
import com.chessecho.service.StockfishService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

class DynamicSimilarityRealEngineBenchmarkTest {
    private val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    @Test
    fun `empirical real-Stockfish performance benchmark on 100 candidates`() {
        assumeTrue(isStockfishAvailable(), "Stockfish binary is not available on host environment; SKIPPED")

        val candidates = generateBenchmarkCandidates(100)
        val service = StockfishService()
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
            suiteName = "Empirical Real-Stockfish Performance Benchmark",
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
        assertTrue(result.engineCompletedCount > 0)
    }

    private fun isStockfishAvailable(): Boolean =
        try {
            val proc = ProcessBuilder("stockfish").start()
            proc.outputStream.bufferedWriter().use {
                it.write("quit\n")
                it.flush()
            }
            proc.waitFor(2, TimeUnit.SECONDS)
            true
        } catch (_: Exception) {
            false
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
}
