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
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

enum class SchedulingEventType {
    PRESENTED,
    STARTED,
    SOLVED,
    FAILED,
    SKIPPED,
    GAME_REENCOUNTERED,
    GAME_MISTAKE,
    GAME_HANDLED_SUCCESSFULLY,
}

@Entity
@Table(
    name = "puzzle_scheduling_event",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_puzzle_event_source_type",
            columnNames = ["position_occurrence_id", "event_type"],
        ),
    ],
    indexes = [
        Index(name = "idx_puzzle_event_account_position", columnList = "chess_account_id, position_id, player_color, occurred_at"),
        Index(name = "idx_puzzle_event_source", columnList = "position_occurrence_id"),
    ],
)
class PuzzleSchedulingEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
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
)
