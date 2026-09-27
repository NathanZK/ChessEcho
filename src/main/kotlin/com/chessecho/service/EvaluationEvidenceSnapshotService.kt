package com.chessecho.service

import java.util.UUID

data class EvaluationReferencePopulation(
    val contentDigest: String,
    val sourceRunId: UUID,
    val coveredPrefix: Int,
    val prefixN: Int,
    val ratingBand: String,
    val minObservations: Int,
    val calculationVersion: String,
    val distributionSha256: String?,
)

data class EvaluationEvidenceConfiguration(
    val thresholds: Set<Double>,
    val minMistakeCount: Int,
    val minTimesReached: Int,
    val color: String,
    val platform: String,
    val observationWindowDays: Int?,
)

data class EvaluationEvidencePlayer(
    val id: UUID,
    val identity: String,
)

enum class ObjectiveOutcome {
    WEAK,
    SOUND,
}

enum class ObservedGameOutcome {
    WIN,
    DRAW,
    LOSS,
}

data class EvaluationEvidenceRow(
    val playerId: UUID,
    val gameId: UUID,
    val occurrenceId: UUID,
    val positionIdentity: String,
    val preMovePly: Int,
    val move: String,
    val playerColor: String,
    val loss: Double,
    val engineDepth: Int,
    val observedOutcome: ObservedGameOutcome,
    val objectiveOutcome: ObjectiveOutcome,
    val practicalCandidate: Boolean,
    val practicalEligible: Boolean,
    val practicalWins: Int,
    val practicalDraws: Int,
    val practicalLosses: Int,
)

data class EvaluationEvidenceSnapshot(
    val id: UUID,
    val referencePopulation: EvaluationReferencePopulation,
    val occurrenceEvidenceId: UUID?,
    val players: Set<EvaluationEvidencePlayer>,
    val configuration: EvaluationEvidenceConfiguration,
    val sourceRevision: String,
    val engineIdentity: String,
    val parserIdentity: String,
    val rows: List<EvaluationEvidenceRow>,
)

data class EvaluationEvidenceReconstruction(
    val objectiveWeakness: Map<UUID, Map<Double, Int>>,
    val practicalEvidence: Map<UUID, PracticalEvidenceCounts>,
    val observedOutcomes: Map<UUID, Map<ObservedGameOutcome, Int>>,
)

data class PracticalEvidenceCounts(
    val candidateGames: Int,
    val eligibleGames: Int,
    val ineligibleGames: Int,
    val excludedGames: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
)

class EvaluationEvidenceSnapshotService {
    fun validate(snapshot: EvaluationEvidenceSnapshot) {
        require(snapshot.players.isNotEmpty()) { "evaluation-player set must not be empty" }
        require(snapshot.configuration.thresholds == APPROVED_THRESHOLDS) {
            "snapshot thresholds do not match the approved E6 configuration"
        }
        require(snapshot.configuration.minMistakeCount >= 0)
        require(snapshot.configuration.minTimesReached >= 0)
        require(snapshot.configuration.color in setOf("WHITE", "BLACK", "BOTH"))
        require(snapshot.configuration.platform == "CHESS_COM")
        require(snapshot.sourceRevision.isNotBlank())
        require(snapshot.engineIdentity.isNotBlank())
        require(snapshot.parserIdentity.isNotBlank())
        require(snapshot.referencePopulation.coveredPrefix >= snapshot.referencePopulation.prefixN)
        require(snapshot.referencePopulation.prefixN > 0)
        require(snapshot.referencePopulation.minObservations >= 0)
        requireDigest(snapshot.referencePopulation.contentDigest)
        snapshot.referencePopulation.distributionSha256?.let(::requireDigest)

        val playerIds = snapshot.players.map { it.id }.toSet()
        require(playerIds.size == snapshot.players.size) { "duplicate evaluation player identity" }
        require(snapshot.players.all { it.identity.isNotBlank() }) {
            "evaluation-player identity must not be blank"
        }
        snapshot.rows.forEach { row ->
            require(row.playerId in playerIds) { "evidence row references an unknown player" }
            require(row.preMovePly >= 1) { "pre-move ply must be one-based" }
            require(row.positionIdentity.isNotBlank())
            require(row.move.isNotBlank())
            require(row.playerColor in setOf("WHITE", "BLACK"))
            require(row.loss.isFinite() && row.loss >= 0.0)
            require(row.engineDepth > 0)
            require(row.practicalWins >= 0 && row.practicalDraws >= 0 && row.practicalLosses >= 0)
            require(!row.practicalEligible || row.practicalCandidate) {
                "eligible practical evidence must be a candidate"
            }
        }
    }

    fun bind(
        snapshot: EvaluationEvidenceSnapshot,
        selectedReferencePopulation: EvaluationReferencePopulation,
    ): EvaluationEvidenceSnapshot {
        validate(snapshot)
        require(snapshot.referencePopulation == selectedReferencePopulation) {
            "evaluation evidence is bound to a different finalized reference population"
        }
        return snapshot
    }

    fun reconstruct(snapshot: EvaluationEvidenceSnapshot): EvaluationEvidenceReconstruction {
        validate(snapshot)
        val rowsByPlayer = snapshot.rows.groupBy { it.playerId }
        val objective =
            snapshot.players.associate { player ->
                player.id to
                    snapshot.configuration.thresholds.associateWith { threshold ->
                        rowsByPlayer[player.id].orEmpty().count { it.loss >= threshold }
                    }
            }
        val practical =
            snapshot.players.associate { player ->
                val rows = rowsByPlayer[player.id].orEmpty().filter { it.practicalCandidate }
                val byGame =
                    rows.groupBy { it.gameId }.mapValues { (_, gameRows) -> gameRows.first() }
                val eligible = byGame.values.filter { it.practicalEligible }
                val excluded = eligible.count { it.practicalWins + it.practicalDraws + it.practicalLosses == 0 }
                player.id to
                    PracticalEvidenceCounts(
                        candidateGames = byGame.size,
                        eligibleGames = eligible.size,
                        ineligibleGames = byGame.values.count { !it.practicalEligible },
                        excludedGames = excluded,
                        wins = eligible.sumOf { it.practicalWins },
                        draws = eligible.sumOf { it.practicalDraws },
                        losses = eligible.sumOf { it.practicalLosses },
                    )
            }
        val outcomes =
            snapshot.players.associate { player ->
                player.id to
                    rowsByPlayer[player.id].orEmpty()
                        .distinctBy { it.gameId }
                        .groupBy { it.observedOutcome }
                        .mapValues { it.value.size }
            }
        return EvaluationEvidenceReconstruction(objective, practical, outcomes)
    }

    private fun requireDigest(value: String) {
        require(value.matches(HEX_DIGEST)) { "digest must be a 64-character hexadecimal value" }
    }

    companion object {
        val APPROVED_THRESHOLDS = setOf(0.30, 0.50, 0.80)
        private val HEX_DIGEST = Regex("[0-9a-fA-F]{64}")
    }
}
