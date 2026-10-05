package com.chessecho.repository

import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface PuzzleSchedulingEventRepository : JpaRepository<PuzzleSchedulingEvent, UUID> {
    @Query(
        """
        SELECT e.eventType AS outcome, COUNT(e) AS submissionCount FROM PuzzleSchedulingEvent e
        WHERE e.appUser.id = :userId AND e.chessAccount.id = :accountId
          AND e.position.id = :positionId AND e.playerColor = :playerColor
          AND e.eventType IN (com.chessecho.domain.SchedulingEventType.SOLVED, com.chessecho.domain.SchedulingEventType.FAILED)
          AND e.sourceOccurrence IS NULL
        GROUP BY e.eventType
        """,
    )
    fun countSubmittedAnswers(
        userId: UUID,
        accountId: UUID,
        positionId: UUID,
        playerColor: String,
    ): List<PuzzleOutcomeCount>

    fun findByAppUserIdAndSubmissionId(
        appUserId: UUID,
        submissionId: UUID,
    ): PuzzleSchedulingEvent?

    @Modifying(flushAutomatically = true)
    @Query(
        value = """
        INSERT INTO puzzle_scheduling_event
            (id, app_user_id, chess_account_id, position_id, player_color, event_type, occurred_at, submission_id, submitted_move)
        VALUES (:id, :userId, :accountId, :positionId, :playerColor, :eventType, :occurredAt, :submissionId, :submittedMove)
        ON CONFLICT DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertSubmission(
        id: UUID,
        userId: UUID,
        accountId: UUID,
        positionId: UUID,
        playerColor: String,
        eventType: String,
        occurredAt: Instant,
        submissionId: UUID,
        submittedMove: String,
    ): Int

    /**
     * Personal read (#457): training history belongs to one `(AppUser, ChessAccount)` pair, never
     * to the shared account alone. `appUserId = null` selects guest history.
     */
    @Query(
        """
        SELECT e FROM PuzzleSchedulingEvent e
        WHERE e.chessAccount.id = :accountId
          AND ((:appUserId IS NULL AND e.appUser IS NULL) OR e.appUser.id = :appUserId)
          AND e.position.id IN :positionIds
          AND (:playerColor = 'BOTH' OR e.playerColor = :playerColor)
        ORDER BY e.occurredAt ASC, e.id ASC
        """,
    )
    fun findHistory(
        @Param("appUserId") appUserId: UUID?,
        @Param("accountId") accountId: UUID,
        @Param("positionIds") positionIds: Collection<UUID>,
        @Param("playerColor") playerColor: String,
    ): List<PuzzleSchedulingEvent>

    /**
     * Import-replay conflict recovery (#457). The matching key mirrors the `uk_puzzle_event_source_type`
     * unique index, including its `NULLS NOT DISTINCT` semantics: the `appUserId` predicate must be
     * null-safe, because a plain equality never matches a guest row and would leave a recoverable
     * conflict unrecovered.
     */
    @Query(
        "SELECT e FROM PuzzleSchedulingEvent e " +
            "WHERE ((:appUserId IS NULL AND e.appUser IS NULL) OR e.appUser.id = :appUserId) " +
            "AND e.sourceOccurrence.id = :sourceOccurrenceId AND e.eventType = :eventType",
    )
    fun findExistingClaim(
        @Param("appUserId") appUserId: UUID?,
        @Param("sourceOccurrenceId") sourceOccurrenceId: UUID,
        @Param("eventType") eventType: SchedulingEventType,
    ): PuzzleSchedulingEvent?
}

interface PuzzleOutcomeCount {
    val outcome: SchedulingEventType
    val submissionCount: Long
}
