package com.chessecho.service

import com.chessecho.humanmove.artifact.HumanMoveCorpusArtifactService
import com.chessecho.humanmove.artifact.HumanMoveCorpusGameRecord
import com.chessecho.humanmove.artifact.HumanMoveCorpusObservationRecord
import com.chessecho.humanmove.artifact.VerifiedCorpusArtifact
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.ResultSet
import java.util.UUID

data class HumanMoveCorpusOccurrenceBinding(
    val sourceRunId: UUID,
    val contentDigest: String,
    val coveredPrefix: Int,
    val occurrenceDigest: String,
    val occurrenceCount: Int,
)

/**
 * Verifies, binds, and exposes the immutable #431 location evidence associated
 * with one verified #426 snapshot. Occurrences remain independent of purgeable
 * imported rows and are never added to the v1 artifact.
 */
@Service
class HumanMoveCorpusOccurrenceService(
    private val jdbcTemplate: JdbcTemplate,
    private val artifactService: HumanMoveCorpusArtifactService,
) {
    @Transactional
    fun verifyImportEvidence(
        artifact: VerifiedCorpusArtifact,
        archive: Path,
    ) {
        val manifest = artifact.manifest
        if (!hasSourceRun(manifest.sourceRunId)) return

        verifySourceRunMetadata(artifact)
        verifySourcePrefix(manifest.sourceRunId, manifest.coveredPrefix)
        verifyArchiveGames(archive, manifest.sourceRunId, manifest.coveredPrefix)
        verifyArchiveAggregate(archive, manifest.sourceRunId, manifest.coveredPrefix)

        val binding = findBinding(manifest.sourceRunId) ?: return
        verifyBindingTarget(binding)
        if (binding.contentDigest == artifact.digest && binding.coveredPrefix != manifest.coveredPrefix) {
            throw HumanMoveCorpusIntegrityException(
                "Occurrence evidence for run ${manifest.sourceRunId} has a mismatched bound prefix",
            )
        }
        if (binding.contentDigest == artifact.digest && binding.coveredPrefix == manifest.coveredPrefix) {
            verifyRequiredBinding(manifest.sourceRunId, artifact.digest, manifest.coveredPrefix)
        }
    }

    @Transactional
    fun bindFinalizedEvidence(
        artifact: VerifiedCorpusArtifact,
        archive: Path,
    ): HumanMoveCorpusOccurrenceBinding? {
        val manifest = artifact.manifest
        val runId = manifest.sourceRunId
        if (!hasSourceRun(runId)) return null

        verifyImportEvidence(artifact, archive)
        val existing = findBinding(runId)
        if (existing != null) {
            return if (existing.contentDigest == artifact.digest && existing.coveredPrefix == manifest.coveredPrefix) {
                verifyRequiredBinding(runId, artifact.digest, manifest.coveredPrefix)
            } else {
                null
            }
        }

        val updated =
            jdbcTemplate.update(
                """
                UPDATE human_move_corpus_occurrence
                SET content_digest = ?, covered_prefix = ?
                WHERE source_run_id = ? AND qualifying_ordinal <= ?
                """.trimIndent(),
                artifact.digest,
                manifest.coveredPrefix,
                runId,
                manifest.coveredPrefix,
            )
        val occurrenceCount = countOccurrences(runId, manifest.coveredPrefix)
        if (updated.toLong() != occurrenceCount || occurrenceCount == 0L) {
            throw HumanMoveCorpusIntegrityException(
                "Occurrence evidence for run $runId is incomplete for prefix ${manifest.coveredPrefix}",
            )
        }

        val digest = occurrenceDigest(runId, manifest.coveredPrefix)
        jdbcTemplate.update(
            """
            INSERT INTO human_move_corpus_occurrence_binding
                (id, source_run_id, content_digest, covered_prefix, occurrence_digest, occurrence_count, finalized_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """.trimIndent(),
            UUID.randomUUID(),
            runId,
            artifact.digest,
            manifest.coveredPrefix,
            digest,
            Math.toIntExact(occurrenceCount),
        )
        return if (manifest.e6Eligible) {
            verifyRequiredBinding(runId, artifact.digest, manifest.coveredPrefix)
        } else {
            findBinding(runId)
        }
    }

    /**
     * Required retained occurrence-binding verification. A valid binding may
     * represent a partial, nonterminal source prefix.
     */
    @Transactional(readOnly = true)
    fun verifyRequiredBinding(
        sourceRunId: UUID,
        contentDigest: String,
        coveredPrefix: Int,
    ): HumanMoveCorpusOccurrenceBinding {
        val binding =
            findBinding(sourceRunId)
                ?: throw HumanMoveCorpusIntegrityException(
                    "Run $sourceRunId has no finalized occurrence binding",
                )
        verifyBindingTarget(binding)
        if (binding.contentDigest != contentDigest || binding.coveredPrefix != coveredPrefix) {
            throw HumanMoveCorpusIntegrityException(
                "Run $sourceRunId occurrence binding does not match the requested artifact and covered prefix",
            )
        }

        val count = countOccurrences(sourceRunId, coveredPrefix)
        val taggedCount =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM human_move_corpus_occurrence
                WHERE source_run_id = ? AND qualifying_ordinal <= ?
                  AND content_digest = ? AND covered_prefix = ?
                """.trimIndent(),
                Long::class.java,
                sourceRunId,
                coveredPrefix,
                contentDigest,
                coveredPrefix,
            )!!
        val digest = occurrenceDigest(sourceRunId, coveredPrefix)
        if (count != binding.occurrenceCount.toLong() || taggedCount != count || digest != binding.occurrenceDigest) {
            throw HumanMoveCorpusIntegrityException(
                "Run $sourceRunId occurrence rows no longer match their finalized binding",
            )
        }
        verifySourcePrefix(sourceRunId, coveredPrefix)
        return binding
    }

    @Transactional(readOnly = true)
    fun hasSourceRun(sourceRunId: UUID): Boolean =
        jdbcTemplate.query(
            "SELECT id FROM human_move_corpus_run WHERE id = ?",
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            sourceRunId,
        ).isNotEmpty()

    @Transactional(readOnly = true)
    fun verifyBindingIfPresent(
        sourceRunId: UUID,
        contentDigest: String,
        coveredPrefix: Int,
    ) {
        val binding = findBinding(sourceRunId) ?: return
        verifyBindingTarget(binding)
        if (binding.contentDigest == contentDigest && binding.coveredPrefix != coveredPrefix) {
            throw HumanMoveCorpusIntegrityException(
                "Run $sourceRunId occurrence binding has a mismatched covered prefix",
            )
        }
        if (binding.contentDigest == contentDigest) {
            verifyRequiredBinding(sourceRunId, contentDigest, coveredPrefix)
        }
    }

    private fun verifySourcePrefix(
        sourceRunId: UUID,
        coveredPrefix: Int,
    ) {
        val prefix =
            jdbcTemplate.query(
                """
                SELECT r.committed_frontier,
                       COUNT(g.id),
                       COALESCE(MIN(g.qualifying_ordinal), 0),
                       COALESCE(MAX(g.qualifying_ordinal), 0),
                       COALESCE(SUM(g.observation_total), 0)
                FROM human_move_corpus_run r
                LEFT JOIN human_move_corpus_game g
                  ON g.run_id = r.id AND g.qualifying_ordinal <= ?
                WHERE r.id = ?
                GROUP BY r.committed_frontier
                """.trimIndent(),
                { rs, _ ->
                    SourcePrefix(
                        frontier = rs.getInt(1),
                        gameCount = rs.getLong(2),
                        minimum = rs.getInt(3),
                        maximum = rs.getInt(4),
                        expectedOccurrences = rs.getLong(5),
                    )
                },
                coveredPrefix,
                sourceRunId,
            ).singleOrNull()
                ?: throw HumanMoveCorpusIntegrityException("Source corpus run $sourceRunId is missing")
        val (frontier, gameCount, minimum, maximum, expectedOccurrences) = prefix
        val occurrenceCount = countOccurrences(sourceRunId, coveredPrefix)
        if (coveredPrefix < 1 || coveredPrefix > frontier ||
            gameCount != coveredPrefix.toLong() || minimum != 1 || maximum != coveredPrefix ||
            occurrenceCount == 0L || occurrenceCount != expectedOccurrences
        ) {
            throw HumanMoveCorpusIntegrityException(
                "Run $sourceRunId does not have complete occurrence evidence for prefix $coveredPrefix",
            )
        }

        val sourceDiverges =
            jdbcTemplate.queryForObject(
                """
                WITH occurrence_aggregate AS (
                    SELECT qualifying_ordinal, position_hash, move_played, COUNT(*)::BIGINT AS occurrence_count
                    FROM human_move_corpus_occurrence
                    WHERE source_run_id = ? AND qualifying_ordinal <= ?
                    GROUP BY qualifying_ordinal, position_hash, move_played
                ), source_aggregate AS (
                    SELECT g.qualifying_ordinal, o.position_hash, o.move_played,
                           SUM(o.observation_count)::BIGINT AS occurrence_count
                    FROM human_move_corpus_observation o
                    JOIN human_move_corpus_game g ON g.id = o.game_id
                    WHERE g.run_id = ? AND g.qualifying_ordinal <= ?
                    GROUP BY g.qualifying_ordinal, o.position_hash, o.move_played
                )
                SELECT EXISTS (
                    (SELECT * FROM occurrence_aggregate EXCEPT SELECT * FROM source_aggregate)
                    UNION ALL
                    (SELECT * FROM source_aggregate EXCEPT SELECT * FROM occurrence_aggregate)
                )
                """.trimIndent(),
                Boolean::class.java,
                sourceRunId,
                coveredPrefix,
                sourceRunId,
                coveredPrefix,
            )!!
        val mismatchedIdentity =
            jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM human_move_corpus_occurrence o
                    LEFT JOIN human_move_corpus_game g
                      ON g.run_id = o.source_run_id
                     AND g.qualifying_ordinal = o.qualifying_ordinal
                    WHERE o.source_run_id = ? AND o.qualifying_ordinal <= ?
                      AND (g.id IS NULL OR g.provider_game_id IS DISTINCT FROM o.provider_game_id)
                )
                """.trimIndent(),
                Boolean::class.java,
                sourceRunId,
                coveredPrefix,
            )!!
        if (sourceDiverges || mismatchedIdentity) {
            throw HumanMoveCorpusIntegrityException(
                "Run $sourceRunId occurrence evidence diverges from its immutable source contribution",
            )
        }
    }

    private fun verifySourceRunMetadata(artifact: VerifiedCorpusArtifact) {
        val runId = artifact.manifest.sourceRunId
        val metadata = artifact.manifest.sourceRunMetadata
        val source =
            jdbcTemplate.query(
                """
                SELECT rating_band, algorithm_version, source_revision, request_json, request_sha256
                FROM human_move_corpus_run WHERE id = ?
                """.trimIndent(),
                { rs, _ ->
                    listOf(
                        rs.getString("rating_band"),
                        rs.getString("algorithm_version"),
                        rs.getString("source_revision"),
                        rs.getString("request_json"),
                        rs.getString("request_sha256"),
                    )
                },
                runId,
            ).singleOrNull() ?: throw HumanMoveCorpusIntegrityException("Source corpus run $runId is missing")
        val declared =
            listOf(
                artifact.manifest.ratingBand,
                metadata.path("algorithmVersion").asText(null),
                metadata.path("sourceRevision").asText(null),
                metadata.path("requestJson").asText(null),
                metadata.path("requestSha256").asText(null),
            )
        if (source != declared) {
            throw HumanMoveCorpusIntegrityException("Artifact metadata diverges from source corpus run $runId")
        }
    }

    private fun verifyArchiveGames(
        archive: Path,
        sourceRunId: UUID,
        coveredPrefix: Int,
    ) {
        jdbcTemplate.query(
            { connection ->
                connection.prepareStatement(
                    """
                    SELECT qualifying_ordinal, provider_game_id, traversed_player, opponent, opponent_side,
                           opponent_rating, rules, time_class, bfs_depth, pgn_sha256, observation_total,
                           distinct_move_count, committed_at
                    FROM human_move_corpus_game
                    WHERE run_id = ? AND qualifying_ordinal <= ? ORDER BY qualifying_ordinal
                    """.trimIndent(),
                ).apply {
                    fetchSize = 64
                    setObject(1, sourceRunId)
                    setInt(2, coveredPrefix)
                }
            },
            ResultSetExtractor { rows ->
                artifactService.forEachGame(archive) { game ->
                    if (game.qualifyingOrdinal <= coveredPrefix &&
                        (!rows.next() || !sameGame(rows, game))
                    ) {
                        throw HumanMoveCorpusIntegrityException(
                            "Artifact game identity diverges from occurrence source run $sourceRunId",
                        )
                    }
                }
                if (rows.next()) {
                    throw HumanMoveCorpusIntegrityException(
                        "Artifact game prefix does not cover source run $sourceRunId",
                    )
                }
                null
            },
        )
    }

    private fun verifyArchiveAggregate(
        archive: Path,
        sourceRunId: UUID,
        coveredPrefix: Int,
    ) {
        jdbcTemplate.query(
            { connection ->
                connection.prepareStatement(
                    """
                    SELECT qualifying_ordinal, position_hash, move_played, COUNT(*) AS occurrence_count
                    FROM human_move_corpus_occurrence
                    WHERE source_run_id = ? AND qualifying_ordinal <= ?
                    GROUP BY qualifying_ordinal, position_hash, move_played
                    ORDER BY qualifying_ordinal, position_hash, move_played
                    """.trimIndent(),
                ).apply {
                    fetchSize = 512
                    setObject(1, sourceRunId)
                    setInt(2, coveredPrefix)
                }
            },
            ResultSetExtractor { rows ->
                artifactService.forEachObservation(archive) { observation ->
                    if (observation.qualifyingOrdinal <= coveredPrefix &&
                        (!rows.next() || !sameAggregate(rows, observation))
                    ) {
                        throw HumanMoveCorpusIntegrityException(
                            "Artifact aggregate diverges from occurrence evidence for run $sourceRunId",
                        )
                    }
                }
                if (rows.next()) {
                    throw HumanMoveCorpusIntegrityException(
                        "Artifact aggregate omits occurrence evidence for run $sourceRunId",
                    )
                }
                null
            },
        )
    }

    private fun countOccurrences(
        sourceRunId: UUID,
        coveredPrefix: Int,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM human_move_corpus_occurrence
            WHERE source_run_id = ? AND qualifying_ordinal <= ?
            """.trimIndent(),
            Long::class.java,
            sourceRunId,
            coveredPrefix,
        )!!

    private fun sameGame(
        rows: ResultSet,
        game: HumanMoveCorpusGameRecord,
    ): Boolean =
        rows.getInt("qualifying_ordinal") == game.qualifyingOrdinal &&
            rows.getString("provider_game_id") == game.providerGameId &&
            rows.getString("traversed_player") == game.traversedPlayer &&
            rows.getString("opponent") == game.opponent &&
            rows.getString("opponent_side") == game.opponentSide &&
            rows.getInt("opponent_rating") == game.opponentRating &&
            rows.getString("rules") == game.rules &&
            rows.getString("time_class") == game.timeClass &&
            rows.getInt("bfs_depth") == game.bfsDepth &&
            rows.getString("pgn_sha256") == game.pgnSha256 &&
            rows.getInt("observation_total") == game.observationTotal &&
            rows.getInt("distinct_move_count") == game.distinctMoveCount &&
            rows.getTimestamp("committed_at").toInstant() == game.committedAt

    private fun sameAggregate(
        rows: ResultSet,
        observation: HumanMoveCorpusObservationRecord,
    ): Boolean =
        rows.getInt("qualifying_ordinal") == observation.qualifyingOrdinal &&
            rows.getString("position_hash") == observation.positionHash &&
            rows.getString("move_played") == observation.movePlayed &&
            rows.getLong("occurrence_count") == observation.observationCount.toLong()

    private fun occurrenceDigest(
        sourceRunId: UUID,
        coveredPrefix: Int,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        jdbcTemplate.query(
            {
                it.prepareStatement(
                    """
                    SELECT source_run_id, qualifying_ordinal, provider_game_id, pre_move_ply,
                           move_played, position_hash, content_digest, covered_prefix
                    FROM human_move_corpus_occurrence
                    WHERE source_run_id = ? AND qualifying_ordinal <= ?
                    ORDER BY qualifying_ordinal, pre_move_ply
                    """.trimIndent(),
                ).apply {
                    fetchSize = 512
                    setObject(1, sourceRunId)
                    setInt(2, coveredPrefix)
                }
            },
            ResultSetExtractor { rows ->
                while (rows.next()) updateDigest(digest, rows)
                null
            },
        )
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateDigest(
        digest: MessageDigest,
        row: ResultSet,
    ) {
        val values =
            listOf(
                row.getString("source_run_id"),
                row.getInt("qualifying_ordinal").toString(),
                row.getString("provider_game_id"),
                row.getInt("pre_move_ply").toString(),
                row.getString("move_played"),
                row.getString("position_hash"),
                row.getString("content_digest"),
                row.getInt("covered_prefix").toString(),
            )
        values.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
    }

    private fun findBinding(sourceRunId: UUID): HumanMoveCorpusOccurrenceBinding? =
        jdbcTemplate.query(
            """
            SELECT source_run_id, content_digest, covered_prefix, occurrence_digest, occurrence_count
            FROM human_move_corpus_occurrence_binding WHERE source_run_id = ?
            """.trimIndent(),
            { rs, _ ->
                HumanMoveCorpusOccurrenceBinding(
                    rs.getObject("source_run_id", UUID::class.java),
                    rs.getString("content_digest"),
                    rs.getInt("covered_prefix"),
                    rs.getString("occurrence_digest"),
                    rs.getInt("occurrence_count"),
                )
            },
            sourceRunId,
        ).singleOrNull()

    private fun verifyBindingTarget(binding: HumanMoveCorpusOccurrenceBinding) {
        val exists =
            jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM human_move_corpus_artifact_snapshot
                    WHERE source_run_id = ? AND content_digest = ? AND covered_prefix = ?
                )
                """.trimIndent(),
                Boolean::class.java,
                binding.sourceRunId,
                binding.contentDigest,
                binding.coveredPrefix,
            )!!
        if (!exists) {
            throw HumanMoveCorpusIntegrityException(
                "Run ${binding.sourceRunId} occurrence binding references a missing or mismatched artifact snapshot",
            )
        }
    }

    private data class SourcePrefix(
        val frontier: Int,
        val gameCount: Long,
        val minimum: Int,
        val maximum: Int,
        val expectedOccurrences: Long,
    )
}
