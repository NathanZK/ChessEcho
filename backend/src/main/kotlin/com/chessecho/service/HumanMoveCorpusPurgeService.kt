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
    private val retainedEvaluationEvidenceService: RetainedEvaluationEvidenceService,
) {
    @Transactional
    fun purge(
        runId: UUID,
        contentDigest: String,
    ): HumanMoveCorpusPurgeResult {
        HumanMoveCorpusImportService.lockSource(jdbcTemplate, runId)
        verifySnapshotOwnership(runId, contentDigest)
        val (archive, verified) = verifyPurgeArchive(runId, contentDigest)
        val rawCount = queryRawGameCount(runId, verified.manifest.coveredPrefix)

        if (rawCount != 0) importService.verifyImportedEvidence(archive, verified)
        verifyFinalizedProjections(runId, contentDigest, verified.manifest.coveredPrefix, rawCount)

        val purged = executePurge(runId)
        return HumanMoveCorpusPurgeResult(runId, contentDigest, purged)
    }

    private fun verifySnapshotOwnership(
        runId: UUID,
        contentDigest: String,
    ) {
        val snapshotRunId =
            jdbcTemplate
                .query(
                    "SELECT source_run_id FROM human_move_corpus_artifact_snapshot WHERE content_digest = ?",
                    { rs, _ -> rs.getObject(1, UUID::class.java) },
                    contentDigest,
                ).singleOrNull() ?: throw NoSuchElementException("Artifact snapshot $contentDigest not found")
        if (snapshotRunId != runId) {
            throw CorpusArtifactConflict("Artifact snapshot $contentDigest does not belong to corpus run $runId")
        }
    }

    private data class PurgeArchiveEvidence(
        val archive: java.nio.file.Path,
        val verified: com.chessecho.humanmove.artifact.VerifiedCorpusArtifact,
    )

    private fun verifyPurgeArchive(
        runId: UUID,
        contentDigest: String,
    ): PurgeArchiveEvidence {
        val archive =
            artifactService.verifiedArchivePath(contentDigest)
                ?: throw CorpusArtifactConflict("Archived artifact $contentDigest is unavailable; raw evidence cannot be purged")
        val verified = artifactService.verifyArchive(archive, contentDigest)
        if (verified.manifest.sourceRunId != runId) {
            throw CorpusArtifactConflict("Archived artifact does not belong to corpus run $runId")
        }
        occurrenceService.verifyImportEvidence(verified, archive)
        if (verified.manifest.e6Eligible && occurrenceService.hasSourceRun(runId)) {
            retainedEvaluationEvidenceService.verifyTerminalReferenceCoverageEligibility(
                runId,
                contentDigest,
                verified.manifest.coveredPrefix,
            )
        }
        return PurgeArchiveEvidence(archive, verified)
    }

    private fun queryRawGameCount(
        runId: UUID,
        coveredPrefix: Int,
    ): Int {
        val rawCount =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM human_move_corpus_imported_game WHERE source_run_id = ?",
                Int::class.java,
                runId,
            )!!
        if (rawCount != 0 && rawCount != coveredPrefix) {
            throw CorpusArtifactConflict("Artifact must cover the complete imported corpus before purging its raw evidence")
        }
        return rawCount
    }

    private fun verifyFinalizedProjections(
        runId: UUID,
        contentDigest: String,
        coveredPrefix: Int,
        rawCount: Int,
    ) {
        val finalizedProjections =
            jdbcTemplate.query(
                "SELECT id, distribution_sha256, min_observations FROM human_move_corpus_projection " +
                    "WHERE content_digest = ? AND source_run_id = ? AND prefix_n = ? AND finalized = true AND verified = true",
                { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getInt(3)) },
                contentDigest,
                runId,
                coveredPrefix,
            )
        if (finalizedProjections.isEmpty()) {
            throw CorpusArtifactConflict(
                "Corpus run $runId snapshot $contentDigest has no finalized projection yet; purge is not gated open",
            )
        }
        finalizedProjections.forEach { (projectionId, digest, minObservations) ->
            val currentDigest = finalizationService.digestProjection(projectionId).distributionSha256
            val matchesExpected =
                rawCount == 0 ||
                    finalizationService.expectedDigest(runId, coveredPrefix, minObservations) == digest
            if (currentDigest != digest || !matchesExpected) {
                throw CorpusArtifactConflict("Finalized projection $projectionId differs from its verified checkpoint-equivalent digest")
            }
        }
    }

    private fun executePurge(runId: UUID): Int {
        val purged = jdbcTemplate.update("DELETE FROM human_move_corpus_imported_game WHERE source_run_id = ?", runId)
        jdbcTemplate.update("UPDATE human_move_corpus_import SET raw_available = false WHERE source_run_id = ?", runId)
        return purged
    }
}
