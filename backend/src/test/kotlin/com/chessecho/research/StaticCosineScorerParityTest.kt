package com.chessecho.research

import com.chessecho.similarity.clustering.PositionClusterSimilarityScorer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.sqrt

class StaticCosineScorerParityTest {
    private val scorer = PositionClusterSimilarityScorer()
    private val testAlphas = listOf(0.00, 0.25, 0.50, 0.60, 0.75, 1.00)

    @Test
    fun `direct binding to PositionClusterSimilarityScorer yields exact self-similarity of 1_0`() {
        val qId = UUID.randomUUID()
        val terms = mapOf("p(e4)" to 1.0, "ISOLATED_PAWN" to 1.0, "n(f3)" to 1.0, "b(c4)" to 1.0)
        val idfs = mapOf("p(e4)" to 2.5, "ISOLATED_PAWN" to 1.8, "n(f3)" to 2.1, "b(c4)" to 2.3)

        for (alpha in testAlphas) {
            val vec = scorer.buildVector(qId, "test_fen", terms, idfs, alpha)
            val sim = scorer.cosineSimilarity(vec, vec)
            assertEquals(1.0, sim, 1e-12, "Self-similarity must be exactly 1.0 at alpha=$alpha")
        }
    }

    @Test
    fun `algebraic equivalence holds for standard middlegame positions with both subvectors`() {
        val qId = UUID.randomUUID()
        val cId = UUID.randomUUID()

        val qTerms = mapOf("p(e4)" to 1.0, "p(d4)" to 1.0, "n(f3)" to 1.0, "b(e2)" to 1.0)
        val cTerms = mapOf("p(e4)" to 1.0, "p(c4)" to 1.0, "n(f3)" to 1.0, "b(d3)" to 1.0)
        val idfs = mapOf(
            "p(e4)" to 2.0,
            "p(d4)" to 2.5,
            "p(c4)" to 2.2,
            "n(f3)" to 1.5,
            "b(e2)" to 3.0,
            "b(d3)" to 2.8,
        )

        // Manual subvector calculation
        // Pawn weights (p(e4), p(d4), p(c4) match isPawnToken)
        val qPawnW = mapOf("p(e4)" to 1.0 * 2.0, "p(d4)" to 1.0 * 2.5)
        val cPawnW = mapOf("p(e4)" to 1.0 * 2.0, "p(c4)" to 1.0 * 2.2)
        val qPawnNorm = sqrt(qPawnW.values.sumOf { it * it })
        val cPawnNorm = sqrt(cPawnW.values.sumOf { it * it })
        val pawnDot = (2.0 * 2.0) // shared p(e4)
        val pawnCos = pawnDot / (qPawnNorm * cPawnNorm)

        // Piece weights (n(f3), b(e2), b(d3) are piece tokens)
        val qPieceW = mapOf("n(f3)" to 1.0 * 1.5, "b(e2)" to 1.0 * 3.0)
        val cPieceW = mapOf("n(f3)" to 1.0 * 1.5, "b(d3)" to 1.0 * 2.8)
        val qPieceNorm = sqrt(qPieceW.values.sumOf { it * it })
        val cPieceNorm = sqrt(cPieceW.values.sumOf { it * it })
        val pieceDot = (1.5 * 1.5) // shared n(f3)
        val pieceCos = pieceDot / (qPieceNorm * cPieceNorm)

        for (alpha in testAlphas) {
            val vecQ = scorer.buildVector(qId, "fen_q", qTerms, idfs, alpha)
            val vecC = scorer.buildVector(cId, "fen_c", cTerms, idfs, alpha)
            val actualSim = scorer.cosineSimilarity(vecQ, vecC)

            // When both subvectors exist and alpha in (0, 1), sim = alpha * pawnCos + (1-alpha) * pieceCos
            val expectedSim = when (alpha) {
                0.0 -> pieceCos
                1.0 -> pawnCos
                else -> alpha * pawnCos + (1.0 - alpha) * pieceCos
            }

            assertEquals(expectedSim, actualSim, 1e-12, "Algebraic parity failed at alpha=$alpha")
        }
    }

    @Test
    fun `pawnless endgames yield zero pawn energy and scale piece similarity properly`() {
        val qId = UUID.randomUUID()
        val cId = UUID.randomUUID()

        // Only piece tokens (no pawns)
        val qTerms = mapOf("k(e1)" to 1.0, "r(a1)" to 1.0)
        val cTerms = mapOf("k(e1)" to 1.0, "r(c1)" to 1.0)
        val idfs = mapOf("k(e1)" to 1.0, "r(a1)" to 3.0, "r(c1)" to 3.0)

        for (alpha in testAlphas) {
            val vecQ = scorer.buildVector(qId, "fen_q", qTerms, idfs, alpha)
            val vecC = scorer.buildVector(cId, "fen_c", cTerms, idfs, alpha)
            val sim = scorer.cosineSimilarity(vecQ, vecC)

            if (alpha == 1.0) {
                // At alpha=1.0, piece terms have weight 0, pawn terms are empty -> norm=0 -> sim=0.0
                assertEquals(0.0, sim, 1e-12, "At alpha=1.0 with no pawns, similarity must be 0.0")
            } else {
                // Pure piece cosine
                val expectedPieceCos = (1.0 * 1.0) / (sqrt(1.0 + 9.0) * sqrt(1.0 + 9.0)) // 1 / 10 = 0.10
                assertEquals(expectedPieceCos, sim, 1e-12, "Similarity must equal pure piece cosine at alpha=$alpha")
            }
        }
    }

    @Test
    fun `pure pawn endgames yield zero piece energy and scale pawn similarity properly`() {
        val qId = UUID.randomUUID()
        val cId = UUID.randomUUID()

        // Only pawn tokens (no pieces)
        val qTerms = mapOf("p(e4)" to 1.0, "p(d4)" to 1.0)
        val cTerms = mapOf("p(e4)" to 1.0, "p(f4)" to 1.0)
        val idfs = mapOf("p(e4)" to 2.0, "p(d4)" to 2.0, "p(f4)" to 2.0)

        for (alpha in testAlphas) {
            val vecQ = scorer.buildVector(qId, "fen_q", qTerms, idfs, alpha)
            val vecC = scorer.buildVector(cId, "fen_c", cTerms, idfs, alpha)
            val sim = scorer.cosineSimilarity(vecQ, vecC)

            if (alpha == 0.0) {
                // At alpha=0.0, pawn terms have weight 0, piece terms empty -> norm=0 -> sim=0.0
                assertEquals(0.0, sim, 1e-12, "At alpha=0.0 with pure pawns, similarity must be 0.0")
            } else {
                // Pure pawn cosine: (2.0 * 2.0) / (sqrt(4+4) * sqrt(4+4)) = 4 / 8 = 0.50
                val expectedPawnCos = 0.50
                assertEquals(expectedPawnCos, sim, 1e-12, "Similarity must equal pure pawn cosine at alpha=$alpha")
            }
        }
    }
}
