package com.chessecho.service.continuation

import com.chessecho.domain.ContinuationMode
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ContinuationServiceTest {
    private val engineMoveProvider: MoveProvider = mock()
    private val humanMoveProvider: MoveProvider = mock()

    init {
        whenever(engineMoveProvider.providerType).thenReturn("ENGINE")
        whenever(humanMoveProvider.providerType).thenReturn("HUMAN")
    }

    private val continuationService =
        ContinuationService(
            engineMoveProvider = engineMoveProvider,
            humanMoveProvider = humanMoveProvider,
        )

    @Test
    fun `ENGINE request sets requestedMode ENGINE and effectiveProvider ENGINE`() {
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val engineCandidates =
            listOf(
                ContinuationCandidate(move = "Bb5", resultingFen = "fen_bb5", providerType = "ENGINE", evalCp = 40, evalLoss = 0.0),
                ContinuationCandidate(move = "Bc4", resultingFen = "fen_bc4", providerType = "ENGINE", evalCp = 35, evalLoss = 0.05),
            )
        whenever(engineMoveProvider.getContinuationCandidates(fen)).thenReturn(engineCandidates)

        val result = continuationService.getContinuation(fen, ContinuationMode.ENGINE)

        assertNotNull(result)
        assertEquals(2, result.candidates.size)
        assertEquals(ContinuationMode.ENGINE, result.requestedMode)
        assertEquals("ENGINE", result.effectiveProvider)
        assertEquals("Bb5", result.candidates[0].move)
        assertEquals("Bc4", result.candidates[1].move)

        verify(engineMoveProvider).getContinuationCandidates(fen)
        verifyNoInteractions(humanMoveProvider)
    }

    @Test
    fun `HUMAN request with historical candidates sets requestedMode HUMAN and effectiveProvider HUMAN`() {
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val humanCandidates =
            listOf(
                ContinuationCandidate(move = "Nc6", resultingFen = "fen_nc6", providerType = "HUMAN", timesPlayed = 12),
                ContinuationCandidate(move = "Nf6", resultingFen = "fen_nf6", providerType = "HUMAN", timesPlayed = 5),
            )
        whenever(humanMoveProvider.getContinuationCandidates(fen)).thenReturn(humanCandidates)

        val result = continuationService.getContinuation(fen, ContinuationMode.HUMAN)

        assertNotNull(result)
        assertEquals(2, result.candidates.size)
        assertEquals(ContinuationMode.HUMAN, result.requestedMode)
        assertEquals("HUMAN", result.effectiveProvider)
        assertEquals("Nc6", result.candidates[0].move)
        assertEquals("Nf6", result.candidates[1].move)

        verify(humanMoveProvider).getContinuationCandidates(fen)
        verifyNoInteractions(engineMoveProvider)
    }

    @Test
    fun `HUMAN request with no historical candidates sets requestedMode HUMAN and effectiveProvider ENGINE`() {
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        whenever(humanMoveProvider.getContinuationCandidates(fen)).thenReturn(emptyList())

        val fallbackEngineCandidates =
            listOf(
                ContinuationCandidate(move = "d4", resultingFen = "fen_d4", providerType = "ENGINE", evalCp = 30, evalLoss = 0.0),
                ContinuationCandidate(move = "Bc4", resultingFen = "fen_bc4", providerType = "ENGINE", evalCp = 25, evalLoss = 0.05),
            )
        whenever(engineMoveProvider.getContinuationCandidates(fen)).thenReturn(fallbackEngineCandidates)

        val result = continuationService.getContinuation(fen, ContinuationMode.HUMAN)

        assertNotNull(result)
        assertEquals(2, result.candidates.size)
        assertEquals(ContinuationMode.HUMAN, result.requestedMode)
        assertEquals("ENGINE", result.effectiveProvider)
        assertEquals("d4", result.candidates[0].move)
        assertEquals("ENGINE", result.candidates[0].providerType)

        verify(humanMoveProvider).getContinuationCandidates(fen)
        verify(engineMoveProvider).getContinuationCandidates(fen)
    }

    @Test
    fun `getContinuation defaults to ContinuationMode ENGINE when mode parameter is omitted`() {
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val engineCandidates =
            listOf(
                ContinuationCandidate(move = "Bb5", resultingFen = "fen_bb5", providerType = "ENGINE", evalCp = 40, evalLoss = 0.0),
            )
        whenever(engineMoveProvider.getContinuationCandidates(fen)).thenReturn(engineCandidates)

        val result = continuationService.getContinuation(fen)

        assertNotNull(result)
        assertEquals(1, result.candidates.size)
        assertEquals(ContinuationMode.ENGINE, result.requestedMode)
        assertEquals("ENGINE", result.effectiveProvider)

        verify(engineMoveProvider).getContinuationCandidates(fen)
        verifyNoInteractions(humanMoveProvider)
    }
}
