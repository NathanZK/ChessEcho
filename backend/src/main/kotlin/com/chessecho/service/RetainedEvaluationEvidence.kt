package com.chessecho.service

import java.security.MessageDigest
import java.util.UUID

data class RetainedReferenceProjectionRow(
    val positionHash: String,
    val movePlayed: String,
    val observationCount: Int,
)

data class RetainedReferenceProjection(
    val finalized: Boolean,
    val verified: Boolean,
    val distributionSha256: String?,
    val rows: List<RetainedReferenceProjectionRow>,
    val contentDigest: String? = null,
    val sourceRunId: UUID? = null,
    val prefixN: Int? = null,
    val ratingBand: String? = null,
    val minObservations: Int? = null,
    val calculationVersion: String? = null,
)

data class RetainedReferenceOccurrence(
    val sourceRunId: UUID,
    val qualifyingOrdinal: Int,
    val preMovePly: Int,
    val providerGameId: String,
    val positionHash: String,
    val movePlayed: String,
    val contentDigest: String,
    val coveredPrefix: Int,
)

data class RetainedEvaluationEvidence(
    val referencePopulation: EvaluationReferencePopulation,
    val projection: RetainedReferenceProjection,
    val snapshot: EvaluationEvidenceSnapshot,
    val occurrences: Map<UUID, RetainedReferenceOccurrence>,
    val analysisVersion: String,
) {
    val referencePositions: Set<String>
        get() = projection.rows.map { it.positionHash }.toSet()

    val resolvedOccurrences: Map<UUID, RetainedReferenceOccurrence>
        get() = occurrences

    fun toAnalysisInput(): ReferenceCoverageAnalysisInput {
        RetainedEvaluationEvidenceValidator().admit(this)
        return ReferenceCoverageAnalysisInput(
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
                            ?: throw RetainedEvaluationEvidenceIntegrityException(
                                "missing authoritative occurrence ${row.occurrenceId}",
                            )
                    ReferenceCoverageAnalysisRow(
                        playerId = row.playerId,
                        gameId = row.gameId,
                        occurrenceId = row.occurrenceId,
                        positionIdentity = row.positionIdentity,
                        loss = row.loss,
                        verifiedOccurrence = true,
                        canonicalLocation =
                            CanonicalOccurrenceLocation(
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

class RetainedEvaluationEvidenceIntegrityException(message: String) : RuntimeException(message)

class RetainedEvaluationEvidenceValidator {
    fun admit(evidence: RetainedEvaluationEvidence): RetainedEvaluationEvidence {
        val population = evidence.referencePopulation
        val projection = evidence.projection
        validateProjection(projection)
        validatePopulationMatch(evidence.snapshot.referencePopulation, population, projection.distributionSha256)
        validateDeclaredPlayers(evidence.snapshot.players)
        if (evidence.analysisVersion.isBlank()) {
            throw RetainedEvaluationEvidenceIntegrityException("analysis version must not be blank")
        }
        validateOccurrences(evidence, population)
        return evidence
    }

    private fun validateProjection(projection: RetainedReferenceProjection) {
        if (!projection.finalized || !projection.verified || projection.distributionSha256 == null) {
            throw RetainedEvaluationEvidenceIntegrityException("reference projection is not finalized, verified, and digested")
        }
        if (projectionDigest(projection.rows) != projection.distributionSha256) {
            throw RetainedEvaluationEvidenceIntegrityException("reference projection distribution digest mismatch")
        }
    }

    private fun validatePopulationMatch(
        snapshotPopulation: EvaluationReferencePopulation,
        population: EvaluationReferencePopulation,
        projectionSha256: String?,
    ) {
        if (snapshotPopulation != population ||
            (population.distributionSha256 != null && population.distributionSha256 != projectionSha256)
        ) {
            throw RetainedEvaluationEvidenceIntegrityException("retained evidence does not match the selected reference population")
        }
    }

    private fun validateDeclaredPlayers(players: Set<EvaluationEvidencePlayer>) {
        if (players.isEmpty() || hasDuplicateOrBlankPlayers(players)) {
            throw RetainedEvaluationEvidenceIntegrityException("declared player identities are not unique")
        }
    }

    private fun hasDuplicateOrBlankPlayers(players: Set<EvaluationEvidencePlayer>): Boolean =
        hasDuplicatePlayerIds(players) ||
            hasDuplicateIdentities(players) ||
            players.any { it.identity.isBlank() }

    private fun hasDuplicatePlayerIds(players: Set<EvaluationEvidencePlayer>): Boolean {
        return players.map { it.id }.toSet().size != players.size
    }

    private fun hasDuplicateIdentities(players: Set<EvaluationEvidencePlayer>): Boolean {
        return players.map { it.identity }.toSet().size != players.size
    }

    private fun validateOccurrences(
        evidence: RetainedEvaluationEvidence,
        population: EvaluationReferencePopulation,
    ) {
        val declaredIds = evidence.snapshot.players.map { it.id }.toSet()
        evidence.snapshot.rows.forEach { row ->
            val occurrence =
                evidence.occurrences[row.occurrenceId]
                    ?: throw RetainedEvaluationEvidenceIntegrityException("missing authoritative occurrence ${row.occurrenceId}")
            if (!matchesRetainedOccurrence(row, occurrence, population, declaredIds)) {
                throw RetainedEvaluationEvidenceIntegrityException("occurrence ${row.occurrenceId} does not match retained evidence")
            }
        }
    }

    private fun matchesRetainedOccurrence(
        row: EvaluationEvidenceRow,
        occurrence: RetainedReferenceOccurrence,
        population: EvaluationReferencePopulation,
        declaredIds: Set<UUID>,
    ): Boolean =
        row.playerId in declaredIds &&
            matchesOccurrencePopulation(occurrence, population) &&
            matchesOccurrenceIdentity(occurrence, row)

    private fun matchesOccurrencePopulation(
        occurrence: RetainedReferenceOccurrence,
        population: EvaluationReferencePopulation,
    ): Boolean =
        matchesPopulationDigest(occurrence, population) &&
            occurrence.coveredPrefix == population.coveredPrefix &&
            occurrence.qualifyingOrdinal in 1..population.coveredPrefix

    private fun matchesPopulationDigest(
        occurrence: RetainedReferenceOccurrence,
        population: EvaluationReferencePopulation,
    ): Boolean =
        occurrence.sourceRunId == population.sourceRunId &&
            occurrence.contentDigest == population.contentDigest

    private fun matchesOccurrenceIdentity(
        occurrence: RetainedReferenceOccurrence,
        row: EvaluationEvidenceRow,
    ): Boolean =
        matchesPositionAndPly(occurrence, row) &&
            occurrence.providerGameId.isNotBlank() &&
            occurrence.movePlayed.isNotBlank()

    private fun matchesPositionAndPly(
        occurrence: RetainedReferenceOccurrence,
        row: EvaluationEvidenceRow,
    ): Boolean =
        occurrence.positionHash == row.positionIdentity &&
            occurrence.preMovePly == row.preMovePly

    private fun projectionDigest(rows: List<RetainedReferenceProjectionRow>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(rows.joinToString("") { "${it.positionHash}\t${it.movePlayed}\t${it.observationCount}\n" }.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
