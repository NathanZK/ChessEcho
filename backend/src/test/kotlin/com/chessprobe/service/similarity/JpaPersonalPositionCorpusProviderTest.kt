package com.chessprobe.service.similarity

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.Position
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.similarity.RelationalPawnTokenExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class JpaPersonalPositionCorpusProviderTest {
    private val occurrenceRepository: PositionOccurrenceRepository = mock()
    private val tokenExtractor = RelationalPawnTokenExtractor()

    private val provider = JpaPersonalPositionCorpusProvider(occurrenceRepository, tokenExtractor)

    private val afterE4Fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    private val sicilianFen = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2"

    @Test
    fun `loadPersonalPositions extracts documents for all positions returned by repository`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "player1")

        val e4Pos = Position(id = UUID.randomUUID(), hash = "hash-e4", fen = afterE4Fen)
        val sicilianPos = Position(id = UUID.randomUUID(), hash = "hash-sicilian", fen = sicilianFen)

        whenever(occurrenceRepository.findDistinctPositionsByChessAccountId(account.id))
            .thenReturn(listOf(e4Pos, sicilianPos))

        val docs = provider.loadPersonalPositions(account)

        assertEquals(2, docs.size)
        assertEquals(setOf(e4Pos.id, sicilianPos.id), docs.map { it.positionId }.toSet())
    }

    @Test
    fun `loadPersonalPositions returns empty list when repository returns empty list`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "player1")

        whenever(occurrenceRepository.findDistinctPositionsByChessAccountId(account.id))
            .thenReturn(emptyList())

        val docs = provider.loadPersonalPositions(account)

        assertEquals(0, docs.size)
    }
}
