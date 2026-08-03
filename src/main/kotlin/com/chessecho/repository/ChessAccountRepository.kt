package com.chessecho.repository

import com.chessecho.domain.ChessAccount
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ChessAccountRepository : JpaRepository<ChessAccount, UUID> {
    fun findByPlatformAndUsernameIgnoreCase(
        platform: String,
        username: String,
    ): ChessAccount?
}
