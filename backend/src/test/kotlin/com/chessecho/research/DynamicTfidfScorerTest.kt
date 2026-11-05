package com.chessecho.research

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class DynamicTfidfScorerTest {
    private val scorer = DynamicTfidfScorer()

    @Test
    fun `identical vectors produce cosine similarity 1_0`() {
        val terms = mapOf("!-N" to 2.0, "!xQ" to 1.0, "?+" to 1.0)
        val df = mapOf("!-N" to 10, "!xQ" to 5, "?+" to 20)
        val idf = scorer.computeIdf(100, df)
        val vec = scorer.encodeVector(terms, idf)
        val norm = scorer.vectorNorm(vec)

        val sim = scorer.cosineSimilarity(vec, norm, vec)
        assertEquals(1.0, sim, 1e-6)
    }

    @Test
    fun `orthogonal vectors produce cosine similarity 0_0`() {
        val terms1 = mapOf("!-N" to 1.0)
        val terms2 = mapOf("!-B" to 1.0)
        val df = mapOf("!-N" to 10, "!-B" to 10)
        val idf = scorer.computeIdf(100, df)
        val vec1 = scorer.encodeVector(terms1, idf)
        val vec2 = scorer.encodeVector(terms2, idf)

        val sim = scorer.cosineSimilarity(vec1, scorer.vectorNorm(vec1), vec2)
        assertEquals(0.0, sim, 1e-6)
    }

    @Test
    fun `scoreAndRank orders candidates descending by cosine score`() {
        val qTerms = mapOf("!-N" to 1.0, "!xQ" to 1.0)
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val id3 = UUID.randomUUID()

        // id1 has full match, id2 has partial match, id3 has no match
        val candTerms =
            mapOf(
                id1 to mapOf("!-N" to 1.0, "!xQ" to 1.0),
                id2 to mapOf("!-N" to 1.0),
                id3 to mapOf("!-R" to 1.0),
            )
        val candDocs =
            listOf(
                id1 to "fen1",
                id2 to "fen2",
                id3 to "fen3",
            )
        val df = mapOf("!-N" to 5, "!xQ" to 2, "!-R" to 10)
        val ranked = scorer.scoreAndRank(qTerms, candDocs, candTerms, df, 100)

        assertEquals(3, ranked.size)
        assertEquals(id1, ranked[0].positionId)
        assertEquals(1, ranked[0].rank)
        assertTrue(ranked[0].score > ranked[1].score)
        assertEquals(id2, ranked[1].positionId)
        assertEquals(2, ranked[1].rank)
        assertTrue(ranked[1].score > ranked[2].score)
        assertEquals(id3, ranked[2].positionId)
        assertEquals(3, ranked[2].rank)
        assertEquals(0.0, ranked[2].score, 1e-6)
    }
}
