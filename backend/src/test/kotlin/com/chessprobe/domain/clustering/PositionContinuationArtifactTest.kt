package com.chessprobe.domain.clustering

import com.chessprobe.service.continuation.TerminationReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class PositionContinuationArtifactTest {
    @Test
    fun `default artifact is created in PENDING status with null completion fields`() {
        val positionId = UUID.randomUUID()
        val artifact =
            PositionContinuationArtifact(
                positionId = positionId,
                configHash = "sha256:abc12345",
            )

        assertEquals(PositionContinuationStatus.PENDING, artifact.status)
        assertNull(artifact.resolutionStatus)
        assertNull(artifact.achievedDepth)
        assertNull(artifact.nominalMoves)
        assertNull(artifact.continuationMoves)
        assertNull(artifact.resolvedMoves)
        assertNull(artifact.terminalFen)
        assertNull(artifact.terminationReason)
        assertNull(artifact.leaseToken)
        assertNull(artifact.leaseExpiresAt)
        assertEquals(0, artifact.retryCount)
    }

    @Test
    fun `completed artifact holds resolved quiet termination details`() {
        val positionId = UUID.randomUUID()
        val artifact =
            PositionContinuationArtifact(
                positionId = positionId,
                configHash = "sha256:test",
                status = PositionContinuationStatus.COMPLETED,
                resolutionStatus = TacticalResolutionStatus.RESOLVED_QUIET,
                achievedDepth = 10,
                nominalMoves = "e2e4 e7e5",
                continuationMoves = "g1f3 b8c6",
                resolvedMoves = "e2e4 e7e5 g1f3 b8c6",
                terminalFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
                terminationReason = TerminationReason.QUIET_STOP,
            )

        assertEquals(PositionContinuationStatus.COMPLETED, artifact.status)
        assertEquals(TacticalResolutionStatus.RESOLVED_QUIET, artifact.resolutionStatus)
        assertEquals(TerminationReason.QUIET_STOP, artifact.terminationReason)
        assertEquals("e2e4 e7e5 g1f3 b8c6", artifact.resolvedMoves)
    }

    @Test
    fun `assignment supports isolation reason for clustering telemetry`() {
        val snapshotId = UUID.randomUUID()
        val positionId = UUID.randomUUID()
        val assignment =
            PositionClusterAssignment(
                id = PositionClusterAssignmentId(snapshotId, positionId),
                assignmentStatus = PositionClusterAssignmentStatus.ISOLATE,
                isolationReason = "ZERO_TOKENS",
            )

        assertEquals(PositionClusterAssignmentStatus.ISOLATE, assignment.assignmentStatus)
        assertEquals("ZERO_TOKENS", assignment.isolationReason)
    }
}
