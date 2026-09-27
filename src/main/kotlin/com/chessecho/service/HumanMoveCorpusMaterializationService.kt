package com.chessecho.service

import com.chessecho.humanmove.artifact.CorpusArtifactConflict
import com.chessecho.repository.HumanMoveCorpusProjectionRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class HumanMoveCorpusMaterializeRequest(
    val contentDigest: String,
    val prefixN: Int,
    val minObservations: Int = 5,
)

data class HumanMoveCorpusMaterializeResponse(
    val projectionId: UUID,
    val contentDigest: String,
    val sourceRunId: UUID,
    val prefixN: Int,
    val ratingBand: String,
    val minObservations: Int,
    val distributionSha256: String,
)

/**
 * Materializes one independently addressable projection of imported raw
 * evidence, keyed by artifact digest, source corpus, prefix, cohort,
 * `minObservations`, and calculation version. Repeated materialization of
 * the same identity is idempotent and never accumulates or re-derives rows.
 */
@Service
class HumanMoveCorpusMaterializationService(
    private val jdbcTemplate: JdbcTemplate,
    private val projectionRepository: HumanMoveCorpusProjectionRepository,
    private val finalizationService: HumanMoveCorpusProjectionFinalizationService,
) {
    @Transactional
    fun materialize(request: HumanMoveCorpusMaterializeRequest): HumanMoveCorpusMaterializeResponse {
        require(request.prefixN >= 1) { "prefixN must be >= 1 (got ${request.prefixN})" }
        require(request.minObservations >= 1) { "minObservations must be >= 1 (got ${request.minObservations})" }

        val snapshot =
            jdbcTemplate.query(
                "SELECT source_run_id, covered_prefix FROM human_move_corpus_artifact_snapshot WHERE content_digest = ?",
                { rs, _ -> rs.getObject("source_run_id", UUID::class.java) to rs.getInt("covered_prefix") },
                request.contentDigest,
            ).singleOrNull() ?: throw NoSuchElementException("Artifact snapshot ${request.contentDigest} not found")
        val (sourceRunId, coveredPrefix) = snapshot
        HumanMoveCorpusImportService.lockSource(jdbcTemplate, sourceRunId)
        require(request.prefixN <= coveredPrefix) {
            "prefixN (${request.prefixN}) exceeds this artifact snapshot's covered prefix ($coveredPrefix)"
        }
        val ratingBand =
            jdbcTemplate.queryForObject(
                "SELECT rating_band FROM human_move_corpus_import WHERE source_run_id = ?",
                String::class.java,
                sourceRunId,
            ) ?: throw NoSuchElementException("Corpus run $sourceRunId is not imported")

        val existing =
            projectionRepository.findByContentDigestAndSourceRunIdAndPrefixNAndRatingBandAndMinObservationsAndCalculationVersion(
                request.contentDigest,
                sourceRunId,
                request.prefixN,
                ratingBand,
                request.minObservations,
                CALCULATION_VERSION,
            )
        if (existing != null) {
            return HumanMoveCorpusMaterializeResponse(
                existing.id,
                request.contentDigest,
                sourceRunId,
                request.prefixN,
                ratingBand,
                request.minObservations,
                existing.distributionSha256
                    ?: throw CorpusArtifactConflict("Projection ${existing.id} has no retained distribution digest"),
            )
        }
        val availableGames =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM human_move_corpus_imported_game WHERE source_run_id = ? AND qualifying_ordinal <= ?",
                Int::class.java,
                sourceRunId,
                request.prefixN,
            )!!
        if (availableGames != request.prefixN) {
            throw CorpusArtifactConflict(
                "Imported raw evidence for run $sourceRunId prefix ${request.prefixN} is unavailable or incomplete",
            )
        }

        val projectionId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO human_move_corpus_projection (
                id, content_digest, source_run_id, prefix_n, rating_band, min_observations, calculation_version,
                distribution_sha256, finalized, verified
            ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, false, false)
            """.trimIndent(),
            projectionId,
            request.contentDigest,
            sourceRunId,
            request.prefixN,
            ratingBand,
            request.minObservations,
            CALCULATION_VERSION,
        )
        val insertSql =
            "INSERT INTO human_move_corpus_projection_row " +
                "(id, projection_id, position_hash, move_played, observation_count) VALUES (?, ?, ?, ?, ?)"
        val batch = ArrayList<Array<Any>>(500)
        jdbcTemplate.query(
            {
                it.prepareStatement(
                    """
                    SELECT o.position_hash, o.move_played, SUM(o.observation_count) AS observation_count
                    FROM human_move_corpus_imported_observation o
                    JOIN human_move_corpus_imported_game g ON g.id = o.game_id
                    WHERE g.source_run_id = ? AND g.qualifying_ordinal <= ?
                    GROUP BY o.position_hash, o.move_played
                    """.trimIndent(),
                ).apply {
                    fetchSize = 500
                    setObject(1, sourceRunId)
                    setInt(2, request.prefixN)
                }
            },
            RowCallbackHandler { rs ->
                batch.add(
                    arrayOf(
                        UUID.randomUUID(),
                        projectionId,
                        rs.getString("position_hash"),
                        rs.getString("move_played"),
                        Math.toIntExact(rs.getLong("observation_count")),
                    ),
                )
                if (batch.size == 500) {
                    jdbcTemplate.batchUpdate(insertSql, batch)
                    batch.clear()
                }
            },
        )
        if (batch.isNotEmpty()) jdbcTemplate.batchUpdate(insertSql, batch)
        val expectedDigest = finalizationService.expectedDigest(sourceRunId, request.prefixN, request.minObservations)
        jdbcTemplate.update(
            "UPDATE human_move_corpus_projection SET distribution_sha256 = ? WHERE id = ?",
            expectedDigest,
            projectionId,
        )
        return HumanMoveCorpusMaterializeResponse(
            projectionId,
            request.contentDigest,
            sourceRunId,
            request.prefixN,
            ratingBand,
            request.minObservations,
            expectedDigest,
        )
    }

    companion object {
        const val CALCULATION_VERSION = "human-move-corpus-projection-v1"
    }
}
