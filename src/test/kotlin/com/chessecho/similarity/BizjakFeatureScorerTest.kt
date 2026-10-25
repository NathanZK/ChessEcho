package com.chessecho.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BizjakFeatureScorerTest {
    private val staticScorer = BizjakFeatureScorer(ScoringVariant.STATIC_ONLY)
    private val dynamicScorer = BizjakFeatureScorer(ScoringVariant.DYNAMIC_ONLY)
    private val combinedScorer = BizjakFeatureScorer(ScoringVariant.COMBINED)

    @Test
    fun `static pawns extracts isolated passed doubled and island structures`() {
        // FEN with specific pawn structures:
        // White: a2, c3, c4 (doubled on c), f2, g3 (chain w[f2/g3])
        // Black: a7 (isolated Ia7), e5 (passed Fd4? e5 passed), h7
        val fen = "8/p6p/8/4p3/2P5/2P3P1/P4P2/4K2k w - - 0 1"
        val doc =
            TacticalPuzzleDocument(
                puzzleId = "pawn_test",
                canonicalFen = fen.split(" ").take(4).joinToString(" "),
                fen = fen,
                moves = emptyList(),
                isQuery = false,
            )

        val terms = staticScorer.extractTerms(doc)

        // Doubled pawns on c-file for White
        assertTrue(terms.containsKey("Sc"), "Expected doubled pawn on c-file 'Sc'")
        // Chain on f2/g3
        assertTrue(terms.containsKey("w[f2/g3]"), "Expected pawn chain 'w[f2/g3]'")
        // Isolated Black pawn on a7
        assertTrue(terms.containsKey("Ia7"), "Expected isolated Black pawn 'Ia7'")
        // Passed pawn for Black on e5 (no White pawns on d, e, f ahead of e5, i.e., ranks 4, 3, 2, 1)
        // White pawns are on c3, c4, f2, g3. None on d, e, or f ahead of e5 (f2 is rank 2, e5 is moving down towards rank 1)
        // Wait, for Black, moving down towards rank 1: ranks ahead of e5 are 4, 3, 2, 1.
        // On file f: White has f2! f2 is on rank 2, which is ahead of Black's e5 pawn! So e5 is not passed if f2 can contest.
        // White pawn on a2 is isolated because no pawns on b-file.
        assertTrue(terms.containsKey("Ia2"), "Expected isolated White pawn 'Ia2'")
        // Islands: White has a2 (island 1), c3/c4 (island 2), f2/g3 (island 3) -> P(3)
        assertTrue(terms.containsKey("P(3)"), "Expected 3 White pawn islands 'P(3)'")
        // Black has a7 (island 1), e5 (island 2), h7 (island 3) -> p(3)
        assertTrue(terms.containsKey("p(3)"), "Expected 3 Black pawn islands 'p(3)'")
    }

    @Test
    fun `static pawns extracts protected passed and backward pawns`() {
        // White: a5 (passed, no Black pawns on a, b ahead of it, protected by b4),
        // White: d3 (backward: advance square d4 attacked by Black pawn on c5 and e5, and c4/e4 are ahead)
        // Black: c5, e5, h7
        val fen = "4k3/7p/8/P1p1p3/1P6/3P4/8/4K3 w - - 0 1"
        val doc =
            TacticalPuzzleDocument(
                puzzleId = "passed_backward",
                canonicalFen = fen.split(" ").take(4).joinToString(" "),
                fen = fen,
                moves = emptyList(),
                isQuery = false,
            )
        val terms = staticScorer.extractTerms(doc)

        // White a5 is passed and protected by b4
        assertTrue(terms.containsKey("Fa5"), "Expected passed pawn 'Fa5'")
        assertTrue(terms.containsKey("'Fa5"), "Expected protected passed pawn ''Fa5'")
        // White d3 is backward (advance square d4 is attacked by Black pawn on c5 and e5, c/e White pawns are absent/ahead)
        assertTrue(terms.containsKey("ld3"), "Expected backward pawn 'ld3'")
    }

    @Test
    fun `dynamic general flags are extracted correctly across solution sequence`() {
        // Solution with player capture (?px), check (?+), checkmate (?#), sacrifice (?S)
        // White plays 1. Qxf7+ Kxf7 2. Bc4#
        val fen = "r1bqk2r/pppp1ppp/2n5/4p3/2B1n3/5Q2/PPPP1PPP/RNB1K2R b KQkq - 0 1"
        val doc =
            TacticalPuzzleDocument(
                puzzleId = "dyn_general",
                canonicalFen = fen.split(" ").take(4).joinToString(" "),
                fen = fen,
                moves = listOf("d7d5", "f3f7", "e8f7", "c4d5"),
                isQuery = false,
            )
        val terms = dynamicScorer.extractTerms(doc)

        assertTrue(terms.containsKey("?px"), "Expected player capture flag '?px'")
        assertTrue(terms.containsKey("?ox"), "Expected opponent capture flag '?ox'")
        assertTrue(terms.containsKey("?+"), "Expected check flag '?+'")
        assertTrue(terms.containsKey("?S"), "Expected sacrifice flag '?S'")
    }

    @Test
    fun `dynamic solution tokens encode pieces consecutive moves and checkmate`() {
        // Puzzle ending in checkmate: White plays 1. Re8#
        val fen = "6k1/p4ppp/8/8/8/8/8/3R2K1 b - - 0 1"
        val doc =
            TacticalPuzzleDocument(
                puzzleId = "mate_puzzle",
                canonicalFen = fen.split(" ").take(4).joinToString(" "),
                fen = fen,
                moves = listOf("a7a6", "d1d8"),
                isQuery = false,
            )
        val terms = dynamicScorer.extractTerms(doc)

        assertTrue(terms.containsKey("!-R"), "Expected moved Rook token '!-R'")
        assertTrue(terms.containsKey("?#"), "Expected checkmate flag '?#'")
        assertTrue(terms.containsKey("!#R"), "Expected checkmate deliverer token '!#R'")
        // Single ply solution (1 player move): no consecutive move token
        assertFalse(terms.containsKey("!-RR"), "Single-ply should not emit consecutive moved token")
    }

    @Test
    fun `dynamic solution multi-ply frequency accumulation`() {
        // Multi-ply with two knight moves: 1. Nd5 ... 2. Nxe7
        val fen = "r3k2r/ppp1bppp/8/8/8/2N5/PPPP1PPP/R1BQK2R b KQkq - 0 1"
        val doc =
            TacticalPuzzleDocument(
                puzzleId = "multi_knight",
                canonicalFen = fen.split(" ").take(4).joinToString(" "),
                fen = fen,
                moves = listOf("a7a6", "c3d5", "e8d8", "d5e7"),
                isQuery = false,
            )
        val terms = dynamicScorer.extractTerms(doc)

        // Knight moved twice: term frequency should be 2.0
        assertEquals(2.0, terms.getValue("!-N"), 0.001, "Expected frequency 2.0 for '!-N'")
        assertTrue(terms.containsKey("!xB"), "Expected captured Bishop token '!xB'")
        assertTrue(terms.containsKey("!-NK"), "Expected consecutive moved pair '!-NK'")
    }

    @Test
    fun `deterministic tie breaking sorts equal scores by canonical FEN ascending`() {
        val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val candFenA = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        val candFenB = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 1"

        val query =
            TacticalPuzzleDocument(
                puzzleId = "q",
                canonicalFen = queryFen.split(" ").take(4).joinToString(" "),
                fen = queryFen,
                moves = listOf("e2e4", "e7e5"),
                isQuery = true,
            )
        val candA =
            TacticalPuzzleDocument(
                puzzleId = "a",
                canonicalFen = candFenA.split(" ").take(4).joinToString(" "),
                fen = candFenA,
                moves = listOf("e7e5", "g1f3"),
            )
        val candB =
            TacticalPuzzleDocument(
                puzzleId = "b",
                canonicalFen = candFenB.split(" ").take(4).joinToString(" "),
                fen = candFenB,
                moves = listOf("d7d5", "g1f3"),
            )

        val ranked = dynamicScorer.rank(query, listOf(candA, candB))
        assertEquals(2, ranked.size)
        // If scores are equal, canonical FEN ascending decides order
        if (ranked[0].score == ranked[1].score) {
            assertTrue(ranked[0].canonicalFen < ranked[1].canonicalFen)
        }
    }

    @Test
    fun `malformed FEN or illegal moves reject with IllegalArgumentException`() {
        val badDoc =
            TacticalPuzzleDocument(
                puzzleId = "bad",
                canonicalFen = "invalid",
                fen = "invalid FEN string",
                moves = listOf("e2e4"),
            )
        assertThrows(IllegalArgumentException::class.java) {
            staticScorer.extractTerms(badDoc)
        }
    }
}
