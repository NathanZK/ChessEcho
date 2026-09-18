package com.chessecho.repository

import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface PuzzleSchedulingEventRepository : JpaRepository<PuzzleSchedulingEvent, UUID> {
    @Query(
        """
        SELECT e FROM PuzzleSchedulingEvent e
        WHERE e.chessAccount.id = :accountId
          AND e.position.id IN :positionIds
          AND (:playerColor = 'BOTH' OR e.playerColor = :playerColor)
        ORDER BY e.occurredAt ASC, e.id ASC
        """,
    )
    fun findHistory(
        @Param("accountId") accountId: UUID,
        @Param("positionIds") positionIds: Collection<UUID>,
        @Param("playerColor") playerColor: String,
    ): List<PuzzleSchedulingEvent>

    @Query(
        "SELECT e FROM PuzzleSchedulingEvent e " +
            "WHERE e.sourceOccurrence.id = :sourceOccurrenceId AND e.eventType = :eventType",
    )
    fun findExistingClaim(
        @Param("sourceOccurrenceId") sourceOccurrenceId: UUID,
        @Param("eventType") eventType: SchedulingEventType,
    ): PuzzleSchedulingEvent?
}
