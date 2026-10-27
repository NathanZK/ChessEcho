package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.OffsetDateTime
import java.util.UUID

/**
 * One verified, immutable version-1 artifact snapshot of a source corpus
 * identity. Two snapshots of the same [sourceRunId] with different
 * [coveredPrefix] remain independently addressable by [contentDigest].
 */
@Entity
@Immutable
@Table(name = "human_move_corpus_artifact_snapshot")
class HumanMoveCorpusArtifactSnapshot(
    @Id
    @Column(name = "content_digest")
    val contentDigest: String,
    @Column(name = "source_run_id", nullable = false)
    val sourceRunId: UUID,
    @Column(name = "covered_prefix", nullable = false)
    val coveredPrefix: Int,
    @Column(name = "manifest_json", nullable = false, columnDefinition = "TEXT")
    val manifestJson: String,
    @Column(name = "verified_at", nullable = false)
    val verifiedAt: OffsetDateTime,
)
