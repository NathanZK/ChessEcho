package com.chessprobe.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class DynamicInvertedIndexTest {
    @Test
    fun `indexing documents populates size and exact estimated heap footprint`() {
        val doc1 =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
                canonicalFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq -",
                terms = mapOf("e2e4" to 1.0, "+check" to 1.0, "zero_term" to 0.0),
            )
        val doc2 =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
                canonicalFen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq -",
                terms = mapOf("e7e5" to 1.0),
            )

        val index = DynamicInvertedIndex(listOf(doc1, doc2))

        assertEquals(2, index.size)
        // 2 docs * 630L + 3 postings (e2e4, +check, e7e5) * 32L + 1024L = 1260 + 96 + 1024 = 2380L
        val expectedHeap = 2 * 630L + 3 * 32L + 1024L
        assertEquals(expectedHeap, index.estimatedHeapFootprintBytes())
    }

    @Test
    fun `empty index returns empty candidates and zero postings visited`() {
        val index = DynamicInvertedIndex()
        assertEquals(0, index.size)

        val (candidates, metrics) =
            index.retrieveCandidatesWithMetrics(
                queryTerms = setOf("e2e4"),
                activeTurn = "w",
                maxCandidates = 10,
            )

        assertTrue(candidates.isEmpty())
        assertEquals(0L, metrics.postingsVisited)
        assertEquals(0, metrics.candidateCount)
    }

    @Test
    fun `query with empty queryTerms returns empty candidates and zero postings visited`() {
        val doc =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 1",
                canonicalFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq -",
                terms = mapOf("e2e4" to 1.0),
            )
        val index = DynamicInvertedIndex(listOf(doc))

        val (candidates, metrics) =
            index.retrieveCandidatesWithMetrics(
                queryTerms = emptySet(),
                activeTurn = "w",
                maxCandidates = 10,
            )

        assertTrue(candidates.isEmpty())
        assertEquals(0L, metrics.postingsVisited)
        assertEquals(0, metrics.candidateCount)
    }

    @Test
    fun `maxCandidates non-positive throws IllegalArgumentException`() {
        val index = DynamicInvertedIndex()

        assertThrows(IllegalArgumentException::class.java) {
            index.retrieveCandidates(setOf("e2e4"), "w", maxCandidates = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            index.retrieveCandidates(setOf("e2e4"), "w", maxCandidates = -5)
        }
    }

    @Test
    fun `strict active turn parity filters candidates with opposing turn and supports word variations`() {
        val whitePosId = UUID.randomUUID()
        val blackPosId = UUID.randomUUID()

        val whiteDoc =
            PositionDocument(
                positionId = whitePosId,
                fen = "8/8/8/8/8/8/8/4K2k w - - 0 1",
                canonicalFen = "8/8/8/8/8/8/8/4K2k w - -",
                terms = mapOf("kfork" to 1.0, "pin" to 1.0),
            )
        val blackDoc =
            PositionDocument(
                positionId = blackPosId,
                fen = "8/8/8/8/8/8/8/4K2k b - - 0 1",
                canonicalFen = "8/8/8/8/8/8/8/4K2k b - -",
                terms = mapOf("kfork" to 1.0, "pin" to 1.0),
            )

        val index = DynamicInvertedIndex(listOf(whiteDoc, blackDoc))

        // "w" and "white" (with whitespace / case)
        val whiteResults1 = index.retrieveCandidates(setOf("kfork"), activeTurn = "w")
        assertEquals(1, whiteResults1.size)
        assertEquals(whitePosId, whiteResults1[0].positionId)

        val whiteResults2 = index.retrieveCandidates(setOf("kfork"), activeTurn = " White ")
        assertEquals(1, whiteResults2.size)
        assertEquals(whitePosId, whiteResults2[0].positionId)

        // "b" and "black"
        val blackResults1 = index.retrieveCandidates(setOf("kfork"), activeTurn = "b")
        assertEquals(1, blackResults1.size)
        assertEquals(blackPosId, blackResults1[0].positionId)

        val blackResults2 = index.retrieveCandidates(setOf("kfork"), activeTurn = " BLACK ")
        assertEquals(1, blackResults2.size)
        assertEquals(blackPosId, blackResults2[0].positionId)

        // Invalid active turn -> empty list
        val invalidResults = index.retrieveCandidates(setOf("kfork"), activeTurn = "unknown")
        assertTrue(invalidResults.isEmpty())
    }

    @Test
    fun `scoring matches DynamicTacticalScorer bm25Score standard calculation`() {
        val doc1Id = UUID.randomUUID()
        val doc2Id = UUID.randomUUID()

        val doc1 =
            PositionDocument(
                positionId = doc1Id,
                fen = "8/8/8/8/8/8/8/1K5k w - - 0 1",
                canonicalFen = "8/8/8/8/8/8/8/1K5k w - -",
                terms = mapOf("tactical_1" to 2.0, "common_term" to 1.0),
            )
        val doc2 =
            PositionDocument(
                positionId = doc2Id,
                fen = "8/8/8/8/8/8/8/2K4k w - - 0 1",
                canonicalFen = "8/8/8/8/8/8/8/2K4k w - -",
                terms = mapOf("tactical_2" to 1.0, "common_term" to 1.0),
            )

        val index = DynamicInvertedIndex(listOf(doc1, doc2))
        val queryTerms = setOf("tactical_1", "common_term")

        val results = index.retrieveCandidates(queryTerms, activeTurn = "w")
        assertTrue(results.isNotEmpty())

        val docFreqs = mapOf("tactical_1" to 1, "tactical_2" to 1, "common_term" to 2)
        val expectedScoreDoc1 =
            DynamicTacticalScorer.bm25Score(
                queryTerms = queryTerms,
                documentTerms = doc1.terms,
                documentFrequencies = docFreqs,
                documentCount = 2,
                documentLength = 3.0,
                averageDocumentLength = 2.5,
            )

        val match1 = results.firstOrNull { it.positionId == doc1Id }
        assertNotNull(match1)
        assertEquals(expectedScoreDoc1, match1!!.score, 1e-6)
    }

    @Test
    fun `deterministic tie-breaking orders by score DESC then canonicalFen ASC then positionId ASC`() {
        val id1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val id2 = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val id3 = UUID.fromString("00000000-0000-0000-0000-000000000003")

        val docA =
            PositionDocument(
                positionId = id3,
                fen = "8/8/8/8/8/8/8/7K w - - 0 1",
                canonicalFen = "fen_b",
                terms = mapOf("token" to 1.0),
            )
        val docB =
            PositionDocument(
                positionId = id1,
                fen = "8/8/8/8/8/8/8/6K1 w - - 0 1",
                canonicalFen = "fen_a",
                terms = mapOf("token" to 1.0),
            )
        val docC =
            PositionDocument(
                positionId = id2,
                fen = "8/8/8/8/8/8/8/6K1 w - - 0 1",
                canonicalFen = "fen_a",
                terms = mapOf("token" to 1.0),
            )

        val index = DynamicInvertedIndex(listOf(docA, docB, docC))
        val results = index.retrieveCandidates(setOf("token"), activeTurn = "w")

        assertEquals(3, results.size)
        assertEquals(id1, results[0].positionId)
        assertEquals("fen_a", results[0].canonicalFen)
        assertEquals(id2, results[1].positionId)
        assertEquals("fen_a", results[1].canonicalFen)
        assertEquals(id3, results[2].positionId)
        assertEquals("fen_b", results[2].canonicalFen)
    }

    @Test
    fun `maxCandidates restricts returned candidates while metrics records total eligible candidate count`() {
        val doc1 =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "8/8/8/8/8/8/8/4K2k w - - 0 1",
                canonicalFen = "fen1",
                terms = mapOf("tok" to 2.0),
            )
        val doc2 =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "8/8/8/8/8/8/8/5K1k w - - 0 1",
                canonicalFen = "fen2",
                terms = mapOf("tok" to 1.0),
            )

        val index = DynamicInvertedIndex(listOf(doc1, doc2))
        val (candidates, metrics) =
            index.retrieveCandidatesWithMetrics(
                queryTerms = setOf("tok"),
                activeTurn = "w",
                maxCandidates = 1,
            )

        assertEquals(1, candidates.size)
        assertEquals(doc1.positionId, candidates[0].positionId)
        assertEquals(2, metrics.candidateCount)
        assertEquals(2L, metrics.postingsVisited)
        assertTrue(metrics.traversalLatencyMs >= 0.0)
    }

    @Test
    fun `traversal metrics record postings visited candidate count and non-negative latency`() {
        val doc1 =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "8/8/8/8/8/8/8/4K2k w - - 0 1",
                canonicalFen = "8/8/8/8/8/8/8/4K2k w - -",
                terms = mapOf("tok1" to 1.0, "tok2" to 1.0),
            )
        val doc2 =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "8/8/8/8/8/8/8/5K1k w - - 0 1",
                canonicalFen = "8/8/8/8/8/8/8/5K1k w - -",
                terms = mapOf("tok2" to 1.0),
            )

        val index = DynamicInvertedIndex(listOf(doc1, doc2))
        val (_, metrics) = index.retrieveCandidatesWithMetrics(setOf("tok1", "tok2"), activeTurn = "w")

        assertEquals(3L, metrics.postingsVisited)
        assertEquals(2, metrics.candidateCount)
        assertTrue(metrics.traversalLatencyMs >= 0.0)
    }
}
