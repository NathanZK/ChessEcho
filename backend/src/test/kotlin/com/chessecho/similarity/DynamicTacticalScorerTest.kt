package com.chessecho.similarity

import com.chessecho.service.ContinuationResult
import com.chessecho.service.PrincipalVariation
import com.chessecho.service.SearchStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class DynamicTacticalScorerTest {
    private val scorer = DynamicTacticalScorer()

    @Test
    fun `extractDynamicTokens starts immediately at ply 0 and extracts single-ply tokens`() {
        // Back-rank mate in 1: White plays 1. Rd8#
        val fen = "6k1/p4ppp/8/8/8/8/8/3R2K1 w - - 0 1"
        val moves = listOf("d1d8")

        val terms = scorer.extractDynamicTokens(fen, moves)

        assertTrue(terms.containsKey("!-R"), "Expected moved Rook token '!-R'")
        assertTrue(terms.containsKey("?#"), "Expected checkmate flag '?#'")
        assertTrue(terms.containsKey("?+"), "Expected check flag '?+'")
        assertTrue(terms.containsKey("!#R"), "Expected checkmate deliverer token '!#R'")
        assertFalse(terms.containsKey("!-RR"), "Single-ply should not emit consecutive moved token")
    }

    @Test
    fun `extractDynamicTokens extracts captures, created attacks, and general flags across sequence`() {
        // White to move: 1. Bxf7+ Kxf7 2. Qf3+
        val fen = "r1bqk2r/pppp1ppp/2n5/4p3/2B1n3/8/PPPP1PPP/RNBQK2R w KQkq - 0 1"
        val moves = listOf("c4f7", "e8f7", "d1f3")

        val terms = scorer.extractDynamicTokens(fen, moves)

        assertTrue(terms.containsKey("?px"), "Expected player capture flag '?px'")
        assertTrue(terms.containsKey("?ox"), "Expected opponent capture flag '?ox'")
        assertTrue(terms.containsKey("?+"), "Expected check flag '?+'")
        assertTrue(terms.containsKey("?S"), "Expected sacrifice flag '?S'")
        assertTrue(terms.containsKey("!SB"), "Expected Bishop sacrifice token '!SB'")
        assertTrue(terms.containsKey("!xP"), "Expected capture of pawn '!xP'")
    }

    @Test
    fun `extractDynamicTokens extracts consecutive moves and captures across multiple plies`() {
        // White plays 1. Nd5 Kd8 2. Nxe7
        val fen = "r3k2r/ppp1bppp/8/8/8/2N5/PPPP1PPP/R1BQK2R w KQkq - 0 1"
        val moves = listOf("c3d5", "e8d8", "d5e7")

        val terms = scorer.extractDynamicTokens(fen, moves)

        assertEquals(2.0, terms["!-N"], "Expected frequency 2.0 for '!-N'")
        assertTrue(terms.containsKey("!xB"), "Expected captured Bishop token '!xB'")
        assertTrue(terms.containsKey("!-NK"), "Expected consecutive moved pair '!-NK'")
        assertTrue(terms.containsKey("!-KN"), "Expected consecutive moved pair '!-KN'")
    }

    @Test
    fun `rank scores completed candidate using rank 1 PV only and ignores higher rank PVs`() {
        val queryFen = "r1bqk2r/pppp1ppp/2n5/4p3/2B1n3/8/PPPP1PPP/RNBQK2R w KQkq - 0 1"
        val queryMoves = listOf("c4f7", "e8f7", "d1f3")

        val candFen = "r1bqk2r/pppp1ppp/2n5/4p3/2B1n3/8/PPPP1PPP/RNBQK2R w KQkq - 0 1"
        val candId = UUID.randomUUID()
        val candidate =
            CandidateContinuation(
                positionId = candId,
                fen = candFen,
                canonicalFen = "r1bqk2r/pppp1ppp/2n5/4p3/2B1n3/8/PPPP1PPP/RNBQK2R w KQkq -",
                staticRank = 1,
                staticScore = 10.0,
                continuationResult =
                    ContinuationResult(
                        status = SearchStatus.COMPLETED,
                        achievedDepth = 8,
                        principalVariations =
                            listOf(
                                PrincipalVariation(rank = 1, moves = listOf("c4f7", "e8f7", "d1f3"), achievedDepth = 8),
                                PrincipalVariation(rank = 2, moves = listOf("d1h5", "g7g6"), achievedDepth = 8),
                            ),
                    ),
            )

        val ranked = scorer.rank(queryFen, queryMoves, listOf(candidate))
        assertEquals(1, ranked.size)
        assertTrue(ranked[0].dynamicScore > 0.0, "Completed candidate should have positive dynamic score")
        assertEquals(1, ranked[0].rank)
        assertEquals(1, ranked[0].staticRank)
        assertEquals(10.0, ranked[0].staticScore)
    }

    @Test
    fun `rank assigns 0 dynamic score to interrupted and failed candidates while preserving static rank order`() {
        val queryFen = "r1bqk2r/pppp1ppp/2n5/4p3/2B1n3/5Q2/PPPP1PPP/RNB1K2R w KQkq - 0 1"
        val queryMoves = listOf("f3f7")

        val cand1 =
            CandidateContinuation(
                positionId = UUID.randomUUID(),
                fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3",
                canonicalFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq -",
                staticRank = 1,
                staticScore = 15.0,
                continuationResult =
                    ContinuationResult(
                        status = SearchStatus.INTERRUPTED,
                        achievedDepth = 4,
                        principalVariations = emptyList(),
                    ),
            )

        val cand2 =
            CandidateContinuation(
                positionId = UUID.randomUUID(),
                fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
                canonicalFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq -",
                staticRank = 2,
                staticScore = 12.0,
                continuationResult =
                    ContinuationResult(
                        status = SearchStatus.FAILED,
                        achievedDepth = 0,
                        principalVariations = emptyList(),
                    ),
            )

        val ranked = scorer.rank(queryFen, queryMoves, listOf(cand2, cand1)) // passed out of order
        assertEquals(2, ranked.size)
        assertEquals(0.0, ranked[0].dynamicScore)
        assertEquals(0.0, ranked[1].dynamicScore)
        // Static rank 1 comes before static rank 2
        assertEquals(cand1.positionId, ranked[0].positionId)
        assertEquals(1, ranked[0].rank)
        assertEquals(cand2.positionId, ranked[1].positionId)
        assertEquals(2, ranked[1].rank)
    }

    @Test
    fun `deterministic tie-breaker orders by dynamic score descending then static rank then canonical FEN`() {
        val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val queryMoves = listOf("e2e4")

        val candA =
            CandidateContinuation(
                positionId = UUID.randomUUID(),
                fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
                canonicalFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq -",
                staticRank = 2,
                staticScore = 5.0,
                continuationResult =
                    ContinuationResult(
                        status = SearchStatus.COMPLETED,
                        achievedDepth = 8,
                        principalVariations = listOf(PrincipalVariation(1, listOf("e7e5"), 8)),
                    ),
            )

        val candB =
            CandidateContinuation(
                positionId = UUID.randomUUID(),
                fen = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 1",
                canonicalFen = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq -",
                staticRank = 1,
                staticScore = 8.0,
                continuationResult =
                    ContinuationResult(
                        status = SearchStatus.COMPLETED,
                        achievedDepth = 8,
                        principalVariations = listOf(PrincipalVariation(1, listOf("d7d5"), 8)),
                    ),
            )

        val ranked = scorer.rank(queryFen, queryMoves, listOf(candA, candB))
        assertEquals(2, ranked.size)
        // If dynamic scores are equal (both single pawn advance), candB with staticRank 1 ranks first
        if (ranked[0].dynamicScore == ranked[1].dynamicScore) {
            assertEquals(candB.positionId, ranked[0].positionId)
            assertEquals(1, ranked[0].rank)
        }
    }

    @Test
    fun `invalid FEN or illegal moves throw IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            scorer.extractDynamicTokens("invalid fen", listOf("e2e4"))
        }

        assertThrows(IllegalArgumentException::class.java) {
            scorer.extractDynamicTokens("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", listOf("e2e5"))
        }
    }

    @Test
    fun `extractDynamicTokens detects knight fork and check creation`() {
        val fen = "r3k3/8/4N3/8/8/8/8/4K3 w - - 0 1"
        val moves = listOf("e6c7")

        val terms = scorer.extractDynamicTokens(fen, moves)

        assertTrue(terms.containsKey("!N>K"), "Expected created attack on King '!N>K'")
        assertTrue(terms.containsKey("!N>R"), "Expected created attack on Rook '!N>R'")
        assertTrue(terms.containsKey("?+"), "Expected check flag '?+'")
        assertTrue(terms.containsKey("!-N"), "Expected moved Knight token '!-N'")
    }

    @Test
    fun `extractDynamicTokens detects bishop attack on queen and rook attack on queen`() {
        val bishopFen = "4k2q/8/8/8/8/8/8/B3K3 w - - 0 1"
        val bTerms = scorer.extractDynamicTokens(bishopFen, listOf("a1e5"))
        assertTrue(bTerms.containsKey("!B>Q"), "Expected bishop attack on queen '!B>Q'")
        assertTrue(bTerms.containsKey("!-B"), "Expected moved bishop token '!-B'")

        val rookFen = "4k3/4q3/8/8/8/8/8/R5K1 w - - 0 1"
        val rTerms = scorer.extractDynamicTokens(rookFen, listOf("a1e1"))
        assertTrue(rTerms.containsKey("!R>Q"), "Expected rook attack on queen '!R>Q'")
        assertTrue(rTerms.containsKey("!-R"), "Expected moved rook token '!-R'")
    }

    @Test
    fun `extractDynamicTokens detects queen multi-directional attack and check`() {
        val fen = "r3k3/8/8/8/8/8/8/4K2Q w - - 0 1"
        val terms = scorer.extractDynamicTokens(fen, listOf("h1e4"))

        assertTrue(terms.containsKey("!Q>K"), "Expected queen attack on King '!Q>K'")
        assertTrue(terms.containsKey("!Q>R"), "Expected queen attack on Rook '!Q>R'")
        assertTrue(terms.containsKey("?+"), "Expected check flag '?+'")
        assertTrue(terms.containsKey("!-Q"), "Expected moved queen token '!-Q'")
    }

    @Test
    fun `extractDynamicTokens detects pawn attack on knight and pawn promotion`() {
        val pawnAttackFen = "4k3/8/2n5/8/3P4/8/8/4K3 w - - 0 1"
        val pTerms = scorer.extractDynamicTokens(pawnAttackFen, listOf("d4d5"))
        assertTrue(pTerms.containsKey("!P>N"), "Expected pawn attack on knight '!P>N'")
        assertTrue(pTerms.containsKey("!-P"), "Expected moved pawn '!-P'")

        val promoFen = "3k4/4P3/8/8/8/8/8/4K3 w - - 0 1"
        val promoTerms = scorer.extractDynamicTokens(promoFen, listOf("e7e8q"))
        assertTrue(promoTerms.containsKey("?="), "Expected promotion flag '?='")
        assertTrue(promoTerms.containsKey("!-P"), "Expected moved pawn '!-P'")
        assertTrue(promoTerms.containsKey("?+"), "Expected check flag on promotion '?+'")
    }

    @Test
    fun `rank produces strictly higher dynamic score for full tactical match than partial or non-matching candidate`() {
        val queryFen = "r3k3/8/4N3/8/8/8/8/4K3 w - - 0 1"
        val queryMoves = listOf("e6c7")

        val cand1Id = UUID.randomUUID()
        val cand1 =
            CandidateContinuation(
                positionId = cand1Id,
                fen = queryFen,
                canonicalFen = "r3k3/8/4N3/8/8/8/8/4K3 w - -",
                staticRank = 1,
                staticScore = 10.0,
                continuationResult =
                    ContinuationResult(
                        status = SearchStatus.COMPLETED,
                        achievedDepth = 8,
                        principalVariations = listOf(PrincipalVariation(1, listOf("e6c7"), 8)),
                    ),
            )

        val cand2Id = UUID.randomUUID()
        val cand2 =
            CandidateContinuation(
                positionId = cand2Id,
                fen = "4k3/8/4N3/8/8/8/8/4K3 w - - 0 1",
                canonicalFen = "4k3/8/4N3/8/8/8/8/4K3 w - -",
                staticRank = 2,
                staticScore = 10.0,
                continuationResult =
                    ContinuationResult(
                        status = SearchStatus.COMPLETED,
                        achievedDepth = 8,
                        principalVariations = listOf(PrincipalVariation(1, listOf("e6d4"), 8)),
                    ),
            )

        val cand3Id = UUID.randomUUID()
        val cand3 =
            CandidateContinuation(
                positionId = cand3Id,
                fen = "4k3/8/8/8/8/8/3P4/4K3 w - - 0 1",
                canonicalFen = "4k3/8/8/8/8/8/3P4/4K3 w - -",
                staticRank = 3,
                staticScore = 10.0,
                continuationResult =
                    ContinuationResult(
                        status = SearchStatus.COMPLETED,
                        achievedDepth = 8,
                        principalVariations = listOf(PrincipalVariation(1, listOf("d2d3"), 8)),
                    ),
            )

        val ranked = scorer.rank(queryFen, queryMoves, listOf(cand3, cand2, cand1))

        assertEquals(3, ranked.size)
        assertEquals(cand1Id, ranked[0].positionId, "Full tactical match should rank 1st")
        assertEquals(cand2Id, ranked[1].positionId, "Partial match should rank 2nd")
        assertEquals(cand3Id, ranked[2].positionId, "Non-matching candidate should rank 3rd")

        assertTrue(ranked[0].dynamicScore > ranked[1].dynamicScore, "Full match must outscore partial match")
        assertTrue(ranked[1].dynamicScore > ranked[2].dynamicScore, "Partial match must outscore non-match")
        assertEquals(0.0, ranked[2].dynamicScore, "Non-matching candidate must have score 0.0")
    }
}
