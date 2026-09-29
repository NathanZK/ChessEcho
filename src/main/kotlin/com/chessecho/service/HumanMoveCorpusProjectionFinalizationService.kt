package com.chessecho.service

import com.chessecho.humanmove.artifact.CorpusArtifactConflict
import com.chessecho.humanmove.artifact.HumanMoveCorpusArtifactService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.UUID

data class HumanMoveCorpusProjectionFinalizeResponse(
    val projectionId: UUID,
    val positionsRetained: Int,
    val rowsRetained: Int,
    val distributionSha256: String,
)

/**
 * The corpus-aware finalizer preserving the legacy position-sum/`minObservations`
 * retention rule, scoped to exactly one projection identity. The legacy
 * band-wide `/finalize` endpoint and `human_move_distribution` are untouched.
 */
@Service
class HumanMoveCorpusProjectionFinalizationService(
    private val jdbcTemplate: JdbcTemplate,
    private val artifactService: HumanMoveCorpusArtifactService,
    private val importService: HumanMoveCorpusImportService,
    private val occurrenceService: HumanMoveCorpusOccurrenceService,
    private val retainedEvaluationEvidenceService: RetainedEvaluationEvidenceService,
) {
    fun expectedDigest(
        runId: UUID,
        prefixN: Int,
        minObservations: Int,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        jdbcTemplate.query(
            {
                it.prepareStatement(
                    """
                    SELECT position_hash, move_played, observation_count FROM (
                        SELECT o.position_hash, o.move_played, SUM(o.observation_count) AS observation_count,
                               SUM(SUM(o.observation_count)) OVER (PARTITION BY o.position_hash) AS position_total
                        FROM human_move_corpus_imported_observation o
                        JOIN human_move_corpus_imported_game g ON g.id = o.game_id
                        WHERE g.source_run_id = ? AND g.qualifying_ordinal <= ?
                        GROUP BY o.position_hash, o.move_played
                    ) aggregate
                    WHERE position_total >= ?
                    ORDER BY position_hash, move_played
                    """.trimIndent(),
                ).apply {
                    fetchSize = 500
                    setObject(1, runId)
                    setInt(2, prefixN)
                    setInt(3, minObservations)
                }
            },
            RowCallbackHandler { rs ->
                digest.update(
                    "${rs.getString(1)}\t${rs.getString(2)}\t${rs.getLong(3)}\n".toByteArray(Charsets.UTF_8),
                )
            },
        )
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Transactional
    fun finalize(projectionId: UUID): HumanMoveCorpusProjectionFinalizeResponse {
        val projection =
            jdbcTemplate.query(
                """
                SELECT source_run_id, prefix_n, min_observations, content_digest, distribution_sha256, finalized, verified
                FROM human_move_corpus_projection WHERE id = ? FOR UPDATE
                """.trimIndent(),
                { rs, _ ->
                    ProjectionEvidence(
                        rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getInt(3),
                        rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getBoolean(7),
                    )
                },
                projectionId,
            ).singleOrNull() ?: throw NoSuchElementException("Projection $projectionId not found")
        if (projection.finalized) {
            if (!projection.verified) throw CorpusArtifactConflict("Projection $projectionId is finalized without verification")
            retainedEvaluationEvidenceService.verifyFinalizedEvidenceIfEligible(projection.runId, projection.contentDigest)
            return digestProjection(projectionId).also {
                if (it.distributionSha256 != projection.expectedDigest) {
                    throw CorpusArtifactConflict("Projection $projectionId differs from its verified distribution digest")
                }
            }
        }
        val archive =
            artifactService.verifiedArchivePath(projection.contentDigest)
                ?: throw CorpusArtifactConflict("Projection $projectionId has no verified archive")
        val verified = artifactService.verifyArchive(archive, projection.contentDigest)
        if (verified.manifest.sourceRunId != projection.runId || projection.prefixN > verified.manifest.coveredPrefix) {
            throw CorpusArtifactConflict("Projection $projectionId is not covered by its source artifact")
        }
        importService.verifyImportedEvidence(archive, verified)
        val expected = expectedDigest(projection.runId, projection.prefixN, projection.minObservations)
        if (projection.expectedDigest != expected) {
            throw CorpusArtifactConflict("Projection $projectionId no longer matches the imported checkpoint-equivalent distribution")
        }

        jdbcTemplate.update(
            """
            DELETE FROM human_move_corpus_projection_row r
            WHERE r.projection_id = ?
              AND (SELECT SUM(s.observation_count) FROM human_move_corpus_projection_row s
                   WHERE s.projection_id = r.projection_id AND s.position_hash = r.position_hash) < ?
            """.trimIndent(),
            projectionId,
            projection.minObservations,
        )
        val result = digestProjection(projectionId)
        if (result.distributionSha256 != expected) {
            throw CorpusArtifactConflict("Projection $projectionId does not match its checkpoint-equivalent distribution")
        }
        val mismatches =
            jdbcTemplate.queryForObject(
                """
                WITH expected AS (
                    SELECT position_hash, move_played, observation_count FROM (
                        SELECT o.position_hash, o.move_played, SUM(o.observation_count)::integer AS observation_count,
                               SUM(SUM(o.observation_count)) OVER (PARTITION BY o.position_hash) AS position_total
                        FROM human_move_corpus_imported_observation o
                        JOIN human_move_corpus_imported_game g ON g.id = o.game_id
                        WHERE g.source_run_id = ? AND g.qualifying_ordinal <= ?
                        GROUP BY o.position_hash, o.move_played
                    ) aggregate WHERE position_total >= ?
                ), actual AS (
                    SELECT position_hash, move_played, observation_count
                    FROM human_move_corpus_projection_row WHERE projection_id = ?
                )
                SELECT EXISTS (
                    (SELECT * FROM expected EXCEPT SELECT * FROM actual)
                    UNION ALL (SELECT * FROM actual EXCEPT SELECT * FROM expected)
                )
                """.trimIndent(),
                Boolean::class.java,
                projection.runId,
                projection.prefixN,
                projection.minObservations,
                projectionId,
            )!!
        if (mismatches) throw CorpusArtifactConflict("Projection $projectionId rows differ from imported raw evidence")
        occurrenceService.bindFinalizedEvidence(verified, archive)
        jdbcTemplate.update(
            "UPDATE human_move_corpus_projection SET finalized = true, verified = true WHERE id = ?",
            projectionId,
        )
        return result
    }

    fun digestProjection(projectionId: UUID): HumanMoveCorpusProjectionFinalizeResponse {
        val digest = MessageDigest.getInstance("SHA-256")
        var rowsRetained = 0
        var positionsRetained = 0
        var previousHash: String? = null
        jdbcTemplate.query(
            {
                it.prepareStatement(
                    "SELECT position_hash, move_played, observation_count FROM human_move_corpus_projection_row " +
                        "WHERE projection_id = ? ORDER BY position_hash, move_played",
                ).apply {
                    fetchSize = 500
                    setObject(1, projectionId)
                }
            },
            RowCallbackHandler { rs ->
                val hash = rs.getString("position_hash")
                if (hash != previousHash) {
                    positionsRetained++
                    previousHash = hash
                }
                digest.update("$hash\t${rs.getString("move_played")}\t${rs.getInt("observation_count")}\n".toByteArray(Charsets.UTF_8))
                rowsRetained++
            },
        )
        val distributionSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        return HumanMoveCorpusProjectionFinalizeResponse(
            projectionId = projectionId,
            positionsRetained = positionsRetained,
            rowsRetained = rowsRetained,
            distributionSha256 = distributionSha256,
        )
    }

    private data class ProjectionEvidence(
        val runId: UUID,
        val prefixN: Int,
        val minObservations: Int,
        val contentDigest: String,
        val expectedDigest: String,
        val finalized: Boolean,
        val verified: Boolean,
    )
}
