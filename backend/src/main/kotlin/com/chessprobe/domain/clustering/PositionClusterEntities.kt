package com.chessprobe.domain.clustering

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
    @Column(name = "centroid_similarity")
    val centroidSimilarity: Double? = null,
) {
    val snapshotId: UUID get() = id.snapshotId
    val positionId: UUID get() = id.positionId
}
