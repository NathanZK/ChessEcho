package com.chessprobe.service.similarity

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.EngineAnalysis
import com.chessprobe.domain.Position
import com.chessprobe.repository.EngineAnalysisRepository
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.similarity.DynamicTacticalScorer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class JpaPersonalDynamicCorpusProviderTest {
    private val occurrenceRepository: PositionOccurrenceRepository = mock()
    private val engineAnalysisRepository: EngineAnalysisRepository = mock()
    private val dynamicTacticalScorer: DynamicTacticalScorer = mock()

    private val provider =
        JpaPersonalDynamicCorpusProvider(
            occurrenceRepository = occurrenceRepository,
            engineAnalysisRepository = engineAnalysisRepository,
            dynamicTacticalScorer = dynamicTacticalScorer,
        )

    @Test
    fun `loadPersonalDynamicPositions returns empty list when account has no positions`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "test_player")
        whenever(occurrenceRepository.findDistinctPositionsByChessAccountId(account.id)).thenReturn(emptyList())

        val result = provider.loadPersonalDynamicPositions(account)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `loadPersonalDynamicPositions joins engine analysis and extracts dynamic tokens`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "test_player")
        val posWithAnalysis =
            Position(
                hash = "hash1",
                fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            )
        val posWithoutAnalysis =
            Position(
                hash = "hash2",
                fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
            )

        whenever(occurrenceRepository.findDistinctPositionsByChessAccountId(account.id))
            .thenReturn(listOf(posWithAnalysis, posWithoutAnalysis))

        val analysis =
            EngineAnalysis(
                position = posWithAnalysis,
                depth = 10,
                bestMove = "e7e5",
            )

        whenever(
            engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(
                setOf(posWithAnalysis.id, posWithoutAnalysis.id),
            ),
        ).thenReturn(listOf(analysis))

        whenever(dynamicTacticalScorer.extractDynamicTokens(eq(posWithAnalysis.fen), eq(listOf("e7e5"))))
            .thenReturn(mapOf("e7e5" to 1.0, "+check" to 1.0))

        val docs = provider.loadPersonalDynamicPositions(account)
        assertEquals(2, docs.size)

        val doc1 = docs.first { it.positionId == posWithAnalysis.id }
        assertEquals("b", doc1.sideToMove)
        assertEquals(2, doc1.terms.size)
        assertEquals(1.0, doc1.terms["e7e5"])

        val doc2 = docs.first { it.positionId == posWithoutAnalysis.id }
        assertEquals("w", doc2.sideToMove)
        assertTrue(doc2.terms.isEmpty())
    }
}
