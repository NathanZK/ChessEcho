package com.chessecho.repository

import com.chessecho.domain.ChessAccount
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT ca FROM ChessAccount ca " +
            "WHERE LOWER(ca.platform) = LOWER(:platform) " +
            "AND LOWER(ca.username) = LOWER(:username)",
    )
    fun findByPlatformAndUsernameForUpdate(
        @Param("platform") platform: String,
        @Param("username") username: String,
    ): ChessAccount?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT ca FROM ChessAccount ca WHERE ca.id = :id",
    )
    fun findByIdForUpdate(
        @Param("id") id: UUID,
    ): ChessAccount?

    fun findAllByUserIdOrderByCreatedAtAsc(userId: UUID): List<ChessAccount>

    fun findByIdAndUserId(
        id: UUID,
        userId: UUID,
    ): ChessAccount?

    fun findByIdAndUserIdIsNull(id: UUID): ChessAccount?

    @Query(
        "SELECT ca FROM ChessAccount ca " +
            "WHERE LOWER(ca.platform) = LOWER(:platform) " +
            "AND LOWER(ca.username) = LOWER(:username) " +
            "AND ca.user IS NULL",
    )
    fun findUnclaimedByPlatformAndUsername(
        @Param("platform") platform: String,
        @Param("username") username: String,
    ): ChessAccount?
}
