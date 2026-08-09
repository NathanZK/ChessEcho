package com.chessecho.repository

import com.chessecho.domain.Position
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface PositionRepository : JpaRepository<Position, UUID> {
    @Query("SELECT p FROM Position p WHERE p.hash IN :hashes")
    fun findByHashIn(hashes: List<String>): List<Position>

    @Query(
        """
        SELECT p FROM Position p
        WHERE p.id NOT IN (SELECT e.position.id FROM EngineAnalysis e)
        AND (SELECT COUNT(o) FROM PositionOccurrence o WHERE o.position.id = p.id) >= :minOccurrences
    """,
    )
    fun findUnanalyzedCandidatePositions(
        minOccurrences: Long,
        pageable: org.springframework.data.domain.Pageable,
    ): List<Position>

    /**
     * Finds position IDs among the provided set that have reached the minimum global occurrence threshold.
     */
    @Query(
        """
        SELECT po.position.id
        FROM PositionOccurrence po
        WHERE po.position.id IN :positionIds
        GROUP BY po.position.id
        HAVING COUNT(po.id) >= :minOccurrences
        """,
    )
    fun findQualifyingPositionIds(
        positionIds: Set<UUID>,
        minOccurrences: Long,
    ): List<UUID>
}
