package com.chessecho.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PositionTokenExtractorTest {
    private val extractor = RelationalPawnTokenExtractor()

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val openPositionFen = "r1bqk2r/pp2bppp/2n1pn2/2pp4/2PP4/2N1PN2/PP2BPPP/R1BQK2R w KQkq - 4 7"
    private val endgamePawnFen = "8/5pk1/4p1p1/7p/4P3/5PP1/5K1P/8 w - - 0 40"

    @Test
    fun `extractTerms extracts static piece placement tokens`() {
        val terms = extractor.extractTerms(startFen, includeReachability = false)

        assertTrue(terms.containsKey("piece|white|pawn|e2"))
        assertTrue(terms.containsKey("piece|white|knight|g1"))
        assertTrue(terms.containsKey("piece|black|king|e8"))
        assertEquals(1.0, terms["piece|white|pawn|e2"])
    }

    @Test
    fun `extractTerms omits reachability when includeReachability is false`() {
        val terms = extractor.extractTerms(startFen, includeReachability = false)

        val reachabilityKeys = terms.keys.filter { it.startsWith("reach|") }
        assertTrue(reachabilityKeys.isEmpty(), "Reachability terms must be omitted when includeReachability is false")
    }

    @Test
    fun `extractTerms includes reachability with fractional weights when includeReachability is true`() {
        val terms = extractor.extractTerms(startFen, includeReachability = true)

        val reachabilityKeys = terms.keys.filter { it.startsWith("reach|") }
        assertFalse(reachabilityKeys.isEmpty(), "Reachability terms must be present when includeReachability is true")

        // e.g. e2 pawn reaches e3 (distance 1: 1.0 - 7.0/64.0 = 57.0/64.0)
        val e3Reach = terms["reach|white|pawn|e3"]
        assertTrue(e3Reach != null && e3Reach > 0.0 && e3Reach < 1.0)
    }

    @Test
    fun `extractTerms extracts relational attack and defense tokens`() {
        val terms = extractor.extractTerms(openPositionFen, includeReachability = false)

        val attackTerms = terms.keys.filter { it.startsWith("attack|") }
        val defenseTerms = terms.keys.filter { it.startsWith("defense|") }

        assertFalse(attackTerms.isEmpty(), "Should extract attack relationships")
        assertFalse(defenseTerms.isEmpty(), "Should extract defense relationships")
    }

    @Test
    fun `extractTerms extracts static pawn structure tokens`() {
        val terms = extractor.extractTerms(endgamePawnFen, includeReachability = false)

        val islandTerms = terms.keys.filter { it.startsWith("P(") || it.startsWith("p(") }
        assertFalse(islandTerms.isEmpty(), "Should extract pawn island tokens")

        // In endgamePawnFen, pawns form chains, passed, isolated, or backward pawns
        val pawnFeaturePrefixes = listOf("I", "F", "'F", "l", "S", "w[")
        val extractedPawnFeatures = terms.keys.filter { key -> pawnFeaturePrefixes.any { key.startsWith(it) } }
        assertFalse(extractedPawnFeatures.isEmpty(), "Should extract Bizjak pawn structure features")
    }

    @Test
    fun `extractDocument computes canonical FEN and document length accurately`() {
        val positionId = UUID.randomUUID()
        val doc = extractor.extractDocument(positionId, startFen, includeReachability = true)

        assertEquals(positionId, doc.positionId)
        assertEquals(startFen, doc.fen)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -", doc.canonicalFen)
        assertEquals(doc.terms.values.sum(), doc.documentLength)
        assertTrue(doc.documentLength > 32.0, "Document length should sum all term frequencies")
    }
}
