package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One Issue #426 target-local publication of a source #423 corpus identity.
 * The source run ID is retained as the primary key; [rawAvailable] tracks
 * whether raw imported games/observations currently exist (a purge clears
 * them without deleting this provenance row).
 */
@Entity
@Table(name = "human_move_corpus_import")
class HumanMoveCorpusImport(
    @Id
    @Column(name = "source_run_id")
    val sourceRunId: UUID,
    @Column(name = "rating_band", nullable = false)
    val ratingBand: String,
    @Column(name = "algorithm_version", nullable = false)
    val algorithmVersion: String,
    @Column(name = "source_revision", nullable = false)
    val sourceRevision: String,
    @Column(name = "request_sha256", nullable = false)
    val requestSha256: String,
    @Column(name = "request_json", nullable = false, columnDefinition = "TEXT")
    val requestJson: String,
    @Column(name = "raw_available", nullable = false)
    val rawAvailable: Boolean,
    @Column(name = "imported_at", nullable = false)
    val importedAt: OffsetDateTime,
)
