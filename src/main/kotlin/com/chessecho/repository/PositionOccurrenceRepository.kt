package com.chessecho.repository

import com.chessecho.domain.PositionOccurrence
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface PositionOccurrenceRepository : JpaRepository<PositionOccurrence, UUID> {
    fun findByPositionId(positionId: UUID): List<PositionOccurrence>

    fun findByChessAccountIdAndPlayerColor(
        chessAccountId: UUID,
        playerColor: String,
    ): List<PositionOccurrence>

    @Query(
        """
        SELECT po.position.id, po.playerColor, COUNT(*) as timesReached
        FROM PositionOccurrence po
        WHERE po.chessAccount.id = :chessAccountId
          AND po.position.id IN :positionIds
        GROUP BY po.position.id, po.playerColor
        """,
    )
    fun countOccurrencesByAccountAndPositions(
        @Param("chessAccountId") chessAccountId: UUID,
        @Param("positionIds") positionIds: Set<UUID>,
    ): List<PositionOccurrenceCount>
}

data class PositionOccurrenceCount(
    val positionId: UUID,
    val playerColor: String,
    val timesReached: Long,
)
