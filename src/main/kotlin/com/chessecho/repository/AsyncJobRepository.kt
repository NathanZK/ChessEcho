package com.chessecho.repository

import com.chessecho.domain.AsyncJob
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface AsyncJobRepository : JpaRepository<AsyncJob, UUID> {
    fun findByUsernameAndStatusIn(
        username: String,
        statuses: List<String>,
    ): AsyncJob?

    fun findByChessAccountIdAndStatusIn(
        chessAccountId: UUID,
        statuses: List<String>,
    ): AsyncJob?

    @Query(
        "SELECT j FROM AsyncJob j JOIN FETCH j.chessAccount " +
            "WHERE j.id = :id",
    )
    fun findByIdWithAccount(
        @Param("id") id: UUID,
    ): AsyncJob?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT j FROM AsyncJob j JOIN FETCH j.chessAccount WHERE j.id = :id",
    )
    fun findByIdForUpdate(
        @Param("id") id: UUID,
    ): AsyncJob?

    /**
     * Ids of PROCESSING jobs whose worker lease has lapsed, i.e. jobs left behind
     * by a crashed or killed worker. Selected without a lock so the recovery sweep
     * can lock each candidate individually and re-verify staleness.
     */
    @Query(
        "SELECT j.id FROM AsyncJob j " +
            "WHERE j.status = 'PROCESSING' AND (j.leaseExpiresAt IS NULL OR j.leaseExpiresAt < :now)",
    )
    fun findStaleProcessingJobIds(
        @Param("now") now: Instant,
    ): List<UUID>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM AsyncJob j WHERE j.id = :id")
    fun findAnyByIdForUpdate(
        @Param("id") id: UUID,
    ): AsyncJob?
}
