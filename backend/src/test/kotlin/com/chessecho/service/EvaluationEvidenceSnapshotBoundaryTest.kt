package com.chessecho.service

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class EvaluationEvidenceSnapshotBoundaryTest {
    @Test
    fun `accepts zero and unit boundary values for configuration population and rows`() {
        val service = EvaluationEvidenceSnapshotService()
        assertAcceptsConfigurationAndPopulationBoundaries(service)
        assertAcceptsRowBoundaries(service)
    }

    private fun assertAcceptsConfigurationAndPopulationBoundaries(service: EvaluationEvidenceSnapshotService) {
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
    }

    private fun assertAcceptsRowBoundaries(service: EvaluationEvidenceSnapshotService) {
        val player = UUID.randomUUID()
        val game = UUID.randomUUID()
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
        val snapshot = validSnapshot()
        assertRejectsMalformedDigests(service, snapshot)
        assertRejectsInvalidPlayersAndRows(service, snapshot)
    }

    private fun assertRejectsMalformedDigests(
        service: EvaluationEvidenceSnapshotService,
        snapshot: EvaluationEvidenceSnapshot,
    ) {
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
    }

    private fun assertRejectsInvalidPlayersAndRows(
        service: EvaluationEvidenceSnapshotService,
        snapshot: EvaluationEvidenceSnapshot,
    ) {
        val player = UUID.randomUUID()
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
}
