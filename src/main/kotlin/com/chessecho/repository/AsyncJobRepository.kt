package com.chessecho.repository

import com.chessecho.domain.AsyncJob
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM AsyncJob j WHERE j.id = :id")
    fun findAnyByIdForUpdate(
        @Param("id") id: UUID,
    ): AsyncJob?
}
