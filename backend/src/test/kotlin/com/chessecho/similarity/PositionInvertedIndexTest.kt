package com.chessecho.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PositionInvertedIndexTest {
    private val extractor = RelationalPawnTokenExtractor()

    private val fen1 = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val fen2 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
    private val fen3 = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
    private val fen4 = "8/5pk1/4p1p1/7p/4P3/5PP1/5K1P/8 w - - 0 40"

    @Test
    fun `queryCandidates caps results to maxCandidates`() {
        val index = PositionInvertedIndex()
        val docs =
            (1..250).map { i ->
                val fen = if (i % 2 == 0) fen2 else fen3
                extractor.extractDocument(UUID.randomUUID(), fen, includeReachability = true)
            }
        index.index(docs)

        val queryDoc = extractor.extractDocument(UUID.randomUUID(), fen1, includeReachability = false)
        val candidates = index.queryCandidates(queryDoc.terms.keys, maxCandidates = 200)

        assertTrue(candidates.size <= 200, "Candidate count must be capped at 200, but was ${candidates.size}")
    }

    @Test
    fun `queryCandidates ranks positions by token overlap count`() {
        val index = PositionInvertedIndex()
        val docSimilar = extractor.extractDocument(UUID.randomUUID(), fen3, includeReachability = true)
        val docUnrelated = extractor.extractDocument(UUID.randomUUID(), fen4, includeReachability = true)

        index.index(listOf(docSimilar, docUnrelated))

        val queryDoc = extractor.extractDocument(UUID.randomUUID(), fen1, includeReachability = false)
        val candidates = index.queryCandidates(queryDoc.terms.keys, maxCandidates = 10)

        assertEquals(2, candidates.size)
        assertEquals(docSimilar.positionId, candidates[0].positionId, "More similar position should rank first in candidate generation")
    }

    @Test
    fun `queryCandidates returns empty list when index is empty or no terms match`() {
        val index = PositionInvertedIndex()
        val queryDoc = extractor.extractDocument(UUID.randomUUID(), fen1, includeReachability = false)
        val candidates = index.queryCandidates(queryDoc.terms.keys, maxCandidates = 200)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `generateCandidates filters candidates by active turn before truncation preventing pool starvation`() {
        val index = PositionInvertedIndex()
        // Index 200 Black-to-move documents with high term overlap
        val blackDocs =
            (1..200).map {
                extractor.extractDocument(
                    UUID.randomUUID(),
                    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
                    includeReachability = true,
                )
            }
        // Index 5 White-to-move documents with lower term overlap
        val whiteDocs =
            (1..5).map {
                extractor.extractDocument(
                    UUID.randomUUID(),
                    "8/5pk1/4p1p1/7p/4P3/5PP1/5K1P/8 w - - 0 40",
                    includeReachability = true,
                )
            }
        index.index(blackDocs + whiteDocs)

        // Query with terms overlapping both, active turn = "w"
        val queryTerms = setOf("piece|white|pawn|f3", "piece|white|king|f2", "piece|white|pawn|e4")
        val candidates = index.generateCandidates(queryTerms, activeTurn = "w", maxCandidates = 200)

        assertEquals(5, candidates.size, "Should return all 5 White candidates; Black candidates must not starve pool")
        assertTrue(candidates.all { it.sideToMove == "w" }, "All candidates must have sideToMove == 'w'")
    }

    @Test
    fun `generateCandidates overloads preserve backward compatibility and case insensitivity`() {
        val index = PositionInvertedIndex()
        val whiteDoc = extractor.extractDocument(UUID.randomUUID(), fen1, includeReachability = true)
        val blackDoc = extractor.extractDocument(UUID.randomUUID(), fen2, includeReachability = true)
        index.index(listOf(whiteDoc, blackDoc))

        val queryTerms = whiteDoc.terms.keys
        val defaultCandidates = index.generateCandidates(queryTerms)
        val explicitWhiteCandidates = index.generateCandidates(queryTerms, activeTurn = "w")
        val upperCaseWhiteCandidates = index.generateCandidates(queryTerms, activeTurn = "W")
        val blackCandidates = index.generateCandidates(queryTerms, activeTurn = "b")

        assertEquals(explicitWhiteCandidates.map { it.positionId }, defaultCandidates.map { it.positionId })
        assertEquals(explicitWhiteCandidates.map { it.positionId }, upperCaseWhiteCandidates.map { it.positionId })
        assertEquals(listOf(whiteDoc.positionId), explicitWhiteCandidates.map { it.positionId })
        assertEquals(listOf(blackDoc.positionId), blackCandidates.map { it.positionId })
    }
}
