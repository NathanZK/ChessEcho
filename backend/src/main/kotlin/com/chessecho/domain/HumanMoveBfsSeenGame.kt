package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Persistent record that a Chess.com game URL has already contributed its
 * observations to `human_move_distribution`. The primary key is the URL itself
 * so the database enforces at-most-once contribution globally, across BFS
 * batches, separate BFS invocations, and separate days.
 *
 * This table is intentionally BFS-owned and independent of the user-facing
 * `game` table, whose uniqueness is per-`chess_account_id` and whose purpose is
 * user-analysis storage.
 */
@Entity
@Table(name = "human_move_bfs_seen_game")
class HumanMoveBfsSeenGame(
    @Id
    @Column(name = "game_url", nullable = false, length = 2048)
    val gameUrl: String,
    @Column(name = "seen_at", nullable = false)
    val seenAt: Instant = Instant.now(),
)
