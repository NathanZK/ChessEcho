package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One imported, target-local copy of a portable #426 corpus game. Rows are
 * written only by [com.chessecho.service.HumanMoveCorpusImportService] and
 * removed only by [com.chessecho.service.HumanMoveCorpusPurgeService]; both
 * operate on the separate #426-owned tables and never touch #423 rows.
 */
@Entity
@Immutable
@Table(name = "human_move_corpus_imported_game")
class HumanMoveCorpusImportedGame(
    @Id
    val id: UUID,
    @Column(name = "source_run_id", nullable = false)
    val sourceRunId: UUID,
    @Column(name = "qualifying_ordinal", nullable = false)
    val qualifyingOrdinal: Int,
    @Column(name = "provider_game_id", nullable = false, length = 2048)
    val providerGameId: String,
    @Column(name = "traversed_player", nullable = false)
    val traversedPlayer: String,
    @Column(name = "opponent", nullable = false)
    val opponent: String,
    @Column(name = "opponent_side", nullable = false)
    val opponentSide: String,
    @Column(name = "opponent_rating", nullable = false)
    val opponentRating: Int,
    @Column(name = "rules")
    val rules: String?,
    @Column(name = "time_class", nullable = false)
    val timeClass: String,
    @Column(name = "bfs_depth", nullable = false)
    val bfsDepth: Int,
    @Column(name = "pgn", nullable = false, columnDefinition = "TEXT")
    val pgn: String,
    @Column(name = "pgn_sha256", nullable = false)
    val pgnSha256: String,
    @Column(name = "observation_total", nullable = false)
    val observationTotal: Int,
    @Column(name = "distinct_move_count", nullable = false)
    val distinctMoveCount: Int,
    @Column(name = "committed_at", nullable = false)
    val committedAt: OffsetDateTime,
)
