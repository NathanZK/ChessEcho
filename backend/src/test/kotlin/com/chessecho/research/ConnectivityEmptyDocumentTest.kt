package com.chessecho.research

import com.chessecho.similarity.PositionDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ConnectivityEmptyDocumentTest {

    private val extractor = ConnectivityClosureExtractor()

    @Test
    fun `isolated positions with zero piece-to-piece relations produce empty maps safely`() {
        // King vs King: no piece-to-piece attacks or defenses
        val fen = "8/8/8/8/8/8/8/k6K w - - 0 1"
        val attackTerms = extractor.extractAttackTerms(fen)
        val defenseTerms = extractor.extractDefenseTerms(fen)
        val combinedTerms = extractor.extractCombinedTerms(fen)

        assertTrue(attackTerms.isEmpty(), "Bare king endgame has 0 attack terms")
        assertTrue(defenseTerms.isEmpty(), "Bare king endgame has 0 defense terms")
        assertTrue(combinedTerms.isEmpty(), "Bare king endgame has 0 combined terms")
    }

    @Test
    fun `scoring against empty document safely evaluates to zero without NaN or exceptions`() {
        val emptyDoc = PositionDocument(
            positionId = UUID.randomUUID(),
            fen = "8/8/8/8/8/8/8/k6K w - - 0 1",
            canonicalFen = "8/8/8/8/8/8/8/k6K w - -",
            terms = emptyMap()
        )
        val regularDoc = PositionDocument(
            positionId = UUID.randomUUID(),
            fen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
            canonicalFen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq -",
            terms = mapOf("attack|queen|pawn|f7" to 1.0)
        )

        val selector = ConnectivityCandidateSelector(
            corpus = listOf(emptyDoc, regularDoc),
            representationExtractor = { doc -> if (doc == emptyDoc) emptySet() else setOf("attack|queen|pawn|f7") }
        )

        // Query emptyDoc against regularDoc
        val score1 = selector.score(emptyDoc, regularDoc)
        assertEquals(0.0, score1, 1e-9, "Score with empty query must be exactly 0.0")
        assertFalse(score1.isNaN(), "Score must not be NaN")

        // Query regularDoc against emptyDoc
        val score2 = selector.score(regularDoc, emptyDoc)
        assertEquals(0.0, score2, 1e-9, "Score with empty candidate must be exactly 0.0")
        assertFalse(score2.isNaN(), "Score must not be NaN")

        // Empty document against empty document
        val score3 = selector.score(emptyDoc, emptyDoc)
        assertEquals(0.0, score3, 1e-9, "Score between empty docs must be 0.0")
        assertFalse(score3.isNaN(), "Score must not be NaN")
    }
}
