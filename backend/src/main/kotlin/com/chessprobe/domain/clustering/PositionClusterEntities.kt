package com.chessprobe.domain.clustering

import com.chessprobe.service.continuation.TerminationReason
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant
import java.util.UUID

enum class ClusterType {
    STRUCTURAL,
    DYNAMIC,
}

enum class PositionClusterSnapshotStatus {
    GENERATING,
    ACTIVE,
    SUPERSEDED,
    FAILED,
    EXPERIMENTAL,
}

enum class PositionClusterAssignmentStatus {
    ASSIGNED,
    OUTLIER_EVICTED,
    ISOLATE,
}

@Entity
@Table(
    name = "position_cluster_snapshot",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_position_cluster_snapshot_account_type_version",
            columnNames = ["account_id", "cluster_type", "snapshot_version"],
        ),
    ],
)
class PositionClusterSnapshot(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "account_id", nullable = false)
    val accountId: UUID,
    @Column(name = "snapshot_version", nullable = false)
    val snapshotVersion: Int,
    @Enumerated(EnumType.STRING)
    @Column(name = "cluster_type", nullable = false)
    val clusterType: ClusterType = ClusterType.STRUCTURAL,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: PositionClusterSnapshotStatus = PositionClusterSnapshotStatus.GENERATING,
    @Column(name = "alpha", nullable = false)
    val alpha: Double = 0.60,
    @Column(name = "top_k", nullable = false)
    val topK: Int = 30,
    @Column(name = "floor_threshold", nullable = false)
    val floorThreshold: Double = 0.40,
    @Column(name = "top1_threshold", nullable = false)
    val top1Threshold: Double = 0.65,
    @Column(name = "resolution", nullable = false)
    val resolution: Double = 1.25,
    @Column(name = "centroid_threshold", nullable = false)
    val centroidThreshold: Double = 0.38,
    @Column(name = "total_positions", nullable = false)
    var totalPositions: Int = 0,
    @Column(name = "cluster_count", nullable = false)
    var clusterCount: Int = 0,
    @Column(name = "unassigned_count", nullable = false)
    var unassignedCount: Int = 0,
    @Column(name = "analyzed_position_count", nullable = false)
    var analyzedPositionCount: Int = 0,
    @Column(name = "eligible_position_count", nullable = false)
    var eligiblePositionCount: Int = 0,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "completed_at")
    var completedAt: Instant? = null,
    @Column(name = "error_message")
    var errorMessage: String? = null,
)

@Entity
@Table(
    name = "position_cluster",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_position_cluster_snapshot_index",
            columnNames = ["snapshot_id", "cluster_index"],
        ),
    ],
)
class PositionCluster(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "snapshot_id", nullable = false)
    val snapshotId: UUID,
    @Column(name = "cluster_index", nullable = false)
    val clusterIndex: Int,
    @Column(name = "size", nullable = false)
    val size: Int,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "centroid_vector", nullable = false)
    val centroidVector: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)

@Embeddable
data class PositionClusterAssignmentId(
    @Column(name = "snapshot_id", nullable = false)
    val snapshotId: UUID = UUID.randomUUID(),
    @Column(name = "position_id", nullable = false)
    val positionId: UUID = UUID.randomUUID(),
) : Serializable

@Entity
@Table(name = "position_cluster_assignment")
class PositionClusterAssignment(
    @EmbeddedId
    val id: PositionClusterAssignmentId,
    @Column(name = "cluster_id")
    val clusterId: UUID? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_status", nullable = false)
    val assignmentStatus: PositionClusterAssignmentStatus,
    @Column(name = "isolation_reason")
    val isolationReason: String? = null,
    @Column(name = "centroid_similarity")
    val centroidSimilarity: Double? = null,
) {
    val snapshotId: UUID get() = id.snapshotId
    val positionId: UUID get() = id.positionId
}

enum class PositionContinuationStatus {
    PENDING,
    COMPLETED,
    FAILED,
}

enum class TacticalResolutionStatus {
    RESOLVED_QUIET,
    RESOLVED_TERMINAL,
    INCOMPLETE_LIMIT,
    OPERATIONAL_FAILURE,
}

@Entity
@Table(
    name = "position_continuation_artifact",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_position_continuation_config",
            columnNames = ["position_id", "config_hash"],
        ),
    ],
)
class PositionContinuationArtifact(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "position_id", nullable = false)
    val positionId: UUID,
    @Column(name = "config_hash", nullable = false, length = 64)
    val configHash: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: PositionContinuationStatus = PositionContinuationStatus.PENDING,
    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_status", length = 30)
    var resolutionStatus: TacticalResolutionStatus? = null,
    @Column(name = "achieved_depth")
    var achievedDepth: Int? = null,
    @Column(name = "nominal_moves", length = 255)
    var nominalMoves: String? = null,
    @Column(name = "continuation_moves", length = 255)
    var continuationMoves: String? = null,
    @Column(name = "resolved_moves", length = 512)
    var resolvedMoves: String? = null,
    @Column(name = "terminal_fen")
    var terminalFen: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "termination_reason", length = 30)
    var terminationReason: TerminationReason? = null,
    @Column(name = "lease_token", length = 64)
    var leaseToken: String? = null,
    @Column(name = "lease_expires_at")
    var leaseExpiresAt: Instant? = null,
    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,
    @Column(name = "error_message", length = 500)
    var errorMessage: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
