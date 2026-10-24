package com.chessecho.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
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
    fun `retains the same reference occurrence independently for different operational games`() {
        val player = UUID.randomUUID()
        val occurrence = UUID.randomUUID()
        val first =
            evidenceRow(
                player,
                UUID.randomUUID(),
                0.55,
                ObjectiveOutcome.WEAK,
                occurrenceId = occurrence,
            )
        val second = first.copy(gameId = UUID.randomUUID(), loss = 0.35)
        val snapshot =
            validSnapshot().copy(
                players = setOf(EvaluationEvidencePlayer(player, "player")),
                rows = listOf(first, second),
            )

        val result = EvaluationEvidenceSnapshotService().reconstruct(snapshot)

        assertEquals(2, result.objectiveWeakness.getValue(player).getValue(0.30))
        assertEquals(1, result.objectiveWeakness.getValue(player).getValue(0.50))
        assertEquals(2, result.observedOutcomes.getValue(player).getValue(ObservedGameOutcome.WIN))
        assertEquals(2, result.practicalEvidence.getValue(player)?.candidateGames)
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

    @Test
    fun `accepts zero and unit boundary values for configuration population and rows`() {
        val service = EvaluationEvidenceSnapshotService()
        val player = UUID.randomUUID()
        val game = UUID.randomUUID()

        // minMistakeCount = 0, minTimesReached = 0
        assertDoesNotThrow {
            service.validate(
                validSnapshot().copy(
                    configuration = approvedConfiguration().copy(minMistakeCount = 0, minTimesReached = 0),
                ),
            )
        }

        // coveredPrefix = 50, prefixN = 50, minObservations = 0
        assertDoesNotThrow {
            service.validate(
                validSnapshot().copy(
                    referencePopulation =
                        referencePopulation().copy(
                            coveredPrefix = 50,
                            prefixN = 50,
                            minObservations = 0,
                        ),
                ),
            )
        }

        // prefixN = 1, coveredPrefix = 1
        assertDoesNotThrow {
            service.validate(
                validSnapshot().copy(
                    referencePopulation =
                        referencePopulation().copy(
                            coveredPrefix = 1,
                            prefixN = 1,
                        ),
                ),
            )
        }

        // distributionSha256 = null
        assertDoesNotThrow {
            service.validate(
                validSnapshot().copy(
                    referencePopulation =
                        referencePopulation().copy(
                            distributionSha256 = null,
                        ),
                ),
            )
        }

        // preMovePly = 1, loss = 0.0, engineDepth = 1
        assertDoesNotThrow {
            service.validate(
                validSnapshot().copy(
                    players = setOf(EvaluationEvidencePlayer(player, "player")),
                    rows =
                        listOf(
                            evidenceRow(
                                player,
                                game,
                                loss = 0.0,
                                objectiveOutcome = ObjectiveOutcome.SOUND,
                                preMovePly = 1,
                            ).copy(engineDepth = 1),
                        ),
                ),
            )
        }
    }

    @Test
    fun `rejects invalid configuration and population boundaries`() {
        val service = EvaluationEvidenceSnapshotService()

        assertThrows<IllegalArgumentException> {
            service.validate(validSnapshot().copy(configuration = approvedConfiguration().copy(minMistakeCount = -1)))
        }
        assertThrows<IllegalArgumentException> {
            service.validate(validSnapshot().copy(configuration = approvedConfiguration().copy(minTimesReached = -1)))
        }
        assertThrows<IllegalArgumentException> {
            service.validate(
                validSnapshot().copy(
                    referencePopulation = referencePopulation().copy(coveredPrefix = 49, prefixN = 50),
                ),
            )
        }
        assertThrows<IllegalArgumentException> {
            service.validate(
                validSnapshot().copy(
                    referencePopulation = referencePopulation().copy(prefixN = 0),
                ),
            )
        }
        assertThrows<IllegalArgumentException> {
            service.validate(
                validSnapshot().copy(
                    referencePopulation = referencePopulation().copy(minObservations = -1),
                ),
            )
        }
    }

    @Test
    fun `rejects invalid row boundaries for ply loss and depth`() {
        val service = EvaluationEvidenceSnapshotService()
        val player = UUID.randomUUID()
        val game = UUID.randomUUID()
        val baseRow = evidenceRow(player, game, 0.5, ObjectiveOutcome.WEAK)
        val snapshot = validSnapshot().copy(players = setOf(EvaluationEvidencePlayer(player, "player")))

        assertThrows<IllegalArgumentException> {
            service.validate(snapshot.copy(rows = listOf(baseRow.copy(preMovePly = 0))))
        }
        assertThrows<IllegalArgumentException> {
            service.validate(snapshot.copy(rows = listOf(baseRow.copy(loss = -0.01))))
        }
        assertThrows<IllegalArgumentException> {
            service.validate(snapshot.copy(rows = listOf(baseRow.copy(engineDepth = 0))))
        }
    }

    @Test
    fun `rejects malformed digests duplicate player identities and unknown row players`() {
        val service = EvaluationEvidenceSnapshotService()
        val player = UUID.randomUUID()
        val snapshot = validSnapshot()

        // malformed contentDigest (length and non-hex)
        assertThrows<IllegalArgumentException> {
            service.validate(
                snapshot.copy(
                    referencePopulation = referencePopulation().copy(contentDigest = "a".repeat(32)),
                ),
            )
        }
        assertThrows<IllegalArgumentException> {
            service.validate(
                snapshot.copy(
                    referencePopulation = referencePopulation().copy(contentDigest = "z".repeat(64)),
                ),
            )
        }

        // malformed distributionSha256
        assertThrows<IllegalArgumentException> {
            service.validate(
                snapshot.copy(
                    referencePopulation = referencePopulation().copy(distributionSha256 = "invalid-digest"),
                ),
            )
        }

        // duplicate evaluation player identity
        val duplicatePlayers =
            setOf(
                EvaluationEvidencePlayer(player, "player-1"),
                EvaluationEvidencePlayer(player, "player-2"),
            )
        val dupEx =
            assertThrows<IllegalArgumentException> {
                service.validate(snapshot.copy(players = duplicatePlayers))
            }
        assertEquals("duplicate evaluation player identity", dupEx.message)

        // blank evaluation-player identity
        val blankPlayerEx =
            assertThrows<IllegalArgumentException> {
                service.validate(
                    snapshot.copy(
                        players = setOf(EvaluationEvidencePlayer(player, "   ")),
                    ),
                )
            }
        assertEquals("evaluation-player identity must not be blank", blankPlayerEx.message)

        // evidence row references an unknown player
        val unknownPlayerRow = evidenceRow(UUID.randomUUID(), UUID.randomUUID(), 0.5, ObjectiveOutcome.WEAK)
        val unknownEx =
            assertThrows<IllegalArgumentException> {
                service.validate(snapshot.copy(rows = listOf(unknownPlayerRow)))
            }
        assertEquals("evidence row references an unknown player", unknownEx.message)

        // eligible practical evidence must be a candidate
        val invalidPracticalRow =
            evidenceRow(
                snapshot.players.single().id,
                UUID.randomUUID(),
                0.5,
                ObjectiveOutcome.WEAK,
            ).copy(
                practicalCandidate = false,
                practicalEligible = true,
                practicalWins = 0,
                practicalDraws = 0,
                practicalLosses = 0,
            )
        val practicalEx =
            assertThrows<IllegalArgumentException> {
                service.validate(snapshot.copy(rows = listOf(invalidPracticalRow)))
            }
        assertEquals("eligible practical evidence must be a candidate", practicalEx.message)
    }

    @Test
    fun `bind and reconstruct enforce entry validation gate`() {
        val service = EvaluationEvidenceSnapshotService()
        val invalidSnapshot =
            validSnapshot().copy(
                configuration = approvedConfiguration().copy(minMistakeCount = -1),
            )

        assertThrows<IllegalArgumentException> {
            service.bind(invalidSnapshot, invalidSnapshot.referencePopulation)
        }

        assertThrows<IllegalArgumentException> {
            service.reconstruct(invalidSnapshot)
        }
    }

    @Test
    fun `aggregates practical evidence counts accurately distinguishing candidate eligible ineligible and excluded games`() {
        val player = UUID.randomUUID()
        val g1 = UUID.randomUUID()
        val g2 = UUID.randomUUID()
        val g3 = UUID.randomUUID()
        val rows =
            listOf(
                // Game 1: candidate, eligible, 1 win, 0 draws, 0 losses
                evidenceRow(
                    player,
                    g1,
                    0.55,
                    ObjectiveOutcome.WEAK,
                    practicalWins = 1,
                    practicalDraws = 0,
                    practicalLosses = 0,
                ).copy(
                    practicalCandidate = true,
                    practicalEligible = true,
                ),
                // Game 2: candidate, eligible, 0 wins, 0 draws, 0 losses -> zero outcome, excluded
                evidenceRow(
                    player,
                    g2,
                    0.55,
                    ObjectiveOutcome.WEAK,
                    practicalWins = 0,
                    practicalDraws = 0,
                    practicalLosses = 0,
                ).copy(
                    practicalCandidate = true,
                    practicalEligible = true,
                ),
                // Game 3: candidate, ineligible, 0 wins, 0 draws, 1 loss
                evidenceRow(
                    player,
                    g3,
                    0.55,
                    ObjectiveOutcome.WEAK,
                    practicalWins = 0,
                    practicalDraws = 0,
                    practicalLosses = 1,
                ).copy(
                    practicalCandidate = true,
                    practicalEligible = false,
                ),
            )
        val snapshot =
            validSnapshot().copy(
                players = setOf(EvaluationEvidencePlayer(player, "player")),
                rows = rows,
            )

        val result = EvaluationEvidenceSnapshotService().reconstruct(snapshot)
        val practical = result.practicalEvidence[player]

        assertEquals(3, practical?.candidateGames)
        assertEquals(2, practical?.eligibleGames)
        assertEquals(1, practical?.ineligibleGames)
        assertEquals(1, practical?.excludedGames)
        assertEquals(1, practical?.wins)
        assertEquals(0, practical?.draws)
        assertEquals(0, practical?.losses)
    }

    @Test
    fun `aggregates multi-game eligible practical evidence with non-zero draws losses and wins`() {
        val player = UUID.randomUUID()
        val g1 = UUID.randomUUID()
        val g2 = UUID.randomUUID()
        val rows =
            listOf(
                // Game 1: 1 win, 1 draw, 0 losses (1+1+0 != 0, not excluded)
                evidenceRow(
                    player,
                    g1,
                    0.55,
                    ObjectiveOutcome.WEAK,
                    practicalWins = 1,
                    practicalDraws = 1,
                    practicalLosses = 0,
                ).copy(
                    practicalCandidate = true,
                    practicalEligible = true,
                ),
                // Game 2: 0 wins, 1 draw, 1 loss (0+1+1 != 0, not excluded)
                evidenceRow(
                    player,
                    g2,
                    0.35,
                    ObjectiveOutcome.WEAK,
                    practicalWins = 0,
                    practicalDraws = 1,
                    practicalLosses = 1,
                ).copy(
                    practicalCandidate = true,
                    practicalEligible = true,
                ),
            )
        val snapshot =
            validSnapshot().copy(
                players = setOf(EvaluationEvidencePlayer(player, "player")),
                rows = rows,
            )

        val result = EvaluationEvidenceSnapshotService().reconstruct(snapshot)
        val practical = result.practicalEvidence[player]

        assertEquals(2, practical?.candidateGames)
        assertEquals(2, practical?.eligibleGames)
        assertEquals(0, practical?.ineligibleGames)
        assertEquals(0, practical?.excludedGames)
        assertEquals(1, practical?.wins)
        assertEquals(2, practical?.draws)
        assertEquals(1, practical?.losses)
    }

    @Test
    fun `reconstructs objective weakness counts at exact threshold boundaries`() {
        val service = EvaluationEvidenceSnapshotService()
        val player = UUID.randomUUID()
        val game = UUID.randomUUID()
        val snapshot =
            validSnapshot().copy(
                players = setOf(EvaluationEvidencePlayer(player, "player")),
                rows =
                    listOf(
                        evidenceRow(player, game, 0.30, ObjectiveOutcome.WEAK, preMovePly = 1),
                        evidenceRow(player, game, 0.50, ObjectiveOutcome.WEAK, preMovePly = 3),
                        evidenceRow(player, game, 0.80, ObjectiveOutcome.WEAK, preMovePly = 5),
                    ),
            )
        val result = service.reconstruct(snapshot)
        val objective = result.objectiveWeakness.getValue(player)
        assertEquals(3, objective.getValue(0.30))
        assertEquals(2, objective.getValue(0.50))
        assertEquals(1, objective.getValue(0.80))
    }

    @Test
    fun `evaluation evidence integrity exception preserves root cause`() {
        val cause = IllegalArgumentException("root cause")
        val exception = EvaluationEvidenceIntegrityException("roster error", cause)
        assertEquals("roster error", exception.message)
        assertEquals(cause, exception.cause)
    }
}
