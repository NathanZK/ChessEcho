package com.chessecho.repository

import com.chessecho.domain.PositionOccurrence
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface PositionOccurrenceRepository : JpaRepository<PositionOccurrence, UUID> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            INSERT INTO position_occurrence
              (id, game_id, position_id, chess_account_id, ply_number, move_played, player_color, decision_time_ms, created_at)
            VALUES
              (:id, :gameId, :positionId, :chessAccountId, :plyNumber, :movePlayed, :playerColor, :decisionTimeMs, :createdAt)
            ON CONFLICT (game_id, position_id, ply_number, player_color)
            DO UPDATE SET decision_time_ms = EXCLUDED.decision_time_ms
        """,
        nativeQuery = true,
    )
    fun insertOrRefreshDecisionTime(
        @Param("id") id: UUID,
        @Param("gameId") gameId: UUID,
        @Param("positionId") positionId: UUID,
        @Param("chessAccountId") chessAccountId: UUID,
        @Param("plyNumber") plyNumber: Int,
        @Param("movePlayed") movePlayed: String,
        @Param("playerColor") playerColor: String,
        @Param("decisionTimeMs") decisionTimeMs: Long?,
        @Param("createdAt") createdAt: Instant,
    ): Int

    fun findByGameIdIn(gameIds: Collection<UUID>): List<PositionOccurrence>

    @Query(
        value = """
            SELECT po FROM PositionOccurrence po
            JOIN FETCH po.game g
            JOIN FETCH po.position p
            JOIN FETCH po.chessAccount a
            WHERE po.chessAccount.id = :chessAccountId
              AND UPPER(g.timeControl) = :timeControl
              AND po.decisionTimeMs IS NOT NULL
              AND po.decisionTimeMs >= :thresholdMs
            ORDER BY CASE WHEN g.playedAt IS NULL THEN 1 ELSE 0 END ASC,
                     g.playedAt DESC,
                     po.plyNumber ASC,
                     po.id ASC
        """,
        countQuery = """
            SELECT COUNT(po) FROM PositionOccurrence po
            JOIN po.game g
            WHERE po.chessAccount.id = :chessAccountId
              AND UPPER(g.timeControl) = :timeControl
              AND po.decisionTimeMs IS NOT NULL
              AND po.decisionTimeMs >= :thresholdMs
        """,
    )
    fun findLongDecisionOccurrences(
        @Param("chessAccountId") chessAccountId: UUID,
        @Param("timeControl") timeControl: String,
        @Param("thresholdMs") thresholdMs: Long,
        pageable: Pageable,
    ): Page<PositionOccurrence>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE PositionOccurrence po
        SET po.decisionTimeMs = :decisionTimeMs
        WHERE po.game.id = :gameId
          AND po.position.id = :positionId
          AND po.plyNumber = :plyNumber
          AND po.playerColor = :playerColor
        """,
    )
    fun updateDecisionTime(
        @Param("gameId") gameId: UUID,
        @Param("positionId") positionId: UUID,
        @Param("plyNumber") plyNumber: Int,
        @Param("playerColor") playerColor: String,
        @Param("decisionTimeMs") decisionTimeMs: Long?,
    ): Int

    fun findByPositionId(positionId: UUID): List<PositionOccurrence>

    @Query(
        """
        SELECT DISTINCT po FROM PositionOccurrence po
        JOIN FETCH po.game
        JOIN FETCH po.position
        JOIN FETCH po.chessAccount
        WHERE po.id IN :ids
        """,
    )
    fun findSelectedWithOperationalFacts(
        @Param("ids") ids: Set<UUID>,
    ): List<PositionOccurrence>

    @Query(
        """
        SELECT po FROM PositionOccurrence po
        JOIN FETCH po.game
        WHERE po.chessAccount.id = :chessAccountId
          AND po.position.id = :positionId
          AND po.playerColor = :playerColor
        ORDER BY COALESCE(po.game.playedAt, po.createdAt) ASC, po.createdAt ASC, po.id ASC
        """,
    )
    fun findProgressOccurrences(
        @Param("chessAccountId") chessAccountId: UUID,
        @Param("positionId") positionId: UUID,
        @Param("playerColor") playerColor: String,
    ): List<PositionOccurrence>

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
        JOIN FETCH po.game
        JOIN FETCH po.position
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
     * Finds historical moves played from a specific position ID ordered by total play frequency descending.
     */
    @Query(
        """
        SELECT new com.chessecho.repository.HistoricalMoveStats(po.movePlayed, COUNT(po.id))
        FROM PositionOccurrence po
        WHERE po.position.id = :positionId
        GROUP BY po.movePlayed
        ORDER BY COUNT(po.id) DESC
        """,
    )
    fun findHistoricalMoveStatsByPositionId(
        @Param("positionId") positionId: UUID,
    ): List<HistoricalMoveStats>

    /**
     * Dynamically aggregates position weaknesses in the database for a specific player and color using a dynamic evaluation loss threshold.
     */
    @Query(
        """
        SELECT new com.chessecho.repository.WeaknessAggregation(
            p.id,
            p.fen,
            po.playerColor,
            CAST(COUNT(po.id) AS int),
            ea.bestMove,
            ea.baselineEvalCp,
            SUM(CASE WHEN (COALESCE(me.evalLossFromBest, CASE WHEN (ea.bestMoveEvalCp IS NOT NULL AND me.evalCp IS NOT NULL AND (ea.bestMoveEvalCp - me.evalCp) > 0) THEN (ea.bestMoveEvalCp - me.evalCp) / 100.0 ELSE 0.0 END) >= :minEvalLoss) THEN 1 ELSE 0 END),
            AVG(CASE WHEN (COALESCE(me.evalLossFromBest, CASE WHEN (ea.bestMoveEvalCp IS NOT NULL AND me.evalCp IS NOT NULL AND (ea.bestMoveEvalCp - me.evalCp) > 0) THEN (ea.bestMoveEvalCp - me.evalCp) / 100.0 ELSE NULL END) >= :minEvalLoss) THEN COALESCE(me.evalLossFromBest, CASE WHEN (ea.bestMoveEvalCp IS NOT NULL AND me.evalCp IS NOT NULL AND (ea.bestMoveEvalCp - me.evalCp) > 0) THEN (ea.bestMoveEvalCp - me.evalCp) / 100.0 ELSE 0.0 END) ELSE NULL END),
            SUM(CASE WHEN (COALESCE(me.evalLossFromBest, CASE WHEN (ea.bestMoveEvalCp IS NOT NULL AND me.evalCp IS NOT NULL AND (ea.bestMoveEvalCp - me.evalCp) > 0) THEN (ea.bestMoveEvalCp - me.evalCp) / 100.0 ELSE 0.0 END) >= :minEvalLoss) THEN COALESCE(me.evalLossFromBest, CASE WHEN (ea.bestMoveEvalCp IS NOT NULL AND me.evalCp IS NOT NULL AND (ea.bestMoveEvalCp - me.evalCp) > 0) THEN (ea.bestMoveEvalCp - me.evalCp) / 100.0 ELSE 0.0 END) ELSE 0.0 END)
        )
        FROM PositionOccurrence po
        JOIN po.position p
        JOIN EngineAnalysis ea ON ea.position.id = p.id
        JOIN MoveEvaluation me ON me.engineAnalysis.id = ea.id AND me.move = po.movePlayed
        WHERE po.chessAccount.id = :chessAccountId
          AND (:playerColor = 'BOTH' OR po.playerColor = :playerColor)
        GROUP BY p.id, p.fen, po.playerColor, ea.bestMove, ea.baselineEvalCp
        HAVING COUNT(po.id) >= :minTimesReached
           AND SUM(CASE WHEN (COALESCE(me.evalLossFromBest, CASE WHEN (ea.bestMoveEvalCp IS NOT NULL AND me.evalCp IS NOT NULL AND (ea.bestMoveEvalCp - me.evalCp) > 0) THEN (ea.bestMoveEvalCp - me.evalCp) / 100.0 ELSE 0.0 END) >= :minEvalLoss) THEN 1 ELSE 0 END) >= :minMistakeCount
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

data class HistoricalMoveStats(
    val movePlayed: String,
    val timesPlayed: Long,
)

data class WeaknessAggregation(
    val positionId: UUID,
    val fen: String,
    val playerColor: String,
    val timesReached: Int,
    val bestMove: String?,
    val baselineEvalCp: Int?,
    val mistakeCount: Long,
    val averageLoss: Double?,
    val rawTotalLoss: Double?,
)
