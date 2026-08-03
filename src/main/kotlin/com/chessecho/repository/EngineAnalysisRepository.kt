package com.chessecho.repository

import com.chessecho.domain.EngineAnalysis
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface EngineAnalysisRepository : JpaRepository<EngineAnalysis, UUID> {
    fun findByPositionId(positionId: UUID): EngineAnalysis?
}
