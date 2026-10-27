package com.chessecho.service

import com.chessecho.humanmove.artifact.CorpusArtifactConflict
import com.chessecho.humanmove.artifact.HumanMoveCorpusArtifactCodec
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

private const val POSITION_HASH_ALGORITHM = "fen4-sha256"

/**
 * Client-supplied evaluated weakness sets. The digest checks consistency of
 * the canonical payload; its authenticity depends on an external trusted
 * channel for the expected digest. When contentDigest is omitted, the
 * verified source projection supplies its persisted artifact identity.
 */
data class HumanMoveCorpusWeaknessDataset(
    val cohort: String,
    val players: Map<String, List<String>>,
    val threshold: String,
    val sourceDigest: String,
    val contentDigest: String? = null,
    val hashAlgorithm: String = POSITION_HASH_ALGORITHM,
)

data class HumanMoveCorpusCrossCohortCompareRequest(
    val firstProjectionId: UUID,
    val secondProjectionId: UUID,
    val firstEvaluation: HumanMoveCorpusWeaknessDataset,
    val secondEvaluation: HumanMoveCorpusWeaknessDataset,
)

data class HumanMoveCorpusCrossCohortPlayerResult(
    val weaknessCount: Int,
    val sharedCount: Int,
    val coverage: Double?,
)

data class HumanMoveCorpusCrossCohortDirectionResult(
    val players: Map<String, HumanMoveCorpusCrossCohortPlayerResult>,
    val pooledCoverage: Double?,
)

data class HumanMoveCorpusCrossCohortCompareResponse(
    val firstToSecond: HumanMoveCorpusCrossCohortDirectionResult,
    val secondToFirst: HumanMoveCorpusCrossCohortDirectionResult,
)

/**
 * Read-only, directional (A->B and B->A) overlap between two independently
 * identified finalized, verified projections and two already-evaluated
 * per-player weakness datasets. Evaluation provenance is supplied externally;
 * this endpoint does not authenticate an independently issued evaluation.
 * Never re-runs engine analysis, never merges per-player sets or underlying
 * reference corpora, and is not a corpus similarity metric.
 */
@Service
class HumanMoveCorpusCrossCohortService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun compare(request: HumanMoveCorpusCrossCohortCompareRequest): HumanMoveCorpusCrossCohortCompareResponse {
        val first = loadFinalizedProjection(request.firstProjectionId)
        val second = loadFinalizedProjection(request.secondProjectionId)
        validateDataset(request.firstEvaluation, first.ratingBand, first.contentDigest)
        validateDataset(request.secondEvaluation, second.ratingBand, second.contentDigest)

        return HumanMoveCorpusCrossCohortCompareResponse(
            firstToSecond = direction(request.firstEvaluation, second.projectionId),
            secondToFirst = direction(request.secondEvaluation, first.projectionId),
        )
    }

    private data class FinalizedProjection(
        val projectionId: UUID,
        val ratingBand: String,
        val contentDigest: String,
    )

    private fun loadFinalizedProjection(id: UUID): FinalizedProjection {
        val row =
            jdbcTemplate.query(
                "SELECT rating_band, finalized, content_digest, verified FROM human_move_corpus_projection WHERE id = ?",
                { rs, _ ->
                    val ratingBand = rs.getString("rating_band")
                    val finalized = rs.getBoolean("finalized")
                    val verified = rs.getBoolean("verified")
                    val contentDigest = rs.getString("content_digest")
                    Triple(ratingBand, finalized to verified, contentDigest)
                },
                id,
            ).singleOrNull() ?: throw NoSuchElementException("Projection $id not found")
        val (ratingBand, flags, contentDigest) = row
        val (finalized, verified) = flags
        if (!finalized) {
            throw CorpusArtifactConflict("Projection $id is not finalized")
        }
        if (!verified) {
            throw CorpusArtifactConflict("Projection $id is not verified")
        }
        return FinalizedProjection(id, ratingBand, contentDigest)
    }

    private fun validateDataset(
        dataset: HumanMoveCorpusWeaknessDataset,
        expectedCohort: String,
        projectionContentDigest: String,
    ) {
        if (dataset.cohort != expectedCohort) {
            throw CorpusArtifactConflict("Evaluation dataset cohort '${dataset.cohort}' does not match projection cohort '$expectedCohort'")
        }
        val sourceContentDigest = dataset.contentDigest ?: projectionContentDigest
        if (sourceContentDigest != projectionContentDigest) {
            throw CorpusArtifactConflict("Evaluation contentDigest does not match its source projection")
        }
        if (dataset.hashAlgorithm != POSITION_HASH_ALGORITHM) {
            throw CorpusArtifactConflict("Evaluation position hash algorithm must be $POSITION_HASH_ALGORITHM")
        }
        val canonical =
            sortedMapOf<String, Any>(
                "cohort" to dataset.cohort,
                "players" to dataset.players.toSortedMap(),
                "threshold" to dataset.threshold,
            )
        if (HumanMoveCorpusArtifactCodec.sha(objectMapper.writeValueAsBytes(canonical)) != dataset.sourceDigest) {
            throw CorpusArtifactConflict("Evaluation dataset does not match its declared sourceDigest")
        }
    }

    private fun direction(
        dataset: HumanMoveCorpusWeaknessDataset,
        referenceProjectionId: UUID,
    ): HumanMoveCorpusCrossCohortDirectionResult {
        val players =
            dataset.players.mapValues { (_, hashes) ->
                val distinct = hashes.toSet()
                val weaknessCount = distinct.size

                val sharedCount =
                    distinct.chunked(MEMBERSHIP_BATCH_SIZE).sumOf { batch ->
                        val placeholders = List(batch.size) { "?" }.joinToString(",")
                        val sql =
                            """
                            SELECT COUNT(DISTINCT position_hash)
                            FROM human_move_corpus_projection_row
                            WHERE projection_id = ?
                              AND position_hash IN ($placeholders)
                            """.trimIndent()
                        jdbcTemplate.queryForObject(
                            sql,
                            Int::class.java,
                            referenceProjectionId,
                            *batch.toTypedArray(),
                        ) ?: throw IllegalStateException("Reference projection membership query returned no count")
                    }

                HumanMoveCorpusCrossCohortPlayerResult(
                    weaknessCount = weaknessCount,
                    sharedCount = sharedCount,
                    coverage = if (weaknessCount == 0) null else sharedCount.toDouble() / weaknessCount,
                )
            }
        val totalWeakness = players.values.sumOf { it.weaknessCount }
        val totalShared = players.values.sumOf { it.sharedCount }
        return HumanMoveCorpusCrossCohortDirectionResult(
            players = players,
            pooledCoverage = if (totalWeakness == 0) null else totalShared.toDouble() / totalWeakness,
        )
    }

    private companion object {
        const val MEMBERSHIP_BATCH_SIZE = 500
    }
}
