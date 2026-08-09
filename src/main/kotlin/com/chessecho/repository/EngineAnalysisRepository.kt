package com.chessecho.repository

import com.chessecho.domain.EngineAnalysis
import org.springframework.data.jpa.repository.JpaRepository
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
     * Finds all moves that have already been evaluated for a position ID.
     */
    @Query("SELECT me.move FROM MoveEvaluation me WHERE me.engineAnalysis.position.id = :positionId")
    fun findEvaluatedMovesByPositionId(
        @Param("positionId") positionId: UUID,
    ): List<String>
}
