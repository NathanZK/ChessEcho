package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class SchedulingEventType {
    SOLVED,
    FAILED,
    GAME_REENCOUNTERED,
    GAME_MISTAKE,
    GAME_HANDLED_SUCCESSFULLY,
}

/**
 * The source-linked uniqueness invariant for this table is a partial, `NULLS NOT DISTINCT`
 * unique index defined in `V1__baseline.sql` (`uk_puzzle_event_source_type`). JPA cannot express
 * either the `WHERE position_occurrence_id IS NOT NULL` predicate or the null-equality semantics,
 * so it is deliberately not declared here: a table-wide `@UniqueConstraint` would wrongly collide
 * every training-history row, which carries `position_occurrence_id = NULL`.
 */
@Entity
@Table(
    name = "puzzle_scheduling_event",
    indexes = [
        Index(name = "idx_puzzle_event_account_position", columnList = "chess_account_id, position_id, player_color, occurred_at"),
        Index(name = "idx_puzzle_event_source", columnList = "position_occurrence_id"),
        Index(
            name = "idx_puzzle_event_user_account_position",
            columnList = "app_user_id, chess_account_id, position_id, player_color, occurred_at",
        ),
    ],
)
class PuzzleSchedulingEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id")
    val appUser: AppUser? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chess_account_id", nullable = false)
    val chessAccount: ChessAccount,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id", nullable = false)
    val position: Position,
    @Column(name = "player_color", nullable = false)
    val playerColor: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    val eventType: SchedulingEventType,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_occurrence_id")
    val sourceOccurrence: PositionOccurrence? = null,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant = Instant.now(),
    @Column(name = "submission_id")
    val submissionId: UUID? = null,
    @Column(name = "submitted_move")
    val submittedMove: String? = null,
)
