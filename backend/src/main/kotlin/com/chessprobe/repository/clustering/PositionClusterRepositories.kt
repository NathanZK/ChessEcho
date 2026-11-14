package com.chessprobe.repository.clustering

import com.chessprobe.domain.clustering.ClusterType
import com.chessprobe.domain.clustering.PositionCluster
import com.chessprobe.domain.clustering.PositionClusterAssignment
import com.chessprobe.domain.clustering.PositionClusterAssignmentId
import com.chessprobe.domain.clustering.PositionClusterAssignmentStatus
import com.chessprobe.domain.clustering.PositionClusterSnapshot
import com.chessprobe.domain.clustering.PositionClusterSnapshotStatus
import com.chessprobe.domain.clustering.PositionContinuationArtifact
import com.chessprobe.domain.clustering.PositionContinuationStatus
import com.chessprobe.domain.clustering.TacticalResolutionStatus
import com.chessprobe.service.continuation.TerminationReason
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface PositionClusterSnapshotRepository : JpaRepository<PositionClusterSnapshot, UUID> {
    fun findByAccountIdAndClusterTypeAndStatus(
        accountId: UUID,
        clusterType: ClusterType,
        status: PositionClusterSnapshotStatus,
    ): PositionClusterSnapshot?

    fun findByAccountIdAndStatus(
        accountId: UUID,
        status: PositionClusterSnapshotStatus,
    ): PositionClusterSnapshot? = findByAccountIdAndClusterTypeAndStatus(accountId, ClusterType.STRUCTURAL, status)

    @Query(
        "SELECT COALESCE(MAX(s.snapshotVersion), 0) FROM PositionClusterSnapshot s " +
            "WHERE s.accountId = :accountId AND s.clusterType = :clusterType",
    )
    fun findMaxVersionByAccountIdAndClusterType(
        @Param("accountId") accountId: UUID,
        @Param("clusterType") clusterType: ClusterType,
    ): Int

    fun findByAccountIdAndClusterTypeOrderBySnapshotVersionDesc(
        accountId: UUID,
        clusterType: ClusterType,
    ): List<PositionClusterSnapshot>

    @Modifying
    @Query(
        "UPDATE PositionClusterSnapshot s SET s.status = :newStatus " +
            "WHERE s.accountId = :accountId AND s.clusterType = :clusterType AND s.status = :oldStatus " +
            "AND s.snapshotVersion < :currentVersion",
    )
    fun updateStatusForAccountAndClusterType(
        @Param("accountId") accountId: UUID,
        @Param("clusterType") clusterType: ClusterType,
        @Param("oldStatus") oldStatus: PositionClusterSnapshotStatus,
        @Param("newStatus") newStatus: PositionClusterSnapshotStatus,
        @Param("currentVersion") currentVersion: Int,
    ): Int
}

interface PositionClusterRepository : JpaRepository<PositionCluster, UUID> {
    fun findBySnapshotIdOrderByClusterIndexAsc(snapshotId: UUID): List<PositionCluster>
}

interface PositionClusterAssignmentRepository : JpaRepository<PositionClusterAssignment, PositionClusterAssignmentId> {
    fun findByIdSnapshotId(snapshotId: UUID): List<PositionClusterAssignment>

    fun findByClusterId(clusterId: UUID): List<PositionClusterAssignment>

    fun findByIdSnapshotIdAndAssignmentStatusIn(
        snapshotId: UUID,
        statuses: Collection<PositionClusterAssignmentStatus>,
    ): List<PositionClusterAssignment>
}

interface PositionContinuationArtifactRepository : JpaRepository<PositionContinuationArtifact, UUID> {
    fun findByPositionIdAndConfigHash(
        positionId: UUID,
        configHash: String,
    ): PositionContinuationArtifact?

    fun findByPositionIdInAndConfigHash(
        positionIds: Collection<UUID>,
        configHash: String,
    ): List<PositionContinuationArtifact>

    @Modifying
    @Query(
        value =
            "INSERT INTO position_continuation_artifact " +
                "(id, position_id, config_hash, status, lease_token, lease_expires_at, retry_count, created_at, updated_at) " +
                "VALUES (:id, :positionId, :configHash, 'PENDING', :leaseToken, :leaseExpiresAt, 0, :now, :now) " +
                "ON CONFLICT (position_id, config_hash) DO NOTHING",
        nativeQuery = true,
    )
    fun insertPendingIfAbsent(
        @Param("id") id: UUID,
        @Param("positionId") positionId: UUID,
        @Param("configHash") configHash: String,
        @Param("leaseToken") leaseToken: String,
        @Param("leaseExpiresAt") leaseExpiresAt: Instant,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Query(
        value =
            "UPDATE position_continuation_artifact " +
                "SET status = 'PENDING', " +
                "    lease_token = :leaseToken, " +
                "    lease_expires_at = :leaseExpiresAt, " +
                "    retry_count = retry_count + 1, " +
                "    updated_at = :now " +
                "WHERE position_id = :positionId " +
                "  AND config_hash = :configHash " +
                "  AND ((status = 'PENDING' AND lease_expires_at < :now) OR status = 'FAILED') " +
                "  AND retry_count < :maxRetries",
        nativeQuery = true,
    )
    fun reclaimStaleOrFailed(
        @Param("positionId") positionId: UUID,
        @Param("configHash") configHash: String,
        @Param("leaseToken") leaseToken: String,
        @Param("leaseExpiresAt") leaseExpiresAt: Instant,
        @Param("maxRetries") maxRetries: Int,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Query(
        "UPDATE PositionContinuationArtifact a " +
            "SET a.status = :status, " +
            "    a.resolutionStatus = :resolutionStatus, " +
            "    a.achievedDepth = :achievedDepth, " +
            "    a.nominalMoves = :nominalMoves, " +
            "    a.continuationMoves = :continuationMoves, " +
            "    a.resolvedMoves = :resolvedMoves, " +
            "    a.terminalFen = :terminalFen, " +
            "    a.terminationReason = :terminationReason, " +
            "    a.leaseToken = NULL, " +
            "    a.leaseExpiresAt = NULL, " +
            "    a.updatedAt = :now " +
            "WHERE a.id = :id AND a.leaseToken = :leaseToken",
    )
    fun completeLeasedArtifact(
        @Param("id") id: UUID,
        @Param("leaseToken") leaseToken: String,
        @Param("status") status: PositionContinuationStatus,
        @Param("resolutionStatus") resolutionStatus: TacticalResolutionStatus?,
        @Param("achievedDepth") achievedDepth: Int?,
        @Param("nominalMoves") nominalMoves: String?,
        @Param("continuationMoves") continuationMoves: String?,
        @Param("resolvedMoves") resolvedMoves: String?,
        @Param("terminalFen") terminalFen: String?,
        @Param("terminationReason") terminationReason: TerminationReason?,
        @Param("now") now: Instant,
    ): Int

    @Modifying
    @Query(
        "UPDATE PositionContinuationArtifact a " +
            "SET a.status = :status, " +
            "    a.resolutionStatus = :resolutionStatus, " +
            "    a.errorMessage = :errorMessage, " +
            "    a.leaseToken = NULL, " +
            "    a.leaseExpiresAt = NULL, " +
            "    a.updatedAt = :now " +
            "WHERE a.id = :id AND a.leaseToken = :leaseToken",
    )
    fun failLeasedArtifact(
        @Param("id") id: UUID,
        @Param("leaseToken") leaseToken: String,
        @Param("status") status: PositionContinuationStatus,
        @Param("resolutionStatus") resolutionStatus: TacticalResolutionStatus,
        @Param("errorMessage") errorMessage: String?,
        @Param("now") now: Instant,
    ): Int
}
