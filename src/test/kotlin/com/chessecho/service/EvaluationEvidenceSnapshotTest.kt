package com.chessecho.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
                        evidenceRow(player, game, 0.81, ObjectiveOutcome.WEAK, preMovePly = 1),
                        evidenceRow(player, game, 0.55, ObjectiveOutcome.WEAK, preMovePly = 3),
                        evidenceRow(player, game, 0.35, ObjectiveOutcome.WEAK, preMovePly = 5),
                        evidenceRow(player, game, 0.12, ObjectiveOutcome.SOUND, preMovePly = 7),
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

    @Test
    fun `collapses identical duplicate rows without inflating objective or observed counts`() {
        val player = UUID.randomUUID()
        val game = UUID.randomUUID()
        val occurrence = UUID.randomUUID()
        val row = evidenceRow(player, game, 0.55, ObjectiveOutcome.WEAK, preMovePly = 1, occurrenceId = occurrence)
        val snapshot =
            validSnapshot().copy(
                players = setOf(EvaluationEvidencePlayer(player, "player")),
                rows = listOf(row, row.copy()),
            )

        val result = EvaluationEvidenceSnapshotService().reconstruct(snapshot)

        assertEquals(1, result.objectiveWeakness[player]?.get(0.50))
        assertEquals(1, result.observedOutcomes[player]?.get(ObservedGameOutcome.WIN))
    }

    @Test
    fun `rejects conflicting duplicate rows for the same occurrence`() {
        val player = UUID.randomUUID()
        val game = UUID.randomUUID()
        val occurrence = UUID.randomUUID()
        val snapshot =
            validSnapshot().copy(
                players = setOf(EvaluationEvidencePlayer(player, "player")),
                rows =
                    listOf(
                        evidenceRow(player, game, 0.55, ObjectiveOutcome.WEAK, preMovePly = 1, occurrenceId = occurrence),
                        evidenceRow(player, game, 0.81, ObjectiveOutcome.WEAK, preMovePly = 1, occurrenceId = occurrence),
                    ),
            )

        assertThrows<EvaluationEvidenceIntegrityException> {
            EvaluationEvidenceSnapshotService().reconstruct(snapshot)
        }
    }

    @Test
    fun `retains distinct occurrences at the same player game and preMovePly`() {
        val player = UUID.randomUUID()
        val game = UUID.randomUUID()
        val snapshot =
            validSnapshot().copy(
                players = setOf(EvaluationEvidencePlayer(player, "player")),
                rows =
                    listOf(
                        evidenceRow(player, game, 0.55, ObjectiveOutcome.WEAK, preMovePly = 1),
                        evidenceRow(player, game, 0.55, ObjectiveOutcome.WEAK, preMovePly = 1),
                    ),
            )

        val result = EvaluationEvidenceSnapshotService().reconstruct(snapshot)

        assertEquals(2, result.objectiveWeakness[player]?.get(0.50))
    }

    @Test
    fun `rejects duplicate game ids with different observed outcomes`() {
        val player = UUID.randomUUID()
        val game = UUID.randomUUID()
        val snapshot =
            validSnapshot().copy(
                players = setOf(EvaluationEvidencePlayer(player, "player")),
                rows =
                    listOf(
                        evidenceRow(player, game, 0.55, ObjectiveOutcome.WEAK, preMovePly = 1, observedOutcome = ObservedGameOutcome.WIN),
                        evidenceRow(player, game, 0.35, ObjectiveOutcome.WEAK, preMovePly = 3, observedOutcome = ObservedGameOutcome.LOSS),
                    ),
            )

        assertThrows<EvaluationEvidenceIntegrityException> {
            EvaluationEvidenceSnapshotService().reconstruct(snapshot)
        }
    }

    @Test
    fun `rejects conflicting practical contributions for the same game`() {
        val player = UUID.randomUUID()
        val game = UUID.randomUUID()
        val snapshot =
            validSnapshot().copy(
                players = setOf(EvaluationEvidencePlayer(player, "player")),
                rows =
                    listOf(
                        evidenceRow(player, game, 0.55, ObjectiveOutcome.WEAK, preMovePly = 1, practicalWins = 1),
                        evidenceRow(player, game, 0.35, ObjectiveOutcome.WEAK, preMovePly = 3, practicalWins = 0),
                    ),
            )

        assertThrows<EvaluationEvidenceIntegrityException> {
            EvaluationEvidenceSnapshotService().reconstruct(snapshot)
        }
    }

    @Test
    fun `reconstructs unavailable practical evidence without turning it into zero`() {
        val snapshot = validSnapshot()
        val player = snapshot.players.single().id
        val unavailable =
            snapshot.copy(
                sourceRevision = null,
                engineIdentity = null,
                parserIdentity = null,
                rows =
                    snapshot.rows.map {
                        it.copy(
                            objectiveOutcome = null,
                            practicalCandidate = null,
                            practicalEligible = null,
                            practicalWins = null,
                            practicalDraws = null,
                            practicalLosses = null,
                        )
                    },
            )

        val result = EvaluationEvidenceSnapshotService().reconstruct(unavailable)

        assertEquals(1, result.objectiveWeakness.getValue(player).getValue(0.50))
        assertNull(result.practicalEvidence.getValue(player))
        assertEquals(1, result.observedOutcomes.getValue(player).getValue(ObservedGameOutcome.WIN))
        assertEquals(
            PracticalEvidenceCounts(
                candidateGames = 1,
                eligibleGames = 1,
                ineligibleGames = 0,
                excludedGames = 0,
                wins = 1,
                draws = 0,
                losses = 0,
            ),
            EvaluationEvidenceSnapshotService().reconstruct(snapshot).practicalEvidence[player],
        )
    }

    @Test
    fun `available zero practical counts remain available while mixed rows are unavailable`() {
        val snapshot = validSnapshot()
        val player = snapshot.players.single().id
        val zero = snapshot.rows.single().copy(practicalWins = 0)
        val absent =
            zero.copy(
                gameId = UUID.randomUUID(),
                occurrenceId = UUID.randomUUID(),
                practicalCandidate = null,
                practicalEligible = null,
                practicalWins = null,
                practicalDraws = null,
                practicalLosses = null,
            )
        val service = EvaluationEvidenceSnapshotService()

        assertEquals(
            PracticalEvidenceCounts(
                candidateGames = 1,
                eligibleGames = 1,
                ineligibleGames = 0,
                excludedGames = 1,
                wins = 0,
                draws = 0,
                losses = 0,
            ),
            service.reconstruct(snapshot.copy(rows = listOf(zero))).practicalEvidence[player],
        )
        assertNull(service.reconstruct(snapshot.copy(rows = listOf(zero, absent))).practicalEvidence.getValue(player))
        assertNull(service.reconstruct(snapshot.copy(rows = emptyList())).practicalEvidence.getValue(player))
    }

    @Test
    fun `rejects each partially present practical tuple and blank supplied provenance`() {
        val snapshot = validSnapshot()
        val row = snapshot.rows.single()
        val service = EvaluationEvidenceSnapshotService()
        val partials =
            listOf(
                row.copy(practicalCandidate = null),
                row.copy(practicalEligible = null),
                row.copy(practicalWins = null),
                row.copy(practicalDraws = null),
                row.copy(practicalLosses = null),
                row.copy(
                    practicalCandidate = null,
                    practicalEligible = null,
                    practicalWins = null,
                    practicalDraws = null,
                ),
            )
        partials.forEach { partial ->
            assertThrows<IllegalArgumentException> { service.validate(snapshot.copy(rows = listOf(partial))) }
        }
        listOf(
            snapshot.copy(sourceRevision = " "),
            snapshot.copy(engineIdentity = ""),
            snapshot.copy(parserIdentity = " "),
        ).forEach { invalid ->
            assertThrows<IllegalArgumentException> { service.validate(invalid) }
        }
    }

    @Test
    fun `rejects absent versus supplied practical tuples within one game`() {
        val snapshot = validSnapshot()
        val row = snapshot.rows.single()
        val absent =
            row.copy(
                occurrenceId = UUID.randomUUID(),
                practicalCandidate = null,
                practicalEligible = null,
                practicalWins = null,
                practicalDraws = null,
                practicalLosses = null,
            )
        assertThrows<EvaluationEvidenceIntegrityException> {
            EvaluationEvidenceSnapshotService().reconstruct(snapshot.copy(rows = listOf(row, absent)))
        }
    }
}
