package com.chessecho.service

import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class E6AnalysisEvidenceServiceTest {
    private val service = E6AdmissionValidator()
    private val runId = UUID.nameUUIDFromBytes("reference-run".toByteArray())
    private val playerId = UUID.nameUUIDFromBytes("evaluation-player".toByteArray())
    private val occurrenceId = UUID.nameUUIDFromBytes("reference-occurrence".toByteArray())

    @Test
    fun `admits finalized projection rows and a retained snapshot with exactly the selected identity`() {
        val selected = evidence()

        val admitted = service.admit(selected)

        assertEquals(selected.snapshot.referencePopulation, admitted.referencePopulation)
        assertEquals(setOf("r1", "r2"), admitted.referencePositions)
        assertEquals(selected.snapshot.players, admitted.snapshot.players)
        assertEquals(setOf(occurrenceId), admitted.resolvedOccurrences.keys)
        assertEquals(7, admitted.resolvedOccurrences.getValue(occurrenceId).qualifyingOrdinal)
        assertEquals("e6-v1", admitted.analysisVersion)
    }

    @Test
    fun `rejects unfinalized projection corrupted distribution and every mismatched population component`() {
        val selected = evidence()
        assertFailsWith<E6AnalysisEvidenceIntegrityException> {
            service.admit(selected.copy(projection = selected.projection.copy(finalized = false)))
        }
        assertFailsWith<E6AnalysisEvidenceIntegrityException> {
            service.admit(selected.copy(projection = selected.projection.copy(distributionSha256 = "0".repeat(64))))
        }
        val changes =
            listOf(
                selected.snapshot.referencePopulation.copy(contentDigest = "c".repeat(64)),
                selected.snapshot.referencePopulation.copy(sourceRunId = UUID.randomUUID()),
                selected.snapshot.referencePopulation.copy(coveredPrefix = 9),
                selected.snapshot.referencePopulation.copy(prefixN = 9),
                selected.snapshot.referencePopulation.copy(ratingBand = "1400-1600"),
                selected.snapshot.referencePopulation.copy(minObservations = 2),
                selected.snapshot.referencePopulation.copy(calculationVersion = "other"),
                selected.snapshot.referencePopulation.copy(distributionSha256 = "d".repeat(64)),
            )
        changes.forEach { population ->
            assertFailsWith<E6AnalysisEvidenceIntegrityException> {
                service.admit(selected.copy(snapshot = selected.snapshot.copy(referencePopulation = population)))
            }
        }
    }

    @Test
    fun `resolves UUID through authoritative source ordinal ply move and position`() {
        val selected = evidence()
        val occurrence = selected.occurrences.getValue(occurrenceId)
        val row = selected.snapshot.rows.single()
        assertEquals(occurrence.positionHash, row.positionIdentity)
        assertEquals(occurrence.preMovePly, row.preMovePly)

        assertFailsWith<E6AnalysisEvidenceIntegrityException> {
            service.admit(selected.copy(occurrences = selected.occurrences + (occurrenceId to occurrence.copy(preMovePly = 3))))
        }
        assertFailsWith<E6AnalysisEvidenceIntegrityException> {
            service.admit(selected.copy(occurrences = emptyMap()))
        }
        assertFailsWith<E6AnalysisEvidenceIntegrityException> {
            service.admit(
                selected.copy(occurrences = selected.occurrences + (occurrenceId to occurrence.copy(contentDigest = "c".repeat(64)))),
            )
        }
    }

    private fun evidence(): E6AdmissionEvidence {
        val projectionRows =
            listOf(
                E6ProjectionRow("r1", "e4", 5),
                E6ProjectionRow("r2", "Nf3", 6),
            )
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(projectionRows.joinToString("") { "${it.positionHash}\t${it.movePlayed}\t${it.observationCount}\n" }.toByteArray())
                .joinToString("") { "%02x".format(it) }
        val population =
            EvaluationReferencePopulation(
                contentDigest = "a".repeat(64),
                sourceRunId = runId,
                coveredPrefix = 10,
                prefixN = 10,
                ratingBand = "1000-1200",
                minObservations = 5,
                calculationVersion = "human-move-corpus-checkpoint-v1",
                distributionSha256 = digest,
            )
        val row =
            EvaluationEvidenceRow(
                playerId = playerId,
                gameId = UUID.randomUUID(),
                occurrenceId = occurrenceId,
                positionIdentity = "r1",
                preMovePly = 2,
                move = "e4",
                playerColor = "WHITE",
                loss = 0.50,
                engineDepth = 18,
                observedOutcome = ObservedGameOutcome.WIN,
                objectiveOutcome = ObjectiveOutcome.WEAK,
                practicalCandidate = true,
                practicalEligible = true,
                practicalWins = 1,
                practicalDraws = 0,
                practicalLosses = 0,
            )
        val snapshot =
            EvaluationEvidenceSnapshot(
                id = UUID.randomUUID(),
                referencePopulation = population,
                occurrenceEvidenceId = null,
                players =
                    setOf(
                        EvaluationEvidencePlayer(playerId, "evaluation-player"),
                        EvaluationEvidencePlayer(UUID.randomUUID(), "zero-row"),
                    ),
                configuration =
                    EvaluationEvidenceConfiguration(
                        EvaluationEvidenceSnapshotService.APPROVED_THRESHOLDS,
                        3,
                        5,
                        "BOTH",
                        "CHESS_COM",
                        null,
                    ),
                sourceRevision = "source",
                engineIdentity = "engine",
                parserIdentity = "parser",
                rows = listOf(row),
            )
        return E6AdmissionEvidence(
            referencePopulation = population,
            projection = E6ReferenceProjection(finalized = true, verified = true, distributionSha256 = digest, rows = projectionRows),
            snapshot = snapshot,
            occurrences =
                mapOf(
                    occurrenceId to
                        E6ReferenceOccurrence(
                            sourceRunId = runId,
                            qualifyingOrdinal = 7,
                            preMovePly = 2,
                            providerGameId = "reference-game",
                            positionHash = "r1",
                            movePlayed = "e4",
                            contentDigest = population.contentDigest,
                            coveredPrefix = population.coveredPrefix,
                        ),
                ),
            analysisVersion = "e6-v1",
        )
    }
}
