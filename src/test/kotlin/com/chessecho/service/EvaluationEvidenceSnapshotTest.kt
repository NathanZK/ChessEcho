package com.chessecho.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals

class EvaluationEvidenceSnapshotTest {
    @Test
    fun `accepts complete immutable evidence and reconstructs threshold counts`() {
        val player = UUID.randomUUID()
        val game = UUID.randomUUID()
        val snapshot =
            EvaluationEvidenceSnapshot(
                id = UUID.randomUUID(),
                referencePopulation = referencePopulation(),
                occurrenceEvidenceId = UUID.randomUUID(),
                players = setOf(EvaluationEvidencePlayer(player, "player")),
                configuration = approvedConfiguration(),
                sourceRevision = "source-revision",
                engineIdentity = "stockfish-16-depth-18",
                parserIdentity = "pgn-normalizer-v1",
                rows =
                    listOf(
                        evidenceRow(player, game, 0.81, ObjectiveOutcome.WEAK),
                        evidenceRow(player, game, 0.55, ObjectiveOutcome.WEAK),
                        evidenceRow(player, game, 0.35, ObjectiveOutcome.WEAK),
                        evidenceRow(player, game, 0.12, ObjectiveOutcome.SOUND),
                    ),
            )

        val result = EvaluationEvidenceSnapshotService().reconstruct(snapshot)

        assertEquals(3, result.objectiveWeakness[player]?.get(0.30))
        assertEquals(2, result.objectiveWeakness[player]?.get(0.50))
        assertEquals(1, result.objectiveWeakness[player]?.get(0.80))
        assertEquals(1, result.practicalEvidence[player]?.eligibleGames)
        assertEquals(1, result.observedOutcomes[player]?.get(ObservedGameOutcome.WIN))
    }

    @Test
    fun `rejects a reference population that differs in an existing identity component`() {
        val snapshot = validSnapshot()
        val mismatched =
            snapshot.copy(
                referencePopulation =
                    snapshot.referencePopulation.copy(
                        calculationVersion = "different-version",
                    ),
            )

        assertThrows<IllegalArgumentException> {
            EvaluationEvidenceSnapshotService().bind(mismatched, snapshot.referencePopulation)
        }
    }

    @Test
    fun `rejects mutable-only or incomplete rows`() {
        val snapshot =
            validSnapshot().copy(
                rows =
                    listOf(
                        evidenceRow(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            0.5,
                            ObjectiveOutcome.WEAK,
                        ).copy(preMovePly = 0),
                    ),
            )

        assertThrows<IllegalArgumentException> {
            EvaluationEvidenceSnapshotService().validate(snapshot)
        }
    }

    private fun validSnapshot(): EvaluationEvidenceSnapshot {
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

    private fun referencePopulation() =
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

    private fun approvedConfiguration() =
        EvaluationEvidenceConfiguration(
            thresholds = setOf(0.30, 0.50, 0.80),
            minMistakeCount = 3,
            minTimesReached = 5,
            color = "BOTH",
            platform = "CHESS_COM",
            observationWindowDays = 365,
        )

    private fun evidenceRow(
        playerId: UUID,
        gameId: UUID,
        loss: Double,
        objectiveOutcome: ObjectiveOutcome,
    ) = EvaluationEvidenceRow(
        playerId = playerId,
        gameId = gameId,
        occurrenceId = UUID.randomUUID(),
        positionIdentity = "position-hash",
        preMovePly = 1,
        move = "e4",
        playerColor = "WHITE",
        loss = loss,
        engineDepth = 18,
        observedOutcome = ObservedGameOutcome.WIN,
        objectiveOutcome = objectiveOutcome,
        practicalCandidate = true,
        practicalEligible = true,
        practicalWins = 1,
        practicalDraws = 0,
        practicalLosses = 0,
    )
}
