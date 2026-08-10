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

    @Query(
        """
        SELECT po FROM PositionOccurrence po
        WHERE po.chessAccount.id = :chessAccountId
          AND (:playerColor = 'BOTH' OR po.playerColor = :playerColor)
          AND po.position.id IN :positionIds
        """,
    )
    fun findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
        @Param("chessAccountId") chessAccountId: UUID,
        @Param("playerColor") playerColor: String,
        @Param("positionIds") positionIds: Collection<UUID>,
    ): List<PositionOccurrence>

    @Query("SELECT COUNT(po) FROM PositionOccurrence po WHERE po.chessAccount.id = :chessAccountId")
    fun countByChessAccountId(
        @Param("chessAccountId") chessAccountId: UUID,
    ): Long

    @Query(
        """
        SELECT COUNT(po) FROM PositionOccurrence po
        WHERE po.chessAccount.id = :chessAccountId
          AND (:playerColor = 'BOTH' OR po.playerColor = :playerColor)
        """,
    )
    fun countByChessAccountIdAndPlayerColorOrBoth(
        @Param("chessAccountId") chessAccountId: UUID,
        @Param("playerColor") playerColor: String,
    ): Long

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
     * Dynamically aggregates position weaknesses in the database for a specific player and color using a dynamic evaluation loss threshold.
     */
    @Query(
        """
        SELECT new com.chessecho.repository.WeaknessAggregation(
            p.id,
            p.fen,
            CAST(COUNT(po.id) AS int),
            ea.bestMove,
            ea.baselineEvalCp,
            SUM(CASE WHEN COALESCE(me.evalLossFromBest, CASE WHEN (ea.bestMoveEvalCp IS NOT NULL AND me.evalCp IS NOT NULL AND (ea.bestMoveEvalCp - me.evalCp) > 0) THEN (ea.bestMoveEvalCp - me.evalCp) / 100.0 ELSE 0.0 END) >= :minEvalLoss THEN 1 ELSE 0 END),
            AVG(CASE WHEN COALESCE(me.evalLossFromBest, CASE WHEN (ea.bestMoveEvalCp IS NOT NULL AND me.evalCp IS NOT NULL AND (ea.bestMoveEvalCp - me.evalCp) > 0) THEN (ea.bestMoveEvalCp - me.evalCp) / 100.0 ELSE NULL END) >= :minEvalLoss THEN COALESCE(me.evalLossFromBest, CASE WHEN (ea.bestMoveEvalCp IS NOT NULL AND me.evalCp IS NOT NULL AND (ea.bestMoveEvalCp - me.evalCp) > 0) THEN (ea.bestMoveEvalCp - me.evalCp) / 100.0 ELSE 0.0 END) ELSE NULL END),
            SUM(CASE WHEN COALESCE(me.evalLossFromBest, CASE WHEN (ea.bestMoveEvalCp IS NOT NULL AND me.evalCp IS NOT NULL AND (ea.bestMoveEvalCp - me.evalCp) > 0) THEN (ea.bestMoveEvalCp - me.evalCp) / 100.0 ELSE 0.0 END) >= :minEvalLoss THEN COALESCE(me.evalLossFromBest, CASE WHEN (ea.bestMoveEvalCp IS NOT NULL AND me.evalCp IS NOT NULL AND (ea.bestMoveEvalCp - me.evalCp) > 0) THEN (ea.bestMoveEvalCp - me.evalCp) / 100.0 ELSE 0.0 END) ELSE 0.0 END)
        )
        FROM PositionOccurrence po
        JOIN po.position p
        JOIN EngineAnalysis ea ON ea.position.id = p.id
        JOIN MoveEvaluation me ON me.engineAnalysis.id = ea.id AND me.move = po.movePlayed
        WHERE po.chessAccount.id = :chessAccountId
          AND (:playerColor = 'BOTH' OR po.playerColor = :playerColor)
        GROUP BY p.id, p.fen, ea.bestMove, ea.baselineEvalCp
        HAVING COUNT(po.id) >= :minTimesReached
           AND SUM(CASE WHEN COALESCE(me.evalLossFromBest, CASE WHEN (ea.bestMoveEvalCp IS NOT NULL AND me.evalCp IS NOT NULL AND (ea.bestMoveEvalCp - me.evalCp) > 0) THEN (ea.bestMoveEvalCp - me.evalCp) / 100.0 ELSE 0.0 END) >= :minEvalLoss THEN 1 ELSE 0 END) >= :minMistakeCount
        """,
    )
    fun findWeaknessAggregations(
        @Param("chessAccountId") chessAccountId: UUID,
        @Param("playerColor") playerColor: String,
        @Param("minEvalLoss") minEvalLoss: Double,
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
