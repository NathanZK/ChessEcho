package com.chessecho.repository

import com.chessecho.domain.AccountConnection
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AccountConnectionRepository : JpaRepository<AccountConnection, UUID> {
    fun findByAppUserId(appUserId: UUID): AccountConnection?

    fun existsByAppUserIdAndChessAccountId(
        appUserId: UUID,
        chessAccountId: UUID,
    ): Boolean

    fun existsByChessAccountId(chessAccountId: UUID): Boolean
}
