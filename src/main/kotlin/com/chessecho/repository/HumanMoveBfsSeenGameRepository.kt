package com.chessecho.repository

import com.chessecho.domain.HumanMoveBfsSeenGame
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface HumanMoveBfsSeenGameRepository : JpaRepository<HumanMoveBfsSeenGame, String> {
    /**
     * Bulk existence probe. Returns the subset of [gameUrls] that is already
     * persisted. Used by [com.chessecho.service.HumanMoveBfsService] at
     * archive-load time to skip parsing games that were consumed by a previous
     * batch / invocation / day.
     *
     * The atomic-claim primitive for a batch lives in
     * [HumanMoveBfsSeenGameClaimer], which issues a plain `INSERT` inside the
     * caller's transaction; the `game_url` primary-key uniqueness constraint
     * provides atomic exactly-once semantics (a collision surfaces as
     * [org.springframework.dao.DataIntegrityViolationException] and aborts the
     * enclosing transaction).
     */
    @Query("SELECT s.gameUrl FROM HumanMoveBfsSeenGame s WHERE s.gameUrl IN :gameUrls")
    fun findExistingGameUrls(
        @Param("gameUrls") gameUrls: Collection<String>,
    ): List<String>
}
