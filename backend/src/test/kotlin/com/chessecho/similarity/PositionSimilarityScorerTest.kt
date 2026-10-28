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
    private val c5Fen = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
    private val e4Fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
    private val d4Fen = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq - 0 1"
    private val endgameFen = "8/5pk1/4p1p1/7p/4P3/5PP1/5K1P/8 w - - 0 40"

    @Test
    fun `score strictly excludes exact query canonical FEN from results`() {
        val queryDoc = extractor.extractDocument(UUID.randomUUID(), queryFen, includeReachability = false)
        val identicalDoc = extractor.extractDocument(UUID.randomUUID(), identicalFenWithDiffClocks, includeReachability = true)
        val diffDoc = extractor.extractDocument(UUID.randomUUID(), c5Fen, includeReachability = true)

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
        val similarDoc = extractor.extractDocument(UUID.randomUUID(), c5Fen, includeReachability = true)
        val endgameDoc = extractor.extractDocument(UUID.randomUUID(), endgameFen, includeReachability = true)

        val results = scorer.score(queryDoc, listOf(similarDoc, endgameDoc))

        assertEquals(2, results.size)
        assertEquals(similarDoc.positionId, results[0].document.positionId)
        assertTrue(results[0].score > results[1].score, "Similar opening position must score higher than unrelated endgame")
    }

    @Test
    fun `score excludes candidates with opposite side to move even with identical board pieces`() {
        val queryDoc = extractor.extractDocument(UUID.randomUUID(), queryFen, includeReachability = false)
        // Candidate with opposite side-to-move
        val oppositeTurnDoc = extractor.extractDocument(UUID.randomUUID(), e4Fen, includeReachability = true)
        val validDoc = extractor.extractDocument(UUID.randomUUID(), c5Fen, includeReachability = true)

        val results = scorer.score(queryDoc, listOf(oppositeTurnDoc, validDoc))

        assertEquals(1, results.size)
        assertEquals(validDoc.positionId, results[0].document.positionId)
    }

    @Test
    fun `score rejects opposite-turn board clone bypassing canonicalFen equality`() {
        val queryDoc = extractor.extractDocument(UUID.randomUUID(), queryFen, includeReachability = false)
        // Exact board clone with turn flipped to 'b'
        val flippedTurnFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1"
        val flippedDoc = extractor.extractDocument(UUID.randomUUID(), flippedTurnFen, includeReachability = true)

        val results = scorer.score(queryDoc, listOf(flippedDoc))

        assertTrue(results.isEmpty(), "Board clone with opposite turn must be rejected as candidate")
    }

    @Test
    fun `score preserves identical raw BM25 scores for eligible candidates on same-turn corpus`() {
        val queryDoc = extractor.extractDocument(UUID.randomUUID(), queryFen, includeReachability = false)
        val candidateDoc = extractor.extractDocument(UUID.randomUUID(), c5Fen, includeReachability = true)

        val results = scorer.score(queryDoc, listOf(candidateDoc))

        assertEquals(1, results.size)
        // Verify BM25 score is positive, finite, and strictly matches pre-change baseline from base commit 870fa4d
        val score = results[0].score
        assertTrue(score > 0.0, "Score should be positive")
        assertTrue(!score.isNaN() && !score.isInfinite(), "Score must be finite")

        // Expected baseline BM25 score derived directly from base commit 870fa4d on this deterministic fixture
        val expectedBaselineScore = 25.891386520660266
        assertEquals(expectedBaselineScore, score, 1e-9, "BM25 score must match pre-change baseline within 1e-9")

        // Re-scoring must produce identical bitwise score
        val repeatResults = scorer.score(queryDoc, listOf(candidateDoc))
        assertEquals(score, repeatResults[0].score, 1e-9, "BM25 score must be strictly preserved")
    }

    @Test
    fun `score preserves relative ranking and canonicalFen tie-breaking on eligible candidates`() {
        val queryDoc = extractor.extractDocument(UUID.randomUUID(), queryFen, includeReachability = false)
        val cand1 = extractor.extractDocument(UUID.randomUUID(), c5Fen, includeReachability = true)
        val cand2 = extractor.extractDocument(UUID.randomUUID(), endgameFen, includeReachability = true)

        val results = scorer.score(queryDoc, listOf(cand2, cand1))

        assertEquals(2, results.size)
        assertEquals(cand1.positionId, results[0].document.positionId)
        assertEquals(1, results[0].rank)
        assertEquals(cand2.positionId, results[1].document.positionId)
        assertEquals(2, results[1].rank)
    }

    @Test
    fun `score returns empty list when candidate list is empty`() {
        val queryDoc = extractor.extractDocument(UUID.randomUUID(), queryFen, includeReachability = false)
        val results = scorer.score(queryDoc, emptyList())

        assertTrue(results.isEmpty())
    }
}
