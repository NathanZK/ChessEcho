package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class TrainingAttemptMode {
    STOPWATCH,
    COUNTDOWN,
}

enum class TrainingAttemptOutcome {
    SUBMITTED,
    EXPIRED,
    CANCELLED,
}

@Entity
@Table(name = "training_attempt")
class TrainingAttempt(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    val puzzleId: String,
    @Column(nullable = false)
    @Enumerated
    val mode: TrainingAttemptMode,
    @Column(nullable = false)
    val elapsedMs: Long,
    @Column(nullable = true)
    val allowedMs: Long? = null,
    @Column(nullable = false)
    @Enumerated
    val outcome: TrainingAttemptOutcome,
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "chess_account_id", nullable = true)
    val chessAccount: ChessAccount? = null,
    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),
)
