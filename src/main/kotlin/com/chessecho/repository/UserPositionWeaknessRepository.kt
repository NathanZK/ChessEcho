package com.chessecho.repository

import com.chessecho.domain.UserPositionWeakness
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface UserPositionWeaknessRepository : JpaRepository<UserPositionWeakness, UUID> {
    fun findByChessAccountIdAndPlayerColorAndMistakeCountGreaterThanEqualOrderByPriorityDesc(
        chessAccountId: UUID,
        playerColor: String,
        mistakeCount: Int,
    ): List<UserPositionWeakness>

    fun findByChessAccountIdAndPositionIdAndPlayerColor(
        chessAccountId: UUID,
        positionId: UUID,
        playerColor: String,
    ): UserPositionWeakness?

    @Query(
        """
        SELECT upw FROM UserPositionWeakness upw
        WHERE upw.chessAccount.id = :chessAccountId
        AND upw.position.id = :positionId
        AND upw.playerColor = :playerColor
        """,
    )
    fun findByAccountPositionAndColor(
        chessAccountId: UUID,
        positionId: UUID,
        playerColor: String,
    ): UserPositionWeakness?
}
