package com.chessecho.repository.clustering

import com.chessecho.domain.clustering.ClusterType
import com.chessecho.domain.clustering.PositionCluster
import com.chessecho.domain.clustering.PositionClusterAssignment
import com.chessecho.domain.clustering.PositionClusterAssignmentId
import com.chessecho.domain.clustering.PositionClusterAssignmentStatus
import com.chessecho.domain.clustering.PositionClusterSnapshot
import com.chessecho.domain.clustering.PositionClusterSnapshotStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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
