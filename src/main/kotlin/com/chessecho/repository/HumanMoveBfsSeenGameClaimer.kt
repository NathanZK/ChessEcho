package com.chessecho.repository

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Thrown when the atomic claim step for a batch of game URLs collides with an
 * existing row. Under the single-writer assumption plus the per-archive
 * pre-check in [com.chessecho.service.HumanMoveBfsService] this cannot happen;
 * if it does, it is a genuine integrity failure and the enclosing
 * `@Transactional` boundary rolls back both the URL claims and the batch's
 * `human_move_distribution` writes.
 */
class HumanMoveBfsClaimConflictException(
    val attempted: Int,
    cause: Throwable,
) : RuntimeException(
        "Atomic claim of $attempted game URLs collided with an existing row; " +
            "aborting batch to preserve exactly-once observation semantics",
        cause,
    )

/**
 * Atomic claim primitive for [com.chessecho.domain.HumanMoveBfsSeenGame].
 *
 * Uses [JdbcTemplate.batchUpdate] with a plain `INSERT INTO
 * human_move_bfs_seen_game(game_url) VALUES (?)`. The database enforces
 * exactly-once semantics via the primary-key uniqueness constraint on
 * `game_url` — if any URL already exists the batch throws
 * [org.springframework.dao.DataIntegrityViolationException], which this
 * component rewraps as [HumanMoveBfsClaimConflictException]. Because the claim
 * runs inside the caller's transaction, the exception propagating out marks the
 * `@Transactional` boundary for rollback so both the URL claims and the batch's
 * `human_move_distribution` writes vanish together.
 *
 * This SQL is portable across PostgreSQL (production) and H2 (tests) with no
 * dialect-specific syntax such as `ON CONFLICT` or `MERGE`, which H2 does not
 * accept even in PostgreSQL compatibility mode.
 */
@Component
class HumanMoveBfsSeenGameClaimer(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun claimGameUrls(urls: Collection<String>) {
        if (urls.isEmpty()) return
        try {
            jdbcTemplate.batchUpdate(
                "INSERT INTO human_move_bfs_seen_game(game_url, seen_at) VALUES (?, CURRENT_TIMESTAMP)",
                urls.map { arrayOf<Any>(it) },
            )
        } catch (e: DataIntegrityViolationException) {
            throw HumanMoveBfsClaimConflictException(attempted = urls.size, cause = e)
        }
    }
}
