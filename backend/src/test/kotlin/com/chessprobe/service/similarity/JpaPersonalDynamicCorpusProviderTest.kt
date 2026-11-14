package com.chessprobe.service.similarity

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.Position
import com.chessprobe.domain.clustering.PositionContinuationArtifact
import com.chessprobe.domain.clustering.PositionContinuationStatus
import com.chessprobe.domain.clustering.TacticalResolutionStatus
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.repository.clustering.PositionContinuationArtifactRepository
import com.chessprobe.service.clustering.DynamicContinuationCoordinator
import com.chessprobe.service.continuation.TerminationReason
import com.chessprobe.similarity.DynamicTacticalScorer
import com.chessprobe.similarity.PositionAnalysisOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class JpaPersonalDynamicCorpusProviderTest {
    private val occurrenceRepository: PositionOccurrenceRepository = mock()
    private val artifactRepository: PositionContinuationArtifactRepository = mock()
    private val dynamicTacticalScorer: DynamicTacticalScorer = mock()
    private val configHash = DynamicContinuationCoordinator.computeConfigHash()

    private val provider =
        JpaPersonalDynamicCorpusProvider(
            occurrenceRepository = occurrenceRepository,
            artifactRepository = artifactRepository,
            dynamicTacticalScorer = dynamicTacticalScorer,
            configHash = configHash,
        )

    @Test
    fun `loadPersonalDynamicPositions returns empty list when account has no positions`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "test_player")
        whenever(occurrenceRepository.findDistinctPositionsByChessAccountId(account.id)).thenReturn(emptyList())

        val result = provider.loadPersonalDynamicPositions(account)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `loadPersonalDynamicPositions extracts tokens for resolved quiet artifacts and marks EVALUATED_WITH_TOKENS`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "test_player")
        val pos =
            Position(
                hash = "hash1",
                fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
            )

        whenever(occurrenceRepository.findDistinctPositionsByChessAccountId(account.id))
            .thenReturn(listOf(pos))

        val artifact =
            PositionContinuationArtifact(
                positionId = pos.id,
                configHash = configHash,
                status = PositionContinuationStatus.COMPLETED,
                resolutionStatus = TacticalResolutionStatus.RESOLVED_QUIET,
                achievedDepth = 10,
                nominalMoves = "e7e5",
                continuationMoves = "g1f3",
                resolvedMoves = "e7e5 g1f3",
                terminalFen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2",
                terminationReason = TerminationReason.QUIET_STOP,
            )

        whenever(artifactRepository.findByPositionIdInAndConfigHash(setOf(pos.id), configHash))
            .thenReturn(listOf(artifact))
        whenever(dynamicTacticalScorer.extractDynamicTokensFromResolved(eq(pos.fen), eq("e7e5 g1f3")))
            .thenReturn(mapOf("e7e5" to 1.0, "+check" to 1.0))

        val docs = provider.loadPersonalDynamicPositions(account)
        assertEquals(1, docs.size)

        val doc = docs[0]
        assertEquals(pos.id, doc.positionId)
        assertEquals("b", doc.sideToMove)
        assertEquals(PositionAnalysisOutcome.EVALUATED_WITH_TOKENS, doc.analysisOutcome)
        assertEquals(2, doc.terms.size)
        assertEquals(1.0, doc.terms["e7e5"])
    }

    @Test
    fun `loadPersonalDynamicPositions marks unanalyzed positions with UNANALYZED outcome`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "test_player")
        val pos =
            Position(
                hash = "hash2",
                fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
            )

        whenever(occurrenceRepository.findDistinctPositionsByChessAccountId(account.id))
            .thenReturn(listOf(pos))
        whenever(artifactRepository.findByPositionIdInAndConfigHash(setOf(pos.id), configHash))
            .thenReturn(emptyList())

        val docs = provider.loadPersonalDynamicPositions(account)
        assertEquals(1, docs.size)

        val doc = docs[0]
        assertEquals(pos.id, doc.positionId)
        assertEquals(PositionAnalysisOutcome.UNANALYZED, doc.analysisOutcome)
        assertTrue(doc.terms.isEmpty())
    }

    @Test
    fun `loadPersonalDynamicPositions marks incomplete line cutoffs with INCOMPLETE_RESOLUTION outcome`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "test_player")
        val pos =
            Position(
                hash = "hash3",
                fen = "3rkb1r/p2R1ppp/4qn2/1B2p1B1/4P3/1Q6/PPP2PPP/2K4R b k - 0 13",
            )

        val artifact =
            PositionContinuationArtifact(
                positionId = pos.id,
                configHash = configHash,
                status = PositionContinuationStatus.COMPLETED,
                resolutionStatus = TacticalResolutionStatus.INCOMPLETE_LIMIT,
                achievedDepth = 10,
                nominalMoves = "d8d7",
                continuationMoves = "b3e6",
                resolvedMoves = "d8d7 b3e6",
                terminationReason = TerminationReason.PLY_LIMIT,
            )

        whenever(occurrenceRepository.findDistinctPositionsByChessAccountId(account.id))
            .thenReturn(listOf(pos))
        whenever(artifactRepository.findByPositionIdInAndConfigHash(setOf(pos.id), configHash))
            .thenReturn(listOf(artifact))

        val docs = provider.loadPersonalDynamicPositions(account)
        assertEquals(1, docs.size)

        val doc = docs[0]
        assertEquals(PositionAnalysisOutcome.INCOMPLETE_RESOLUTION, doc.analysisOutcome)
        assertTrue(doc.terms.isEmpty())
    }

    @Test
    fun `loadPersonalDynamicPositions safely rejects incompatible engine config hashes and marks UNANALYZED`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "test_player")
        val pos = Position(hash = "hash-incompat", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")

        whenever(occurrenceRepository.findDistinctPositionsByChessAccountId(account.id))
            .thenReturn(listOf(pos))

        // Repository returns empty list for active configHash even if older artifact exists for different hash
        whenever(artifactRepository.findByPositionIdInAndConfigHash(setOf(pos.id), configHash))
            .thenReturn(emptyList())

        val docs = provider.loadPersonalDynamicPositions(account)
        assertEquals(1, docs.size)
        assertEquals(pos.id, docs[0].positionId)
        assertEquals(PositionAnalysisOutcome.UNANALYZED, docs[0].analysisOutcome)
        assertTrue(docs[0].terms.isEmpty())
    }
}
