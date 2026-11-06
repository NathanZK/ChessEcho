package com.chessecho.research

import com.chessecho.similarity.PositionDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ConnectivityCandidateEligibilityTest {

    @Test
    fun `canonical-position inequality excludes identical canonical positions regardless of UUID`() {
        val queryFen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
        val queryCanonical = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq -"
        val query = PositionDocument(
            positionId = UUID.randomUUID(),
            fen = queryFen,
            canonicalFen = queryCanonical,
            terms = emptyMap()
        )

        // Clone candidate with different UUID but same canonicalFen
        val duplicateCandidate = PositionDocument(
            positionId = UUID.randomUUID(),
            fen = "$queryCanonical 10 10",
            canonicalFen = queryCanonical,
            terms = emptyMap()
        )

        val otherCandidate = PositionDocument(
            positionId = UUID.randomUUID(),
            fen = "r1bqkb1r/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 0 1",
            canonicalFen = "r1bqkb1r/pppp1ppp/2n5/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq -",
            terms = emptyMap()
        )

        val selector = ConnectivityCandidateSelector(
            corpus = listOf(query, duplicateCandidate, otherCandidate),
            representationExtractor = { setOf("attack|queen|pawn|f7") }
        )

        val poolResult = selector.selectCandidates(query, requestedBudget = 50)
        assertFalse(
            poolResult.candidates.any { it.canonicalFen == query.canonicalFen },
            "Candidates with identical canonicalFen must be excluded"
        )
        assertFalse(
            poolResult.candidates.any { it.positionId == duplicateCandidate.positionId },
            "Duplicate candidate with same canonicalFen must be excluded"
        )
    }

    @Test
    fun `candidates with zero BM25 scores never enter the candidate pool`() {
        val query = PositionDocument(
            positionId = UUID.randomUUID(),
            fen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
            canonicalFen = "q1",
            terms = emptyMap()
        )

        val matchingCandidate = PositionDocument(
            positionId = UUID.randomUUID(),
            fen = "c1_fen w - - 0 1",
            canonicalFen = "c1",
            terms = emptyMap()
        )

        val zeroScoreCandidate = PositionDocument(
            positionId = UUID.randomUUID(),
            fen = "c2_fen w - - 0 1",
            canonicalFen = "c2",
            terms = emptyMap()
        )

        val selector = ConnectivityCandidateSelector(
            corpus = listOf(query, matchingCandidate, zeroScoreCandidate),
            representationExtractor = { doc ->
                when (doc.canonicalFen) {
                    "q1" -> setOf("term_A")
                    "c1" -> setOf("term_A")
                    "c2" -> setOf("term_B")
                    else -> emptySet()
                }
            }
        )

        val poolResult = selector.selectCandidates(query, requestedBudget = 10)
        assertEquals(1, poolResult.candidates.size, "Only 1 positive-scoring candidate should be retrieved")
        assertEquals(matchingCandidate.positionId, poolResult.candidates[0].positionId)
        assertFalse(
            poolResult.candidates.any { it.positionId == zeroScoreCandidate.positionId },
            "Zero-score candidate must NEVER enter candidate pool even if budget is unfilled"
        )
    }

    @Test
    fun `pool exhaustion accounting records exhaustion when positive pool is smaller than budget`() {
        val query = PositionDocument(
            positionId = UUID.randomUUID(),
            fen = "q_fen w - - 0 1",
            canonicalFen = "q",
            terms = emptyMap()
        )
        val candidate1 = PositionDocument(
            positionId = UUID.randomUUID(),
            fen = "c1_fen w - - 0 1",
            canonicalFen = "c1",
            terms = emptyMap()
        )
        val candidate2 = PositionDocument(
            positionId = UUID.randomUUID(),
            fen = "c2_fen w - - 0 1",
            canonicalFen = "c2",
            terms = emptyMap()
        )

        val selector = ConnectivityCandidateSelector(
            corpus = listOf(query, candidate1, candidate2),
            representationExtractor = { setOf("shared_term") }
        )

        // Requested budget 50, but only 2 eligible candidates exist
        val poolResult = selector.selectCandidates(query, requestedBudget = 50)
        assertEquals(50, poolResult.requestedBudget)
        assertEquals(2, poolResult.actualCandidateCount)
        assertEquals(2, poolResult.positiveCandidateCount)
        assertTrue(poolResult.isPoolExhausted, "isPoolExhausted must be true when positive count < budget")
    }

    @Test
    fun `deterministic tie-breaking sorts by canonicalFen ascending then positionId ascending`() {
        val query = PositionDocument(
            positionId = UUID.randomUUID(),
            fen = "q_fen w - - 0 1",
            canonicalFen = "q",
            terms = emptyMap()
        )
        val uuid1 = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val uuid2 = UUID.fromString("00000000-0000-0000-0000-000000000002")

        val candB = PositionDocument(
            positionId = uuid1,
            fen = "b_fen w - - 0 1",
            canonicalFen = "fen_b",
            terms = emptyMap()
        )
        val candA = PositionDocument(
            positionId = uuid2,
            fen = "a_fen w - - 0 1",
            canonicalFen = "fen_a",
            terms = emptyMap()
        )

        val selector = ConnectivityCandidateSelector(
            corpus = listOf(query, candB, candA),
            representationExtractor = { setOf("shared_term") }
        )

        val poolResult = selector.selectCandidates(query, requestedBudget = 10)
        assertEquals(2, poolResult.candidates.size)
        // With identical scores, 'fen_a' comes before 'fen_b'
        assertEquals("fen_a", poolResult.candidates[0].canonicalFen)
        assertEquals("fen_b", poolResult.candidates[1].canonicalFen)
    }
}
