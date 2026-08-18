package com.chessecho.service.continuation

import com.chessecho.domain.Position
import com.chessecho.repository.HistoricalMoveStats
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HumanMoveProviderTest {
    private val positionRepository: PositionRepository = mock()
    private val positionOccurrenceRepository: PositionOccurrenceRepository = mock()
    private val humanMoveProvider = HumanMoveProvider(positionRepository, positionOccurrenceRepository)

    @Test
    fun `providerType is HUMAN`() {
        assertEquals("HUMAN", humanMoveProvider.providerType)
    }

    @Test
    fun `getContinuationCandidates returns multiple historical move candidates when position and occurrences exist`() {
        val fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"
        val positionId = UUID.randomUUID()
        val mockPosition = Position(id = positionId, hash = "somehash", fen = fen)

        val stats =
            listOf(
                HistoricalMoveStats(movePlayed = "Nc6", timesPlayed = 15),
                HistoricalMoveStats(movePlayed = "Nf6", timesPlayed = 8),
                HistoricalMoveStats(movePlayed = "d6", timesPlayed = 3),
            )

        whenever(positionRepository.findByHash(any())).thenReturn(mockPosition)
        whenever(positionOccurrenceRepository.findHistoricalMoveStatsByPositionId(positionId)).thenReturn(stats)

        val result = humanMoveProvider.getContinuationCandidates(fen)

        assertEquals(3, result.size)
        assertEquals("Nc6", result[0].move)
        assertEquals(15, result[0].timesPlayed)
        assertEquals("HUMAN", result[0].providerType)

        assertEquals("Nf6", result[1].move)
        assertEquals(8, result[1].timesPlayed)

        assertEquals("d6", result[2].move)
        assertEquals(3, result[2].timesPlayed)
    }

    @Test
    fun `getContinuationCandidates returns empty list when position hash is not in repository`() {
        val fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"
        whenever(positionRepository.findByHash(any())).thenReturn(null)

        val result = humanMoveProvider.getContinuationCandidates(fen)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getContinuationCandidates returns empty list when position exists but has no occurrences`() {
        val fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"
        val positionId = UUID.randomUUID()
        val mockPosition = Position(id = positionId, hash = "somehash", fen = fen)

        whenever(positionRepository.findByHash(any())).thenReturn(mockPosition)
        whenever(positionOccurrenceRepository.findHistoricalMoveStatsByPositionId(positionId)).thenReturn(emptyList())

        val result = humanMoveProvider.getContinuationCandidates(fen)

        assertTrue(result.isEmpty())
    }
}
