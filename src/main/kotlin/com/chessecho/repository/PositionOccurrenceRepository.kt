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

    fun findByChessAccountIdAndPlayerColorAndPositionIdIn(
        chessAccountId: UUID,
        playerColor: String,
        positionIds: Collection<UUID>,
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

    /**
     * Dynamically aggregates position weaknesses in the database for a specific player and color using a dynamic mistake threshold.
     */
    @Query(
        """
        SELECT new com.chessecho.repository.WeaknessAggregation(
            p.id,
            p.fen,
            ups.timesReached,
            ea.bestMove,
            ea.baselineEvalCp,
            SUM(CASE WHEN me.evalLossFromBest >= :mistakeThreshold THEN 1 ELSE 0 END),
            AVG(CASE WHEN me.evalLossFromBest >= :mistakeThreshold THEN me.evalLossFromBest ELSE NULL END),
            SUM(CASE WHEN me.evalLossFromBest >= :mistakeThreshold THEN me.evalLossFromBest ELSE 0.0 END)
        )
        FROM UserPositionStats ups
        JOIN ups.position p
        JOIN EngineAnalysis ea ON ea.position.id = p.id
        JOIN PositionOccurrence po ON po.position.id = p.id AND po.chessAccount.id = ups.chessAccount.id AND po.playerColor = ups.playerColor
        JOIN MoveEvaluation me ON me.engineAnalysis.id = ea.id AND me.move = po.movePlayed
        WHERE ups.chessAccount.id = :chessAccountId
          AND ups.playerColor = :playerColor
          AND ups.timesReached >= :minTimesReached
        GROUP BY p.id, p.fen, ups.timesReached, ea.bestMove, ea.baselineEvalCp
        HAVING SUM(CASE WHEN me.evalLossFromBest >= :mistakeThreshold THEN 1 ELSE 0 END) >= :minMistakeCount
        """,
    )
    fun findWeaknessAggregations(
        @Param("chessAccountId") chessAccountId: UUID,
        @Param("playerColor") playerColor: String,
        @Param("mistakeThreshold") mistakeThreshold: Double,
        @Param("minTimesReached") minTimesReached: Int,
        @Param("minMistakeCount") minMistakeCount: Long,
    ): List<WeaknessAggregation>
}

data class PositionOccurrenceCount(
    val positionId: UUID,
    val playerColor: String,
    val timesReached: Long,
)

data class WeaknessAggregation(
    val positionId: UUID,
    val fen: String,
    val timesReached: Int,
    val bestMove: String?,
    val baselineEvalCp: Int?,
    val mistakeCount: Long,
    val averageLoss: Double?,
    val rawTotalLoss: Double?,
)
