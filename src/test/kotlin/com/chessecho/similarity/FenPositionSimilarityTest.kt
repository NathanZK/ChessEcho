package com.chessecho.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ln

class FenPositionSimilarityTest {
    private val similarity = FenPositionSimilarity()

    @Test
    fun `empty candidate collection returns empty ranking`() {
        assertTrue(similarity.rank(START_FEN, emptyList()).isEmpty())
    }

    @Test
    fun `query identity is excluded and candidate identities are deduplicated`() {
        val result =
            similarity.rank(
                START_FEN,
                listOf(START_FEN, START_FEN_WITH_HALF_MOVE_INCREMENT, AFTER_D4, AFTER_D4_WITH_HALF_MOVE_INCREMENT),
            )

        assertEquals(listOf(canonical(AFTER_D4)), result.map { it.canonicalFen })
    }

    @Test
    fun `counter-only query changes do not change ranking or scores`() {
        val candidates = listOf(AFTER_D4, AFTER_E4)
        val expected = similarity.rank(START_FEN, candidates)

        assertEquals(expected, similarity.rank(START_FEN_WITH_HALF_MOVE_INCREMENT, candidates))
        assertEquals(expected, similarity.rank(START_FEN_WITH_FULL_MOVE_INCREMENT, candidates))
    }

    @Test
    fun `malformed query is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            similarity.rank("not a FEN", listOf(AFTER_D4))
        }
    }

    @Test
    fun `invalid candidate rejects the whole call and identifies its original index`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                similarity.rank(START_FEN, listOf(AFTER_D4, "8/8/8/8/8/8/8/8 w - - 0 1"))
            }

        assertTrue(error.message.orEmpty().contains("candidate[1]"))
    }

    @Test
    fun `inconsistent castling rights are rejected`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                similarity.rank(START_FEN, listOf(INCONSISTENT_CASTLING_FEN))
            }

        assertTrue(error.message.orEmpty().contains("candidate[0]"))
    }

    @Test
    fun `en passant target is rejected when the pawn origin is occupied`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                similarity.rank(START_FEN, listOf(INCONSISTENT_EN_PASSANT_FEN))
            }

        assertTrue(error.message.orEmpty().contains("candidate[0]"))
    }

    @Test
    fun `adjacent kings are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            similarity.rank(START_FEN, listOf(ADJACENT_KINGS_FEN))
        }
    }

    @Test
    fun `the side that just moved cannot have left its king in check`() {
        assertThrows(IllegalArgumentException::class.java) {
            similarity.rank(START_FEN, listOf(PREVIOUS_MOVER_KING_IN_CHECK_FEN))
        }
    }

    @Test
    fun `pawns on the first or eighth rank are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            similarity.rank(START_FEN, listOf(PAWN_ON_BACK_RANK_FEN))
        }
    }

    @Test
    fun `a side cannot have more than eight pawns`() {
        assertThrows(IllegalArgumentException::class.java) {
            similarity.rank(START_FEN, listOf(TOO_MANY_PAWNS_FEN))
        }
    }

    @Test
    fun `a side cannot have more than sixteen pieces`() {
        assertThrows(IllegalArgumentException::class.java) {
            similarity.rank(START_FEN, listOf(TOO_MANY_PIECES_FEN))
        }
    }

    @Test
    fun `rankings are independent of candidate order and exact ties use canonical FEN`() {
        val candidates = listOf(START_BLACK_TO_MOVE, START_FEN)

        val forward = similarity.rank(AFTER_D4, candidates)
        val reverse = similarity.rank(AFTER_D4, candidates.reversed())

        assertEquals(forward, reverse)
        assertEquals(candidates.map(::canonical).sorted(), forward.map { it.canonicalFen })
        assertEquals(forward[0].score, forward[1].score)
    }

    @Test
    fun `document features contain a Chebyshev-weighted reachable destination`() {
        val terms = similarity.extractTerms(QUEEN_POSITION, includeReachability = true)

        assertEquals(57.0 / 64.0, terms.getValue("reach|white|queen|e2"), 1e-15)
    }

    @Test
    fun `weighted frequencies from distinct pieces with the same term are added`() {
        val terms = similarity.extractTerms(TWO_KNIGHTS_POSITION, includeReachability = true)

        assertEquals(100.0 / 64.0, terms.getValue("reach|white|knight|e3"), 1e-15)
    }

    @Test
    fun `query features omit reachability terms`() {
        val terms = similarity.extractTerms(QUEEN_POSITION, includeReachability = false)

        assertTrue(terms.keys.none { it.startsWith("reach|") })
        assertTrue(terms.containsKey("piece|white|queen|d1"))
    }

    @Test
    fun `attack defense and unobstructed ray terms use source-free relation tuples`() {
        val terms = similarity.extractTerms(RELATION_POSITION, includeReachability = false)

        assertEquals(1.0, terms.getValue("attack|white|rook|black|king|e8"))
        assertEquals(1.0, terms.getValue("ray|white|rook|black|king|e8"))
        assertEquals(1.0, terms.getValue("defense|white|king|white|rook|e1"))
        assertTrue(terms.keys.none { it.contains("|from|") })
    }

    @Test
    fun `identical attack relationships from multiple pieces retain term frequency`() {
        val terms = similarity.extractTerms(TWO_ATTACKERS_POSITION, includeReachability = false)

        assertEquals(2.0, terms.getValue("attack|white|knight|black|pawn|d7"))
    }

    @Test
    fun `attack map includes geometrically attacked squares from pinned pieces`() {
        val terms = similarity.extractTerms(PINNED_ROOK_POSITION, includeReachability = false)

        assertEquals(1.0, terms.getValue("attack|white|rook|black|knight|d2"))
    }

    @Test
    fun `relation terms are attributed only to pieces that attack the target`() {
        val terms = similarity.extractTerms(UNRELATED_PIECE_POSITION, includeReachability = false)

        assertEquals(1.0, terms.getValue("attack|white|rook|black|king|e8"))
        assertTrue("attack|white|knight|black|king|e8" !in terms)
    }

    @Test
    fun `reachability stops at occupied blockers`() {
        val terms = similarity.extractTerms(QUEEN_BLOCKED_POSITION, includeReachability = true)

        assertTrue("reach|white|queen|d4" !in terms)
    }

    @Test
    fun `castling is excluded from structural reachability terms`() {
        val terms = similarity.extractTerms(OPEN_CASTLING_POSITION, includeReachability = true)

        assertTrue("reach|white|king|g1" !in terms)
        assertTrue("reach|white|king|c1" !in terms)
    }

    @Test
    fun `BM25 uses weighted term frequency and the declared length normalization`() {
        val score =
            FenPositionSimilarity.bm25Score(
                queryTerms = setOf("q"),
                documentTerms = mapOf("q" to 2.0, "other" to 2.0),
                documentFrequencies = mapOf("q" to 1, "other" to 2),
                documentCount = 2,
                documentLength = 4.0,
                averageDocumentLength = 4.0,
            )

        assertEquals(ln(2.0) * 1.375, score, 1e-15)
    }

    @Test
    fun `BM25 returns zero for an empty document pool`() {
        assertEquals(
            0.0,
            FenPositionSimilarity.bm25Score(
                queryTerms = setOf("q"),
                documentTerms = emptyMap(),
                documentFrequencies = emptyMap(),
                documentCount = 0,
                documentLength = 0.0,
                averageDocumentLength = 0.0,
            ),
        )
    }

    @Test
    fun `ranking recomputes document frequencies and average length for each pool`() {
        val onlyDocument = similarity.rank(QUEEN_POSITION, listOf(QUEEN_POSITION_BLACK_TO_MOVE)).single()
        val expandedPool =
            similarity.rank(
                QUEEN_POSITION,
                listOf(QUEEN_POSITION_BLACK_TO_MOVE, QUEEN_MOVED_POSITION_BLACK_TO_MOVE),
            ).first()

        assertEquals(canonical(QUEEN_POSITION_BLACK_TO_MOVE), onlyDocument.canonicalFen)
        assertTrue(onlyDocument.score != expandedPool.score)
    }

    private fun canonical(fen: String): String = fen.split(' ').take(4).joinToString(" ")

    companion object {
        private const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        private const val START_FEN_WITH_HALF_MOVE_INCREMENT = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 1 1"
        private const val START_FEN_WITH_FULL_MOVE_INCREMENT = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 6"
        private const val START_BLACK_TO_MOVE = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1"
        private const val AFTER_D4 = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq - 0 1"
        private const val AFTER_D4_WITH_HALF_MOVE_INCREMENT = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq - 1 1"
        private const val AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        private const val QUEEN_POSITION = "4k3/8/8/8/8/8/8/3QK3 w - - 0 1"
        private const val QUEEN_POSITION_BLACK_TO_MOVE = "4k3/8/8/8/8/8/8/3QK3 b - - 0 1"
        private const val QUEEN_MOVED_POSITION_BLACK_TO_MOVE = "4k3/8/8/8/8/8/4Q3/4K3 b - - 0 1"
        private const val RELATION_POSITION = "4k3/8/8/8/8/8/8/3KR3 b - - 0 1"
        private const val TWO_KNIGHTS_POSITION = "4k3/8/8/8/8/8/2N3N1/4K3 w - - 0 1"
        private const val TWO_ATTACKERS_POSITION = "4k3/3p4/8/2N1N3/8/8/8/K7 w - - 0 1"
        private const val PINNED_ROOK_POSITION = "k3r3/8/8/8/8/8/3nR3/4K3 w - - 0 1"
        private const val UNRELATED_PIECE_POSITION = "4k3/8/8/8/8/8/N7/3KR3 b - - 0 1"
        private const val QUEEN_BLOCKED_POSITION = "4k3/8/8/8/8/3P4/8/3QK3 w - - 0 1"
        private const val OPEN_CASTLING_POSITION = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
        private const val INCONSISTENT_CASTLING_FEN = "4k3/8/8/8/8/8/8/4K3 w K - 0 1"
        private const val INCONSISTENT_EN_PASSANT_FEN = "4k3/4p3/8/4p3/8/8/8/4K3 w - e6 0 1"
        private const val ADJACENT_KINGS_FEN = "8/8/8/8/8/8/4k3/4K3 w - - 0 1"
        private const val PREVIOUS_MOVER_KING_IN_CHECK_FEN = "4k3/8/8/8/8/8/8/K3R3 w - - 0 1"
        private const val PAWN_ON_BACK_RANK_FEN = "P3k3/8/8/8/8/8/8/4K3 w - - 0 1"
        private const val TOO_MANY_PAWNS_FEN = "4k3/pppppppp/4p3/8/8/8/8/4K3 w - - 0 1"
        private const val TOO_MANY_PIECES_FEN = "3k4/nnnnnnnn/nnnnnnnn/8/8/8/8/K7 w - - 0 1"
    }
}
