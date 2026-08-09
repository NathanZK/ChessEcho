package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "user_position_weakness",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["chess_account_id", "position_id", "player_color"]),
    ],
)
class UserPositionWeakness(
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
    @Column(name = "mistake_count", nullable = false)
    val mistakeCount: Int = 0,
    @Column(name = "mistake_rate")
    val mistakeRate: Double? = null,
    @Column(name = "average_loss")
    val averageLoss: Double? = null,
    @Column(name = "priority")
    val priority: Double? = null,
    @Column(name = "moves_played")
    val movesPlayed: String? = null,
    @Column(name = "game_urls")
    val gameUrls: String? = null,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)
