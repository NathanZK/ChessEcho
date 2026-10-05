package com.chessecho.repository

import com.chessecho.domain.AppUser
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface AppUserRepository : JpaRepository<AppUser, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AppUser u WHERE u.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: UUID,
    ): AppUser?

    fun findByEmail(email: String): AppUser?
}
