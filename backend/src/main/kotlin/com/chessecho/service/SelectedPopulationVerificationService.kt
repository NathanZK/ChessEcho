package com.chessecho.service

import com.chessecho.domain.HumanMoveCorpusProjection
import com.chessecho.repository.HumanMoveCorpusProjectionRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class VerifiedSelectedPopulationContext(
    val population: EvaluationReferencePopulation,
    val projection: HumanMoveCorpusProjection,
    val binding: HumanMoveCorpusOccurrenceBinding,
    val occurrences: Map<UUID, RetainedReferenceOccurrence>,
)

@Service
class SelectedPopulationVerificationService(
    private val projectionRepository: HumanMoveCorpusProjectionRepository,
    private val projectionFinalizationService: HumanMoveCorpusProjectionFinalizationService,
    private val occurrenceService: HumanMoveCorpusOccurrenceService,
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional(
        readOnly = true,
        isolation = Isolation.REPEATABLE_READ,
    )
    fun verify(
        selectedPopulation: EvaluationReferencePopulation,
        occurrenceIds: Set<UUID>,
    ): VerifiedSelectedPopulationContext {
        if (selectedPopulation.prefixN <= 0 || selectedPopulation.coveredPrefix < selectedPopulation.prefixN) {
            throw RetainedEvaluationEvidenceIntegrityException("selected population has an invalid prefix scope")
        }
        val selectedDigest =
            selectedPopulation.distributionSha256
                ?: throw RetainedEvaluationEvidenceIntegrityException("selected population distribution digest is missing")
        val projection =
            projectionRepository.findByContentDigestAndSourceRunIdAndPrefixNAndRatingBandAndMinObservationsAndCalculationVersion(
                selectedPopulation.contentDigest,
                selectedPopulation.sourceRunId,
                selectedPopulation.prefixN,
                selectedPopulation.ratingBand,
                selectedPopulation.minObservations,
                selectedPopulation.calculationVersion,
            ) ?: throw RetainedEvaluationEvidenceIntegrityException("selected population projection not found")
        if (!matchesIdentity(projection, selectedPopulation)) {
            throw RetainedEvaluationEvidenceIntegrityException("projection does not match the selected population identity")
        }
        if (!projection.finalized || !projection.verified) {
            throw RetainedEvaluationEvidenceIntegrityException("selected population projection is not finalized and verified")
        }
        val storedDigest =
            projection.distributionSha256
                ?: throw RetainedEvaluationEvidenceIntegrityException("selected population projection digest is missing")
        if (storedDigest != selectedDigest) {
            throw RetainedEvaluationEvidenceIntegrityException("selected population digest does not match the projection")
        }
        if (projectionFinalizationService.digestProjection(projection.id).distributionSha256 != storedDigest) {
            throw RetainedEvaluationEvidenceIntegrityException("selected population projection rows do not match their digest")
        }

        val binding =
            occurrenceService.verifyRequiredBinding(
                selectedPopulation.sourceRunId,
                selectedPopulation.contentDigest,
                selectedPopulation.coveredPrefix,
            )
        val occurrences = resolveOccurrences(selectedPopulation, occurrenceIds)
        return VerifiedSelectedPopulationContext(selectedPopulation, projection, binding, occurrences)
    }

    private fun matchesIdentity(
        projection: HumanMoveCorpusProjection,
        population: EvaluationReferencePopulation,
    ): Boolean =
        projection.contentDigest == population.contentDigest &&
            projection.sourceRunId == population.sourceRunId &&
            projection.prefixN == population.prefixN &&
            projection.ratingBand == population.ratingBand &&
            projection.minObservations == population.minObservations &&
            projection.calculationVersion == population.calculationVersion

    private fun resolveOccurrences(
        population: EvaluationReferencePopulation,
        occurrenceIds: Set<UUID>,
    ): Map<UUID, RetainedReferenceOccurrence> {
        if (occurrenceIds.isEmpty()) return emptyMap()

        val ids = occurrenceIds.toList()
        val placeholders = ids.joinToString(",") { "?" }
        val parameters = ArrayList<Any>(ids.size + 4)
        parameters.addAll(ids)
        parameters.add(population.sourceRunId)
        parameters.add(population.contentDigest)
        parameters.add(population.coveredPrefix)
        parameters.add(population.prefixN)
        val occurrences =
            jdbcTemplate.query(
                """
                SELECT id, source_run_id, qualifying_ordinal, pre_move_ply, provider_game_id,
                       position_hash, move_played, content_digest, covered_prefix
                FROM human_move_corpus_occurrence
                WHERE id IN ($placeholders)
                  AND source_run_id = ?
                  AND content_digest = ?
                  AND covered_prefix = ?
                  AND qualifying_ordinal <= ?
                """.trimIndent(),
                { rs, _ ->
                    rs.getObject("id", UUID::class.java) to
                        RetainedReferenceOccurrence(
                            sourceRunId = rs.getObject("source_run_id", UUID::class.java),
                            qualifyingOrdinal = rs.getInt("qualifying_ordinal"),
                            preMovePly = rs.getInt("pre_move_ply"),
                            providerGameId = rs.getString("provider_game_id"),
                            positionHash = rs.getString("position_hash"),
                            movePlayed = rs.getString("move_played"),
                            contentDigest = rs.getString("content_digest"),
                            coveredPrefix = rs.getInt("covered_prefix"),
                        )
                },
                *parameters.toTypedArray(),
            ).toMap()
        if (occurrences.keys != occurrenceIds) {
            throw RetainedEvaluationEvidenceIntegrityException("requested occurrences are missing or outside the selected prefix")
        }
        return occurrences
    }
}
