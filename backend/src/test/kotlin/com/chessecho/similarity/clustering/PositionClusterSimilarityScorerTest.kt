package com.chessecho.similarity.clustering

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class PositionClusterSimilarityScorerTest {
    private val scorer = PositionClusterSimilarityScorer()

    @Test
    fun `self similarity is exactly 1 for normal position with pawns and pieces`() {
        val terms =
            mapOf(
                "P(2)" to 1.0,
                "Ie4" to 1.0,
                "piece|white|knight|f3" to 1.0,
                "reach|white|queen|d1" to 0.8,
            )
        val vec =
            scorer.buildVector(
                positionId = UUID.randomUUID(),
                canonicalFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -",
                terms = terms,
                idfs = terms.keys.associateWith { 2.5 },
                alpha = 0.60,
            )

        assertEquals(1.0, scorer.cosineSimilarity(vec, vec), 1e-9)
    }

    @Test
    fun `self similarity is 1 for pawnless endgame when alpha is 0_60`() {
        val pieceTerms =
            mapOf(
                "piece|white|king|e1" to 1.0,
                "piece|black|king|e8" to 1.0,
                "piece|white|rook|a1" to 1.0,
            )
        val vec =
            scorer.buildVector(
                positionId = UUID.randomUUID(),
                canonicalFen = "4k3/8/8/8/8/8/8/R3K3 w - -",
                terms = pieceTerms,
                idfs = pieceTerms.keys.associateWith { 2.0 },
                alpha = 0.60,
            )

        assertEquals(1.0, scorer.cosineSimilarity(vec, vec), 1e-9)
    }

    @Test
    fun `pawnless position evaluates to 0 similarity against everything at alpha 1_0`() {
        val pieceTerms =
            mapOf(
                "piece|white|king|e1" to 1.0,
                "piece|black|king|e8" to 1.0,
            )
        val pawnless =
            scorer.buildVector(
                positionId = UUID.randomUUID(),
                canonicalFen = "4k3/8/8/8/8/8/8/4K3 w - -",
                terms = pieceTerms,
                idfs = pieceTerms.keys.associateWith { 2.0 },
                alpha = 1.0,
            )
        val normal =
            scorer.buildVector(
                positionId = UUID.randomUUID(),
                canonicalFen = "4k3/4p3/8/8/8/8/4P3/4K3 w - -",
                terms = mapOf("Ie2" to 1.0, "piece|white|king|e1" to 1.0),
                idfs = mapOf("Ie2" to 2.0, "piece|white|king|e1" to 2.0),
                alpha = 1.0,
            )

        assertEquals(0.0, scorer.cosineSimilarity(pawnless, normal))
        assertEquals(0.0, scorer.cosineSimilarity(pawnless, pawnless))
    }

    @Test
    fun `piece-only alpha 0 evaluates pawn-only positions to 0 similarity`() {
        val pawnOnly =
            scorer.buildVector(
                positionId = UUID.randomUUID(),
                canonicalFen = "8/4p3/8/8/8/8/4P3/8 w - -",
                terms = mapOf("Ie2" to 1.0, "Ie7" to 1.0),
                idfs = mapOf("Ie2" to 2.0, "Ie7" to 2.0),
                alpha = 0.0,
            )

        assertEquals(0.0, scorer.cosineSimilarity(pawnOnly, pawnOnly))
    }

    @Test
    fun `alpha weighting linearly balances pawn and piece subvectors`() {
        val posA =
            scorer.buildVector(
                positionId = UUID.randomUUID(),
                canonicalFen = "fenA",
                terms = mapOf("Ie4" to 1.0, "piece|white|knight|f3" to 1.0),
                idfs = mapOf("Ie4" to 2.0, "piece|white|knight|f3" to 2.0),
                alpha = 0.50,
            )
        // posB shares only the pawn feature
        val posBPawnMatch =
            scorer.buildVector(
                positionId = UUID.randomUUID(),
                canonicalFen = "fenB",
                terms = mapOf("Ie4" to 1.0, "piece|white|bishop|c4" to 1.0),
                idfs = mapOf("Ie4" to 2.0, "piece|white|bishop|c4" to 2.0),
                alpha = 0.50,
            )
        // posC shares only the piece feature
        val posCPieceMatch =
            scorer.buildVector(
                positionId = UUID.randomUUID(),
                canonicalFen = "fenC",
                terms = mapOf("Id4" to 1.0, "piece|white|knight|f3" to 1.0),
                idfs = mapOf("Id4" to 2.0, "piece|white|knight|f3" to 2.0),
                alpha = 0.50,
            )

        val simPawn = scorer.cosineSimilarity(posA, posBPawnMatch)
        val simPiece = scorer.cosineSimilarity(posA, posCPieceMatch)

        // At alpha=0.50 with single identical unit features, both should be 0.50
        assertEquals(0.50, simPawn, 1e-9)
        assertEquals(0.50, simPiece, 1e-9)

        // Now test with alpha=0.80
        val posA80 = scorer.buildVector(posA.positionId, "fenA", posA.rawTerms, mapOf("Ie4" to 2.0, "piece|white|knight|f3" to 2.0), 0.80)
        val posB80 =
            scorer.buildVector(
                posBPawnMatch.positionId,
                "fenB",
                posBPawnMatch.rawTerms,
                mapOf(
                    "Ie4" to 2.0,
                    "piece|white|bishop|c4" to 2.0,
                ),
                0.80,
            )
        val posC80 =
            scorer.buildVector(
                posCPieceMatch.positionId,
                "fenC",
                posCPieceMatch.rawTerms,
                mapOf(
                    "Id4" to 2.0,
                    "piece|white|knight|f3" to 2.0,
                ),
                0.80,
            )

        assertEquals(0.80, scorer.cosineSimilarity(posA80, posB80), 1e-9)
        assertEquals(0.20, scorer.cosineSimilarity(posA80, posC80), 1e-9)
    }

    @Test
    fun `cross-stage comparison between middlegame and pawnless endgame scales with sqrt(1 - alpha)`() {
        val middlegame =
            scorer.buildVector(
                positionId = UUID.randomUUID(),
                canonicalFen = "mg",
                terms = mapOf("Ie4" to 1.0, "piece|white|king|e1" to 1.0),
                idfs = mapOf("Ie4" to 2.0, "piece|white|king|e1" to 2.0),
                alpha = 0.60,
            )
        val pawnlessEndgame =
            scorer.buildVector(
                positionId = UUID.randomUUID(),
                canonicalFen = "eg",
                terms = mapOf("piece|white|king|e1" to 1.0),
                idfs = mapOf("piece|white|king|e1" to 2.0),
                alpha = 0.60,
            )

        val sim = scorer.cosineSimilarity(middlegame, pawnlessEndgame)
        // Piece overlap is 1.0. Middlegame norm = 1.0, Endgame norm = sqrt(0.40).
        // Inner product = sqrt(0.40)*1.0 * sqrt(0.40)*1.0 = 0.40.
        // Cosine = 0.40 / (1.0 * sqrt(0.40)) = sqrt(0.40) ≈ 0.6324555
        val expected = Math.sqrt(0.40)
        assertEquals(expected, sim, 1e-9)
    }

    @Test
    fun `empty vs empty returns 0`() {
        val empty1 = scorer.buildVector(UUID.randomUUID(), "fen1", emptyMap<String, Double>(), emptyMap<String, Double>(), 0.60)
        val empty2 = scorer.buildVector(UUID.randomUUID(), "fen2", emptyMap<String, Double>(), emptyMap<String, Double>(), 0.60)
        assertEquals(0.0, scorer.cosineSimilarity(empty1, empty2))
    }
}
