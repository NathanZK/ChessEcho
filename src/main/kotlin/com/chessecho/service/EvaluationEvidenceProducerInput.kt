package com.chessecho.service

import java.util.Collections
import java.util.UUID

class EvaluationEvidenceProducerInput(
    roster: Collection<EvaluationEvidencePlayer>,
    operationalOccurrenceIds: Collection<UUID>,
    val referencePopulation: EvaluationReferencePopulation,
    configuration: EvaluationEvidenceConfiguration,
) {
    val roster: Set<EvaluationEvidencePlayer> = immutableSet(roster)
    val operationalOccurrenceIds: Set<UUID> = immutableSet(operationalOccurrenceIds)
    val configuration: EvaluationEvidenceConfiguration =
        configuration.copy(thresholds = immutableSet(configuration.thresholds))

    init {
        require(this.roster.isNotEmpty()) { "evaluation-player roster must not be empty" }
        require(this.roster.map { it.id }.distinct().size == this.roster.size) {
            "evaluation-player roster contains duplicate player IDs"
        }
        require(operationalOccurrenceIds.size == this.operationalOccurrenceIds.size) {
            "operational occurrence selection contains duplicate IDs"
        }
    }

    private fun <T> immutableSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
}
