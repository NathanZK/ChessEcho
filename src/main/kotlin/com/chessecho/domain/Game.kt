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
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "game")
class Game(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chess_account_id", nullable = false)
    val chessAccount: ChessAccount,
    @Column(name = "platform_game_id", nullable = false)
    val platformGameId: String,
    @Column(nullable = false, columnDefinition = "TEXT")
    val pgn: String,
    @Column(name = "time_control")
    val timeControl: String? = null,
    @Column(name = "played_at")
    val playedAt: Instant? = null,
    val result: String? = null,
    @Column(name = "white_username")
    val whiteUsername: String? = null,
    @Column(name = "black_username")
    val blackUsername: String? = null,
    @Column(name = "created_at")
    val createdAt: Instant = Instant.now(),
)
