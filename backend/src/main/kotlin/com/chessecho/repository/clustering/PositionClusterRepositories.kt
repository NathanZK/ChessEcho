package com.chessecho.repository.clustering

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
    fun findByAccountIdAndStatus(
        accountId: UUID,
        status: PositionClusterSnapshotStatus,
    ): PositionClusterSnapshot?

    @Query("SELECT COALESCE(MAX(s.snapshotVersion), 0) FROM PositionClusterSnapshot s WHERE s.accountId = :accountId")
    fun findMaxVersionByAccountId(
        @Param("accountId") accountId: UUID,
    ): Int

    fun findByAccountIdOrderBySnapshotVersionDesc(accountId: UUID): List<PositionClusterSnapshot>

    @Modifying
    @Query(
        "UPDATE PositionClusterSnapshot s SET s.status = :newStatus " +
            "WHERE s.accountId = :accountId AND s.status = :oldStatus",
    )
    fun updateStatusForAccount(
        @Param("accountId") accountId: UUID,
        @Param("oldStatus") oldStatus: PositionClusterSnapshotStatus,
        @Param("newStatus") newStatus: PositionClusterSnapshotStatus,
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
