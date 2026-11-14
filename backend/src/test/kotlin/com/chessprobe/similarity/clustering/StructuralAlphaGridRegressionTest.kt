package com.chessprobe.similarity.clustering

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class StructuralAlphaGridRegressionTest {
    private val scorer = PositionClusterSimilarityScorer()
    private val alphaGrid = listOf(0.0, 0.25, 0.50, 0.60, 0.75, 1.0)

    @Test
    fun `self similarity is exactly 1_0 across full alpha grid`() {
        val terms =
            mapOf(
                "P(2)" to 1.0,
                "Ie4" to 1.0,
                "piece|white|knight|f3" to 1.0,
                "reach|white|queen|d1" to 0.8,
            )
        val idfs = terms.keys.associateWith { 2.5 }
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"

        for (alpha in alphaGrid) {
            val vec =
                scorer.buildVector(
                    positionId = UUID.randomUUID(),
                    canonicalFen = fen,
                    terms = terms,
                    idfs = idfs,
                    alpha = alpha,
                )
            assertEquals(1.0, scorer.cosineSimilarity(vec, vec), 1e-9, "Failed for alpha=$alpha")
        }
    }

    @Test
    fun `similarity shifts monotonically with pawn weighting across full alpha grid`() {
        val sharedPawnTerms = mapOf("P(2)" to 1.0, "Ie4" to 1.0)
        val pieceTermsA = mapOf("piece|white|knight|f3" to 1.0)
        val pieceTermsB = mapOf("piece|white|bishop|c4" to 1.0)

        val termsA = sharedPawnTerms + pieceTermsA
        val termsB = sharedPawnTerms + pieceTermsB
        val allTerms = (termsA.keys + termsB.keys).distinct()
        val idfs = allTerms.associateWith { 2.0 }

        var previousSimilarity = -1.0
        for (alpha in alphaGrid) {
            val vA = scorer.buildVector(UUID.randomUUID(), "fenA", termsA, idfs, alpha = alpha)
            val vB = scorer.buildVector(UUID.randomUUID(), "fenB", termsB, idfs, alpha = alpha)
            val sim = scorer.cosineSimilarity(vA, vB)

            if (alpha == 0.0) {
                // Pure piece weighting: piece terms are completely disjoint, so similarity must be 0.0
                assertEquals(0.0, sim, 1e-9)
            } else if (alpha == 1.0) {
                // Pure pawn weighting: pawn terms are identical, so similarity must be 1.0
                assertEquals(1.0, sim, 1e-9)
            } else {
                assertTrue(sim > previousSimilarity, "Similarity should strictly increase as alpha increases for shared pawn structures")
            }
            previousSimilarity = sim
        }
    }
}
