package com.chessecho.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.UUID

data class E6ProjectionRow(
    val positionHash: String,
    val movePlayed: String,
    val observationCount: Int,
)

data class E6ReferenceProjection(
    val finalized: Boolean,
    val verified: Boolean,
    val distributionSha256: String?,
    val rows: List<E6ProjectionRow>,
    val contentDigest: String? = null,
    val sourceRunId: UUID? = null,
    val prefixN: Int? = null,
    val ratingBand: String? = null,
    val minObservations: Int? = null,
    val calculationVersion: String? = null,
)

data class E6ReferenceOccurrence(
    val sourceRunId: UUID,
    val qualifyingOrdinal: Int,
    val preMovePly: Int,
    val providerGameId: String,
    val positionHash: String,
    val movePlayed: String,
    val contentDigest: String,
    val coveredPrefix: Int,
)

data class E6AdmissionEvidence(
    val referencePopulation: EvaluationReferencePopulation,
    val projection: E6ReferenceProjection,
    val snapshot: EvaluationEvidenceSnapshot,
    val occurrences: Map<UUID, E6ReferenceOccurrence>,
    val analysisVersion: String,
) {
    val referencePositions: Set<String>
        get() = projection.rows.map { it.positionHash }.toSet()

    val resolvedOccurrences: Map<UUID, E6ReferenceOccurrence>
        get() = occurrences

    fun toAnalysisInput(): E6AnalysisInput {
        E6AdmissionValidator().admit(this)
        return E6AnalysisInput(
            referencePositions = referencePositions,
            players =
                snapshot.players.associate { player ->
                    val key = player.identity
                    key to player
                },
            rows =
                snapshot.rows.map { row ->
                    val occurrence =
                        occurrences[row.occurrenceId]
                            ?: throw E6AnalysisEvidenceIntegrityException(
                                "missing authoritative occurrence ${row.occurrenceId}",
                            )
                    E6AnalysisRow(
                        playerId = row.playerId,
                        gameId = row.gameId,
                        occurrenceId = row.occurrenceId,
                        positionIdentity = row.positionIdentity,
                        loss = row.loss,
                        verifiedOccurrence = true,
                        canonicalLocation =
                            E6CanonicalLocation(
                                occurrence.sourceRunId,
                                occurrence.qualifyingOrdinal,
                                occurrence.preMovePly,
                                occurrence.movePlayed,
                            ),
                    )
                },
            analysisVersion = analysisVersion,
        )
    }
}

class E6AnalysisEvidenceIntegrityException(message: String) : RuntimeException(message)

class E6AdmissionValidator {
    fun admit(evidence: E6AdmissionEvidence): E6AdmissionEvidence {
        val population = evidence.referencePopulation
        val projection = evidence.projection
        if (!projection.finalized || !projection.verified || projection.distributionSha256 == null) {
            throw E6AnalysisEvidenceIntegrityException("reference projection is not finalized, verified, and digested")
        }
        if (projectionDigest(projection.rows) != projection.distributionSha256) {
            throw E6AnalysisEvidenceIntegrityException("reference projection distribution digest mismatch")
        }
        if (evidence.snapshot.referencePopulation != population ||
            population.distributionSha256 != null && population.distributionSha256 != projection.distributionSha256
        ) {
            throw E6AnalysisEvidenceIntegrityException("retained evidence does not match the selected reference population")
        }
        val players = evidence.snapshot.players
        if (players.isEmpty() || players.map { it.id }.toSet().size != players.size ||
            players.map { it.identity }.toSet().size != players.size || players.any { it.identity.isBlank() }
        ) {
            throw E6AnalysisEvidenceIntegrityException("declared E6 player identities are not unique")
        }
        if (evidence.analysisVersion.isBlank()) {
            throw E6AnalysisEvidenceIntegrityException("analysis version must not be blank")
        }
        val declaredIds = players.map { it.id }.toSet()
        evidence.snapshot.rows.forEach { row ->
            val occurrence =
                evidence.occurrences[row.occurrenceId]
                    ?: throw E6AnalysisEvidenceIntegrityException("missing authoritative occurrence ${row.occurrenceId}")
            if (row.playerId !in declaredIds ||
                occurrence.sourceRunId != population.sourceRunId ||
                occurrence.qualifyingOrdinal !in 1..population.coveredPrefix ||
                occurrence.providerGameId.isBlank() ||
                occurrence.contentDigest != population.contentDigest ||
                occurrence.coveredPrefix != population.coveredPrefix ||
                occurrence.positionHash != row.positionIdentity ||
                occurrence.preMovePly != row.preMovePly ||
                occurrence.movePlayed.isBlank()
            ) {
                throw E6AnalysisEvidenceIntegrityException("occurrence ${row.occurrenceId} does not match retained evidence")
            }
        }
        return evidence
    }

    private fun projectionDigest(rows: List<E6ProjectionRow>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(rows.joinToString("") { "${it.positionHash}\t${it.movePlayed}\t${it.observationCount}\n" }.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

@Service
class E6AnalysisEvidenceService(
    private val jdbcTemplate: JdbcTemplate,
    private val snapshotService: EvaluationEvidenceSnapshotService,
    private val occurrenceService: HumanMoveCorpusOccurrenceService,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun loadAndAdmit(
        sourceRunId: UUID,
        projectionId: UUID,
        analysisVersion: String,
    ): E6AdmissionEvidence {
        val snapshot = snapshotService.readVerifiedPersisted(sourceRunId)
        val projection =
            jdbcTemplate.query(
                """
                SELECT finalized, verified, distribution_sha256, content_digest, source_run_id, prefix_n,
                       rating_band, min_observations, calculation_version
                FROM human_move_corpus_projection
                WHERE id = ?
                """.trimIndent(),
                { rs, _ ->
                    E6ReferenceProjection(
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
            ).singleOrNull() ?: throw E6AnalysisEvidenceIntegrityException("reference projection not found")
        val rows =
            jdbcTemplate.query(
                """
                SELECT position_hash, move_played, observation_count
                FROM human_move_corpus_projection_row
                WHERE projection_id = ?
                ORDER BY position_hash, move_played, observation_count
                """.trimIndent(),
                { rs, _ -> E6ProjectionRow(rs.getString(1), rs.getString(2), rs.getInt(3)) },
                projectionId,
            )
        val projectionWithRows = projection.copy(rows = rows)
        val population = snapshot.referencePopulation
        if (population.contentDigest != projection.contentDigest ||
            population.sourceRunId != projection.sourceRunId ||
            population.prefixN != projection.prefixN ||
            population.ratingBand != projection.ratingBand ||
            population.minObservations != projection.minObservations ||
            population.calculationVersion != projection.calculationVersion
        ) {
            throw E6AnalysisEvidenceIntegrityException("projection does not match retained reference population")
        }
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
            throw E6AnalysisEvidenceIntegrityException("selected artifact is not expanded-E6 eligible")
        }
        occurrenceService.verifyExpandedE6Eligibility(sourceRunId, population.contentDigest, population.coveredPrefix)
        val ids = snapshot.rows.map { it.occurrenceId }.distinct()
        val occurrences =
            if (ids.isEmpty()) {
                emptyMap()
            } else {
                val placeholders = ids.joinToString(",") { "?" }
                jdbcTemplate.query(
                    """
                    SELECT id, source_run_id, qualifying_ordinal, pre_move_ply, provider_game_id,
                           position_hash, move_played, content_digest, covered_prefix
                    FROM human_move_corpus_occurrence WHERE id IN ($placeholders)
                    """.trimIndent(),
                    { rs, _ ->
                        rs.getObject("id", UUID::class.java) to
                            E6ReferenceOccurrence(
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
        return E6AdmissionValidator().admit(
            E6AdmissionEvidence(population, projectionWithRows, snapshot, occurrences, analysisVersion),
        )
    }
}
