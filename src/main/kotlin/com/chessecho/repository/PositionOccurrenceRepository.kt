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

    /**
     * Counts occurrence statistics for a given account across a set of position IDs.
     */
    @Query(
        """
        SELECT new com.chessecho.repository.PositionOccurrenceCount(po.position.id, po.playerColor, COUNT(po.id))
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

    /**
     * Finds distinct SAN moves played historically from a specific position ID.
     */
    @Query("SELECT DISTINCT po.movePlayed FROM PositionOccurrence po WHERE po.position.id = :positionId")
    fun findDistinctMovesByPositionId(
        @Param("positionId") positionId: UUID,
    ): List<String>
}

data class PositionOccurrenceCount(
    val positionId: UUID,
    val playerColor: String,
    val timesReached: Long,
)
