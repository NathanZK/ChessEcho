package com.chessecho.repository

import com.chessecho.domain.UserPositionStats
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserPositionStatsRepository : JpaRepository<UserPositionStats, UUID> {
    fun findByChessAccountIdAndPositionIdAndPlayerColor(
        chessAccountId: UUID,
        positionId: UUID,
        playerColor: String,
    ): UserPositionStats?

    fun findByChessAccountIdAndPlayerColor(
        chessAccountId: UUID,
        playerColor: String,
    ): List<UserPositionStats>

    fun findByChessAccountIdAndPlayerColorAndTimesReachedGreaterThanEqual(
        chessAccountId: UUID,
        playerColor: String,
        minTimesReached: Int,
    ): List<UserPositionStats>
}
