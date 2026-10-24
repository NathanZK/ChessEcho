package com.chessecho.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class RetainedEvaluationEvidenceService(
    private val jdbcTemplate: JdbcTemplate,
    private val snapshotService: EvaluationEvidenceSnapshotService,
    private val occurrenceService: HumanMoveCorpusOccurrenceService,
) {
    @Transactional(readOnly = true)
    fun verifyTerminalReferenceCoverageEligibility(
        sourceRunId: UUID,
        contentDigest: String,
        coveredPrefix: Int,
    ): HumanMoveCorpusOccurrenceBinding {
        val binding = occurrenceService.verifyRequiredBinding(sourceRunId, contentDigest, coveredPrefix)
        val eligible =
            jdbcTemplate.query(
                """
                SELECT COALESCE((s.manifest_json::jsonb ->> 'e6Eligible')::boolean, false),
                       r.committed_frontier
                FROM human_move_corpus_artifact_snapshot s
                JOIN human_move_corpus_run r ON r.id = s.source_run_id
                WHERE s.source_run_id = ? AND s.content_digest = ? AND s.covered_prefix = ?
                """.trimIndent(),
                { rs, _ -> rs.getBoolean(1) && rs.getInt(2) == coveredPrefix },
                sourceRunId,
                contentDigest,
                coveredPrefix,
            ).singleOrNull() == true
        if (!eligible) {
            throw RetainedEvaluationEvidenceIntegrityException(
                "Run $sourceRunId artifact is not terminally eligible for reference coverage",
            )
        }
        return binding
    }

    @Transactional(readOnly = true)
    fun verifyFinalizedEvidenceIfEligible(
        sourceRunId: UUID,
        contentDigest: String,
    ) {
        val snapshot =
            jdbcTemplate.query(
                """
                SELECT covered_prefix, COALESCE((manifest_json::jsonb ->> 'e6Eligible')::boolean, false)
                FROM human_move_corpus_artifact_snapshot
                WHERE source_run_id = ? AND content_digest = ?
                """.trimIndent(),
                { rs, _ -> rs.getInt(1) to rs.getBoolean(2) },
                sourceRunId,
                contentDigest,
            ).singleOrNull() ?: return
        if (snapshot.second && occurrenceService.hasSourceRun(sourceRunId)) {
            verifyTerminalReferenceCoverageEligibility(sourceRunId, contentDigest, snapshot.first)
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun loadAndAdmit(
        sourceRunId: UUID,
        projectionId: UUID,
        analysisVersion: String,
    ): RetainedEvaluationEvidence {
        val snapshot = snapshotService.readVerifiedPersisted(sourceRunId)
        val projectionWithRows = queryProjectionWithRows(projectionId)
        val population = snapshot.referencePopulation
        validatePopulationMatchesProjection(population, projectionWithRows)
        verifyArtifactEligibility(sourceRunId, population)
        verifyTerminalReferenceCoverageEligibility(sourceRunId, population.contentDigest, population.coveredPrefix)
        val occurrences = querySnapshotOccurrences(snapshot)
        return RetainedEvaluationEvidenceValidator().admit(
            RetainedEvaluationEvidence(population, projectionWithRows, snapshot, occurrences, analysisVersion),
        )
    }

    private fun queryProjectionWithRows(projectionId: UUID): RetainedReferenceProjection {
        val projection = queryProjectionMetadata(projectionId)
        val rows = queryProjectionRows(projectionId)
        return projection.copy(rows = rows)
    }

    private fun queryProjectionMetadata(projectionId: UUID): RetainedReferenceProjection =
        jdbcTemplate.query(
            """
            SELECT finalized, verified, distribution_sha256, content_digest, source_run_id, prefix_n,
                   rating_band, min_observations, calculation_version
            FROM human_move_corpus_projection
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                RetainedReferenceProjection(
                    finalized = rs.getBoolean("finalized"),
                    verified = rs.getBoolean("verified"),
                    distributionSha256 = rs.getString("distribution_sha256"),
                    rows = emptyList(),
                    contentDigest = rs.getString("content_digest"),
                    sourceRunId = rs.getObject("source_run_id", UUID::class.java),
                    prefixN = rs.getInt("prefix_n"),
                    ratingBand = rs.getString("rating_band"),
                    minObservations = rs.getInt("min_observations"),
                    calculationVersion = rs.getString("calculation_version"),
                )
            },
            projectionId,
        ).singleOrNull() ?: throw RetainedEvaluationEvidenceIntegrityException("reference projection not found")

    private fun queryProjectionRows(projectionId: UUID): List<RetainedReferenceProjectionRow> =
        jdbcTemplate.query(
            """
            SELECT position_hash, move_played, observation_count
            FROM human_move_corpus_projection_row
            WHERE projection_id = ?
            ORDER BY position_hash, move_played, observation_count
            """.trimIndent(),
            { rs, _ -> RetainedReferenceProjectionRow(rs.getString(1), rs.getString(2), rs.getInt(3)) },
            projectionId,
        )

    private fun validatePopulationMatchesProjection(
        population: EvaluationReferencePopulation,
        projection: RetainedReferenceProjection,
    ) {
        if (!matchesPopulationMetadata(population, projection) ||
            !matchesPopulationMetrics(population, projection)
        ) {
            throw RetainedEvaluationEvidenceIntegrityException("projection does not match retained reference population")
        }
    }

    private fun matchesPopulationMetadata(
        population: EvaluationReferencePopulation,
        projection: RetainedReferenceProjection,
    ): Boolean =
        population.contentDigest == projection.contentDigest &&
            population.sourceRunId == projection.sourceRunId &&
            population.calculationVersion == projection.calculationVersion

    private fun matchesPopulationMetrics(
        population: EvaluationReferencePopulation,
        projection: RetainedReferenceProjection,
    ): Boolean =
        population.prefixN == projection.prefixN &&
            population.ratingBand == projection.ratingBand &&
            population.minObservations == projection.minObservations

    private fun verifyArtifactEligibility(
        sourceRunId: UUID,
        population: EvaluationReferencePopulation,
    ) {
        val artifactEligible =
            jdbcTemplate.query(
                "SELECT COALESCE((manifest_json::jsonb ->> 'e6Eligible')::boolean, false), covered_prefix, " +
                    "manifest_json::jsonb ->> 'ratingBand', manifest_json::jsonb ->> 'sourceRunId', " +
                    "manifest_json::jsonb ->> 'coveredPrefix' " +
                    "FROM human_move_corpus_artifact_snapshot WHERE source_run_id = ? AND content_digest = ?",
                { rs, _ ->
                    rs.getBoolean(1) && rs.getInt(2) == population.coveredPrefix &&
                        rs.getString(3) == population.ratingBand &&
                        rs.getString(4) == sourceRunId.toString() &&
                        rs.getString(5) == population.coveredPrefix.toString()
                },
                sourceRunId,
                population.contentDigest,
            ).singleOrNull() == true
        if (!artifactEligible) {
            throw RetainedEvaluationEvidenceIntegrityException("selected artifact is not terminally eligible for reference coverage")
        }
    }

    private fun querySnapshotOccurrences(snapshot: EvaluationEvidenceSnapshot): Map<UUID, RetainedReferenceOccurrence> {
        val ids = snapshot.rows.map { it.occurrenceId }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        return jdbcTemplate.query(
            """
            SELECT id, source_run_id, qualifying_ordinal, pre_move_ply, provider_game_id,
                   position_hash, move_played, content_digest, covered_prefix
            FROM human_move_corpus_occurrence WHERE id IN ($placeholders)
            """.trimIndent(),
            { rs, _ ->
                rs.getObject("id", UUID::class.java) to
                    RetainedReferenceOccurrence(
                        rs.getObject("source_run_id", UUID::class.java),
                        rs.getInt("qualifying_ordinal"),
                        rs.getInt("pre_move_ply"),
                        rs.getString("provider_game_id"),
                        rs.getString("position_hash"),
                        rs.getString("move_played"),
                        rs.getString("content_digest"),
                        rs.getInt("covered_prefix"),
                    )
            },
            *ids.toTypedArray(),
        ).toMap()
    }
}
