package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/**
 * One independently addressable operational projection of one artifact
 * snapshot, keyed by the full identity tuple enforced by the database's
 * `uk_corpus_projection_scope` constraint. Never shares rows with another
 * projection, another corpus, or the legacy `human_move_distribution` table.
 */
@Entity
@Table(name = "human_move_corpus_projection")
class HumanMoveCorpusProjection(
    @Id
    val id: UUID,
    @Column(name = "content_digest", nullable = false)
    val contentDigest: String,
    @Column(name = "source_run_id", nullable = false)
    val sourceRunId: UUID,
    @Column(name = "prefix_n", nullable = false)
    val prefixN: Int,
    @Column(name = "rating_band", nullable = false)
    val ratingBand: String,
    @Column(name = "min_observations", nullable = false)
    val minObservations: Int,
    @Column(name = "calculation_version", nullable = false)
    val calculationVersion: String,
    @Column(name = "distribution_sha256")
    val distributionSha256: String?,
    @Column(name = "finalized", nullable = false)
    val finalized: Boolean,
    @Column(name = "verified", nullable = false)
    val verified: Boolean,
)
