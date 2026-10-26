package com.chessecho.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PositionSimilarityScorerTest {
    private val extractor = RelationalPawnTokenExtractor()
    private val scorer = PositionSimilarityScorer()

    private val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val identicalFenWithDiffClocks = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 5 10"
    private val e4Fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
    private val d4Fen = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq - 0 1"
    private val endgameFen = "8/5pk1/4p1p1/7p/4P3/5PP1/5K1P/8 w - - 0 40"

    @Test
    fun `score strictly excludes exact query canonical FEN from results`() {
        val queryDoc = extractor.extractDocument(UUID.randomUUID(), queryFen, includeReachability = false)
        val identicalDoc = extractor.extractDocument(UUID.randomUUID(), identicalFenWithDiffClocks, includeReachability = true)
        val diffDoc = extractor.extractDocument(UUID.randomUUID(), e4Fen, includeReachability = true)

        val results = scorer.score(queryDoc, listOf(identicalDoc, diffDoc))

        assertEquals(1, results.size)
        assertEquals(diffDoc.positionId, results[0].document.positionId)
    }

    @Test
    fun `score breaks ties deterministically by canonicalFen ascending`() {
        val queryDoc =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "queryFen",
                canonicalFen = "queryCanonical",
                terms = mapOf("token1" to 1.0, "token2" to 1.0),
                documentLength = 2.0,
            )
        val candidateA =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "fenB",
                canonicalFen = "canonicalB",
                terms = mapOf("token1" to 1.0),
                documentLength = 1.0,
            )
        val candidateB =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "fenA",
                canonicalFen = "canonicalA",
                terms = mapOf("token1" to 1.0),
                documentLength = 1.0,
            )

        // Pass candidateB (canonicalB) first, candidateA (canonicalA) second
        val results = scorer.score(queryDoc, listOf(candidateA, candidateB))

        assertEquals(2, results.size)
        assertEquals(results[0].score, results[1].score, "BM25 scores must be strictly identical for identical terms")
        assertEquals("canonicalA", results[0].document.canonicalFen, "canonicalA must rank before canonicalB")
        assertEquals("canonicalB", results[1].document.canonicalFen)
        assertEquals(1, results[0].rank)
        assertEquals(2, results[1].rank)
    }

    @Test
    fun `score penalizes candidates with greater document length when matching terms are identical`() {
        val queryDoc =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "queryFen",
                canonicalFen = "queryCanonical",
                terms = mapOf("token1" to 1.0),
                documentLength = 1.0,
            )
        // Short doc: length = 1.0
        val shortDoc =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "shortFen",
                canonicalFen = "shortCanonical",
                terms = mapOf("token1" to 1.0),
                documentLength = 1.0,
            )
        // Long doc: matching token1 is 1.0, but extra reachability terms increase documentLength to 50.0
        val longDoc =
            PositionDocument(
                positionId = UUID.randomUUID(),
                fen = "longFen",
                canonicalFen = "longCanonical",
                terms = mapOf("token1" to 1.0, "reach1" to 25.0, "reach2" to 24.0),
                documentLength = 50.0,
            )

        val results = scorer.score(queryDoc, listOf(shortDoc, longDoc))

        assertEquals(2, results.size)
        assertEquals(
            shortDoc.positionId,
            results[0].document.positionId,
            "Shorter document with same matching term must achieve higher BM25 score",
        )
        assertTrue(results[0].score > results[1].score)
    }

    @Test
    fun `score assigns higher score to structurally similar positions than unrelated endgame`() {
        val queryDoc = extractor.extractDocument(UUID.randomUUID(), queryFen, includeReachability = false)
        val similarDoc = extractor.extractDocument(UUID.randomUUID(), e4Fen, includeReachability = true)
        val endgameDoc = extractor.extractDocument(UUID.randomUUID(), endgameFen, includeReachability = true)

        val results = scorer.score(queryDoc, listOf(similarDoc, endgameDoc))

        assertEquals(2, results.size)
        assertEquals(similarDoc.positionId, results[0].document.positionId)
        assertTrue(results[0].score > results[1].score, "Similar opening position must score higher than unrelated endgame")
    }

    @Test
    fun `score returns empty list when candidate list is empty`() {
        val queryDoc = extractor.extractDocument(UUID.randomUUID(), queryFen, includeReachability = false)
        val results = scorer.score(queryDoc, emptyList())

        assertTrue(results.isEmpty())
    }
}
