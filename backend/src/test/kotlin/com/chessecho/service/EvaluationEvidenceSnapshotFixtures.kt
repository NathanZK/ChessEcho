package com.chessecho.service

import java.util.UUID

internal fun validSnapshot(): EvaluationEvidenceSnapshot {
    val player = UUID.randomUUID()
    return EvaluationEvidenceSnapshot(
        id = UUID.randomUUID(),
        referencePopulation = referencePopulation(),
        occurrenceEvidenceId = UUID.randomUUID(),
        players = setOf(EvaluationEvidencePlayer(player, "player")),
        configuration = approvedConfiguration(),
        sourceRevision = "source-revision",
        engineIdentity = "stockfish-16-depth-18",
        parserIdentity = "pgn-normalizer-v1",
        rows = listOf(evidenceRow(player, UUID.randomUUID(), 0.5, ObjectiveOutcome.WEAK)),
    )
}

internal fun referencePopulation() =
    EvaluationReferencePopulation(
        contentDigest = "a".repeat(64),
        sourceRunId = UUID.randomUUID(),
        coveredPrefix = 100,
        prefixN = 100,
        ratingBand = "ALL",
        minObservations = 5,
        calculationVersion = "human-move-corpus-checkpoint-v1",
        distributionSha256 = "b".repeat(64),
    )

internal fun approvedConfiguration() =
    EvaluationEvidenceConfiguration(
        thresholds = setOf(0.30, 0.50, 0.80),
        minMistakeCount = 3,
        minTimesReached = 5,
        color = "BOTH",
        platform = "CHESS_COM",
        observationWindowDays = 365,
    )

internal fun evidenceRow(
    playerId: UUID,
    gameId: UUID,
    loss: Double,
    objectiveOutcome: ObjectiveOutcome,
    preMovePly: Int = 1,
    occurrenceId: UUID = UUID.randomUUID(),
    observedOutcome: ObservedGameOutcome = ObservedGameOutcome.WIN,
    practicalWins: Int = 1,
    practicalDraws: Int = 0,
    practicalLosses: Int = 0,
) = EvaluationEvidenceRow(
    playerId = playerId,
    gameId = gameId,
    occurrenceId = occurrenceId,
    positionIdentity = "position-hash",
    preMovePly = preMovePly,
    move = "e4",
    playerColor = "WHITE",
    loss = loss,
    engineDepth = 18,
    observedOutcome = observedOutcome,
    objectiveOutcome = objectiveOutcome,
    practicalCandidate = true,
    practicalEligible = true,
    practicalWins = practicalWins,
    practicalDraws = practicalDraws,
    practicalLosses = practicalLosses,
)
