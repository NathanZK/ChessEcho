package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
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

/**
 * One semantic decision point: the canonical position before a player's move.
 * SAN is payload; game, position, ply, and player color form the retry identity.
 */
@Entity
@Table(
    name = "position_occurrence",
    indexes = [
        Index(
            name = "idx_pos_occ_account_color_pos",
            columnList = "chess_account_id, player_color, position_id",
        ),
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_position_occurrence_identity",
            columnNames = ["game_id", "position_id", "ply_number", "player_color"],
        ),
    ],
)
class PositionOccurrence(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", nullable = false)
    val game: Game,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id", nullable = false)
    val position: Position,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chess_account_id", nullable = false)
    val chessAccount: ChessAccount,
    @Column(name = "ply_number", nullable = false)
    val plyNumber: Int,
    @Column(name = "move_played", nullable = false)
    val movePlayed: String,
    @Column(name = "player_color", nullable = false)
    val playerColor: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
