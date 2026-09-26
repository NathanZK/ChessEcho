package com.chessecho.repository

import com.chessecho.domain.EngineAnalysis
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface EngineAnalysisRepository : JpaRepository<EngineAnalysis, UUID> {
    /**
     * Finds the engine analysis record for a given position ID.
     */
    fun findByPositionId(positionId: UUID): EngineAnalysis?

    /**
     * Finds the engine analysis record along with its moveEvaluations in a single query.
     */
    @Query("SELECT e FROM EngineAnalysis e LEFT JOIN FETCH e.moveEvaluations WHERE e.position.id = :positionId")
    fun findByPositionIdWithMoveEvaluations(
        @Param("positionId") positionId: UUID,
    ): EngineAnalysis?

    /**
     * Batch finds engine analysis records along with their moveEvaluations for a set of position IDs.
     */
    @Query("SELECT DISTINCT e FROM EngineAnalysis e LEFT JOIN FETCH e.moveEvaluations WHERE e.position.id IN :positionIds")
    fun findByPositionIdInWithMoveEvaluations(
        @Param("positionIds") positionIds: Set<UUID>,
    ): List<EngineAnalysis>

    /**
     * Finds all moves that have already been evaluated for a position ID.
     */
    @Query("SELECT me.move FROM MoveEvaluation me WHERE me.engineAnalysis.position.id = :positionId")
    fun findEvaluatedMovesByPositionId(
        @Param("positionId") positionId: UUID,
    ): List<String>

    /**
     * Finds the engine analysis record by its own ID along with its moveEvaluations in a single query.
     *
     * Used to re-read authoritative, freshly-committed state after conflict-safe child inserts, rather
     * than trusting each insert's own return value: `ON CONFLICT ... DO NOTHING` reports whether *this*
     * call's row was the one written, not whether the row exists (a concurrent writer may have won).
     */
    @Query("SELECT e FROM EngineAnalysis e LEFT JOIN FETCH e.moveEvaluations WHERE e.id = :id")
    fun findByIdWithMoveEvaluations(
        @Param("id") id: UUID,
    ): EngineAnalysis?

    /**
     * Idempotently inserts a single [com.chessecho.domain.MoveEvaluation] row, relying on the
     * `(engine_analysis_id, move)` unique constraint to make a concurrent insert of the same move a
     * no-op rather than a thrown exception. This lets independently-racing workers converge on one
     * authoritative row for a given move without either caller's transaction being poisoned by the
     * conflict, and without ever reassigning/cascading a stale fetched [com.chessecho.domain.EngineAnalysis]
     * `moveEvaluations` collection (which would risk deleting a sibling worker's concurrently-inserted
     * row via `orphanRemoval`).
     *
     * @return the number of rows actually inserted: 1 if this call won the race for `move`, 0 if another
     *         writer already persisted it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
            INSERT INTO engine_move_evaluation (id, engine_analysis_id, move, eval_cp, eval_loss_from_best)
            VALUES (:id, :engineAnalysisId, :move, :evalCp, :evalLossFromBest)
            ON CONFLICT (engine_analysis_id, move) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertMoveEvaluationIfAbsent(
        @Param("id") id: UUID,
        @Param("engineAnalysisId") engineAnalysisId: UUID,
        @Param("move") move: String,
        @Param("evalCp") evalCp: Int?,
        @Param("evalLossFromBest") evalLossFromBest: Double?,
    ): Int
}
