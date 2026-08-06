package com.chessecho.repository

import com.chessecho.domain.ChessAccount
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ChessAccountRepository : JpaRepository<ChessAccount, UUID> {
    @Query(
        "SELECT ca FROM ChessAccount ca " +
            "WHERE LOWER(ca.platform) = LOWER(:platform) " +
            "AND LOWER(ca.username) = LOWER(:username) " +
            "ORDER BY ca.createdAt DESC",
    )
    fun findByPlatformAndUsernameAll(
        @Param("platform") platform: String,
        @Param("username") username: String,
    ): List<ChessAccount>

    fun findByPlatformAndUsernameIgnoreCase(
        platform: String,
        username: String,
    ): ChessAccount? {
        return findByPlatformAndUsernameAll(platform, username).firstOrNull()
    }
}
