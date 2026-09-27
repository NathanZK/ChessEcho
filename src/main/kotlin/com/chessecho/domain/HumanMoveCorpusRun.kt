package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.util.UUID

/** Only [COMPLETED] denotes a successfully completed corpus. */
enum class HumanMoveCorpusRunStatus {
    RUNNING,
    COMPLETED,
    INCOMPLETE,
    FAILED,
}

/**
 * One Issue #423 reference-corpus run. Rows are written only through
 * [com.chessecho.service.HumanMoveCorpusGameWriter]; database triggers keep
 * identity and configuration immutable and the committed frontier monotonic.
 */
@Entity
@Immutable
@Table(name = "human_move_corpus_run")
class HumanMoveCorpusRun(
    @Id
    val id: UUID,
    @Column(name = "rating_band", nullable = false)
    val ratingBand: String,
    @Column(name = "seed_players", nullable = false, columnDefinition = "TEXT")
    val seedPlayers: String,
    @Column(name = "excluded_players", nullable = false, columnDefinition = "TEXT")
    val excludedPlayers: String,
    @Column(name = "max_qualifying_games")
    val maxQualifyingGames: Int?,
    @Column(name = "max_games_per_player", nullable = false)
    val maxGamesPerPlayer: Int,
    @Column(name = "max_players")
    val maxPlayers: Int?,
    @Column(name = "max_depth")
    val maxDepth: Int?,
    @Column(name = "batch_size", nullable = false)
    val batchSize: Int,
    @Column(name = "algorithm_version", nullable = false)
    val algorithmVersion: String,
    @Column(name = "source_revision", nullable = false)
    val sourceRevision: String,
    @Column(name = "request_json", nullable = false, columnDefinition = "TEXT")
    val requestJson: String,
    @Column(name = "request_sha256", nullable = false)
    val requestSha256: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    val status: HumanMoveCorpusRunStatus,
    @Column(name = "committed_frontier", nullable = false)
    val committedFrontier: Int,
    @Column(name = "rejected_game_count", nullable = false)
    val rejectedGameCount: Int,
    @Column(name = "archive_fetch_failure_count", nullable = false)
    val archiveFetchFailureCount: Int,
    @Column(name = "stop_reason")
    val stopReason: String?,
    @Column(name = "failure_details", columnDefinition = "TEXT")
    val failureDetails: String?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
    @Column(name = "finished_at")
    val finishedAt: Instant?,
)
