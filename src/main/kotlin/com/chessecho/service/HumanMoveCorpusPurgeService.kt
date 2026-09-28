package com.chessecho.service

import com.chessecho.humanmove.artifact.CorpusArtifactConflict
import com.chessecho.humanmove.artifact.HumanMoveCorpusArtifactService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class HumanMoveCorpusPurgeResult(
    val sourceRunId: UUID,
    val contentDigest: String,
    val gamesPurged: Int,
)

/**
 * Purges raw imported evidence for one source corpus identity only after
 * durable archive verification, import, and at least one finalized
 * projection built from that exact snapshot digest exist. Snapshot,
 * projection, and provenance receipts survive; a later verified import
 * rehydrates the exact raw evidence without changing existing projections.
 * Never deletes #423 acquisition rows or legacy `human_move_distribution`.
 */
@Service
class HumanMoveCorpusPurgeService(
    private val jdbcTemplate: JdbcTemplate,
    private val artifactService: HumanMoveCorpusArtifactService,
    private val importService: HumanMoveCorpusImportService,
    private val finalizationService: HumanMoveCorpusProjectionFinalizationService,
    private val occurrenceService: HumanMoveCorpusOccurrenceService,
    private val e6AnalysisEvidenceService: E6AnalysisEvidenceService,
) {
    @Transactional
    fun purge(
        runId: UUID,
        contentDigest: String,
    ): HumanMoveCorpusPurgeResult {
        HumanMoveCorpusImportService.lockSource(jdbcTemplate, runId)
        val snapshotRunId =
            jdbcTemplate.query(
                "SELECT source_run_id FROM human_move_corpus_artifact_snapshot WHERE content_digest = ?",
                { rs, _ -> rs.getObject(1, UUID::class.java) },
                contentDigest,
            ).singleOrNull() ?: throw NoSuchElementException("Artifact snapshot $contentDigest not found")
        if (snapshotRunId != runId) {
            throw CorpusArtifactConflict("Artifact snapshot $contentDigest does not belong to corpus run $runId")
        }
        val archive =
            artifactService.verifiedArchivePath(contentDigest)
                ?: throw CorpusArtifactConflict("Archived artifact $contentDigest is unavailable; raw evidence cannot be purged")
        val verified = artifactService.verifyArchive(archive, contentDigest)
        if (verified.manifest.sourceRunId != runId) {
            throw CorpusArtifactConflict("Archived artifact does not belong to corpus run $runId")
        }
        occurrenceService.verifyImportEvidence(verified, archive)
        if (verified.manifest.e6Eligible && occurrenceService.hasSourceRun(runId)) {
            e6AnalysisEvidenceService.verifyTerminalE6Eligibility(
                runId,
                contentDigest,
                verified.manifest.coveredPrefix,
            )
        }
        val rawCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM human_move_corpus_imported_game WHERE source_run_id = ?",
                Int::class.java,
                runId,
            )!!
        if (rawCount != 0 && rawCount != verified.manifest.coveredPrefix) {
            throw CorpusArtifactConflict("Artifact must cover the complete imported corpus before purging its raw evidence")
        }

        val finalizedProjections =
            jdbcTemplate.query(
                "SELECT id, distribution_sha256, min_observations FROM human_move_corpus_projection " +
                    "WHERE content_digest = ? AND source_run_id = ? AND prefix_n = ? AND finalized = true AND verified = true",
                { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getInt(3)) },
                contentDigest,
                runId,
                verified.manifest.coveredPrefix,
            )
        if (finalizedProjections.isEmpty()) {
            throw CorpusArtifactConflict(
                "Corpus run $runId snapshot $contentDigest has no finalized projection yet; purge is not gated open",
            )
        }

        if (rawCount != 0) importService.verifyImportedEvidence(archive, verified)
        finalizedProjections.forEach { (projectionId, digest, minObservations) ->
            if (finalizationService.digestProjection(projectionId).distributionSha256 != digest ||
                (
                    rawCount != 0 &&
                        finalizationService.expectedDigest(runId, verified.manifest.coveredPrefix, minObservations) != digest
                )
            ) {
                throw CorpusArtifactConflict("Finalized projection $projectionId differs from its verified checkpoint-equivalent digest")
            }
        }
        val purged = jdbcTemplate.update("DELETE FROM human_move_corpus_imported_game WHERE source_run_id = ?", runId)
        jdbcTemplate.update("UPDATE human_move_corpus_import SET raw_available = false WHERE source_run_id = ?", runId)
        return HumanMoveCorpusPurgeResult(runId, contentDigest, purged)
    }
}
