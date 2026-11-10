package com.chessprobe.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

class DynamicInvertedIndexBenchmarkTest {
    companion object {
        private const val CORPUS_SIZE = 10_000
        private const val VOCABULARY_SIZE = 500
        private const val MEAN_TOKENS_PER_DOC = 31

        private val VOCABULARY = (1..VOCABULARY_SIZE).map { "dynamic_motif_token_$it" }
    }

    private fun generateCorpus(size: Int): List<PositionDocument> {
        val random = Random(42)
        val docs = ArrayList<PositionDocument>(size)

        for (i in 0 until size) {
            val positionId = UUID.randomUUID()
            val side = if (i % 2 == 0) "w" else "b"
            val fen = "8/8/8/8/8/8/8/${if (side == "w") "4K2k" else "4k2K"} $side - - 0 1"
            val canonicalFen = "pos_fen_$i $side - -"

            val tokenCount = (MEAN_TOKENS_PER_DOC + random.nextInt(-5, 6)).coerceAtLeast(1)
            val terms = mutableMapOf<String, Double>()
            for (t in 0 until tokenCount) {
                val token = VOCABULARY[random.nextInt(VOCABULARY.size)]
                terms[token] = (terms[token] ?: 0.0) + 1.0
            }

            docs.add(
                PositionDocument(
                    positionId = positionId,
                    fen = fen,
                    canonicalFen = canonicalFen,
                    terms = terms,
                    sideToMove = side,
                ),
            )
        }
        return docs
    }

    @Test
    fun `benchmark verifies cold-start index build and warm query retrieval latency bounds`() {
        val corpus = generateCorpus(CORPUS_SIZE)
        assertEquals(CORPUS_SIZE, corpus.size)

        // 1. Cold-start build benchmark
        val coldStartStartNs = System.nanoTime()
        val index = DynamicInvertedIndex(corpus)
        val coldStartMs = (System.nanoTime() - coldStartStartNs) / 1_000_000.0

        println("Cold-start index build duration over $CORPUS_SIZE positions: $coldStartMs ms")
        assertTrue(
            coldStartMs <= 650.0,
            "Cold-start index build must be <= 650 ms, but was $coldStartMs ms",
        )

        // 2. Warmup phase
        val random = Random(1234)
        val sampleQueries =
            (1..100).map {
                (1..15).map { VOCABULARY[random.nextInt(VOCABULARY.size)] }.toSet()
            }
        for (qTerms in sampleQueries) {
            index.retrieveCandidates(qTerms, activeTurn = "w", maxCandidates = 100)
        }

        // 3. Warm query retrieval benchmark
        val benchmarkQueries =
            (1..100).map {
                (1..15).map { VOCABULARY[random.nextInt(VOCABULARY.size)] }.toSet()
            }

        val latencies = mutableListOf<Double>()
        for (qTerms in benchmarkQueries) {
            val startNs = System.nanoTime()
            val candidates = index.retrieveCandidates(qTerms, activeTurn = "w", maxCandidates = 100)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            latencies.add(elapsedMs)
            assertTrue(candidates.isNotEmpty(), "Candidates should be retrieved for active terms")
            assertTrue(candidates.size <= 100, "Max candidates bound must hold")
        }

        val sortedLatencies = latencies.sorted()
        val p95 = sortedLatencies[(sortedLatencies.size * 0.95).toInt()]
        val meanLatency = latencies.average()

        println("Warm query retrieval latencies over $CORPUS_SIZE positions: Mean = $meanLatency ms, P95 = $p95 ms")
        val maxLatencyBound = if (System.getenv("CI") == "true") 10.0 else 5.0
        assertTrue(
            meanLatency <= maxLatencyBound,
            "Warm query retrieval mean latency must be <= $maxLatencyBound ms, but was $meanLatency ms",
        )
        assertTrue(
            p95 <= maxLatencyBound,
            "Warm query retrieval P95 latency must be <= $maxLatencyBound ms, but was $p95 ms",
        )
    }
}
