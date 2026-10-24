package com.chessecho.integration.corpus

import com.chessecho.service.EvaluationEvidenceIntegrityException
import com.chessecho.service.EvaluationEvidencePlayer
import com.chessecho.service.EvaluationEvidenceProducerInput
import com.chessecho.service.EvaluationReferencePopulation
import com.chessecho.service.ObservedGameOutcome
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvaluationEvidenceSnapshotOperationalIntegrationTest :
    EvaluationEvidenceSnapshotBaseIntegrationTest() {
    @Test
    fun `producer preserves explicit operational links and remains admissible after source rows are unavailable`() {
        val runId = finalizedRunWithSamePlyOccurrences()
        val population = producerPopulation(runId, prefixN = 2)
        val references = occurrencesOf(runId)
        val fixture = operationalProducerFixture(population)
        val events = trackVerifyAndPersistEvents()

        val snapshotId = evaluationEvidenceProducerService.produce(fixture.input)

        assertEquals(listOf("verify", "persist"), events)
        assertSnapshotAdmissibility(runId, snapshotId, fixture, references)
        assertIdempotentProductionAndTamperRejection(runId, snapshotId, fixture, population)
    }

    private fun trackVerifyAndPersistEvents(): List<String> {
        val events = mutableListOf<String>()
        doAnswer { invocation ->
            assertProducerTransaction()
            events += "verify"
            invocation.callRealMethod()
        }.whenever(selectedPopulationVerificationService).verify(any(), any())
        doAnswer { invocation ->
            assertProducerTransaction()
            events += "persist"
            invocation.callRealMethod()
        }.whenever(evaluationEvidenceSnapshotServiceSpy).persist(any(), any())
        return events
    }

    private fun assertSnapshotAdmissibility(
        runId: UUID,
        snapshotId: UUID,
        fixture: OperationalProducerFixture,
        references: List<Occurrence>,
    ) {
        val snapshot = evaluationEvidenceSnapshotService.readVerifiedPersisted(runId)
        assertEquals(snapshotId, snapshot.id)
        assertEquals(fixture.input.roster, snapshot.players)
        assertEquals(4, snapshot.rows.size)
        assertEquals(
            references.map { it.id }.toSet(),
            snapshot.rows.map { it.occurrenceId }.toSet(),
        )
        assertEquals(
            fixture.decisions.take(2).map { it.game.id }.toSet(),
            snapshot.rows.map { it.gameId }.toSet(),
        )
        val evaluatedPlayer = fixture.input.roster.single { it.id != fixture.zeroRowPlayer.id }
        assertTrue(snapshot.rows.all { it.playerId == evaluatedPlayer.id })
        assertTrue(snapshot.rows.all { it.positionIdentity == references.first().positionHash })
        assertTrue(snapshot.rows.all { it.preMovePly == references.first().preMovePly })
        assertTrue(snapshot.rows.all { it.move == "e4" && it.playerColor == "WHITE" })
        assertTrue(snapshot.rows.all { it.loss == 0.55 && it.engineDepth == 16 })
        assertTrue(snapshot.rows.all { it.observedOutcome == ObservedGameOutcome.WIN })
        assertTrue(
            snapshot.rows.all {
                it.objectiveOutcome == null && it.practicalCandidate == null && it.practicalEligible == null &&
                    it.practicalWins == null && it.practicalDraws == null && it.practicalLosses == null
            },
        )
        assertEquals(fixture.input.configuration, snapshot.configuration)
        assertNull(snapshot.occurrenceEvidenceId)
        assertNull(snapshot.sourceRevision)
        assertNull(snapshot.engineIdentity)
        assertNull(snapshot.parserIdentity)
        val practicalEvidence = evaluationEvidenceSnapshotService.reconstructPersisted(runId).practicalEvidence
        assertNull(practicalEvidence.getValue(evaluatedPlayer.id))
        assertNull(practicalEvidence.getValue(fixture.zeroRowPlayer.id))
    }

    private fun assertIdempotentProductionAndTamperRejection(
        runId: UUID,
        snapshotId: UUID,
        fixture: OperationalProducerFixture,
        population: EvaluationReferencePopulation,
    ) {
        val evidenceDigest =
            jdbcTemplate.queryForObject(
                "SELECT evidence_digest FROM evaluation_evidence_snapshot WHERE id = ?",
                String::class.java,
                snapshotId,
            )
        val reversedInput =
            EvaluationEvidenceProducerInput(
                roster = fixture.input.roster,
                operationalOccurrenceIds = fixture.input.operationalOccurrenceIds.reversed(),
                referencePopulation = population,
                configuration = fixture.input.configuration,
            )
        assertEquals(snapshotId, evaluationEvidenceProducerService.produce(reversedInput))
        assertEquals(
            evidenceDigest,
            jdbcTemplate.queryForObject(
                "SELECT evidence_digest FROM evaluation_evidence_snapshot WHERE id = ?",
                String::class.java,
                snapshotId,
            ),
        )
        val changedInput =
            EvaluationEvidenceProducerInput(
                roster = fixture.input.roster,
                operationalOccurrenceIds = listOf(fixture.input.operationalOccurrenceIds.first()),
                referencePopulation = population,
                configuration = fixture.input.configuration,
            )
        assertFailsWith<EvaluationEvidenceIntegrityException> { evaluationEvidenceProducerService.produce(changedInput) }
        assertEquals(4, evaluationEvidenceSnapshotService.readVerifiedPersisted(runId).rows.size)
    }

    @Test
    fun `producer persists an empty operational selection with its declared zero row roster`() {
        val runId = finalizedRunWithSamePlyOccurrences()
        val population = producerPopulation(runId, prefixN = 2)
        val zeroRowPlayer = EvaluationEvidencePlayer(UUID.randomUUID(), "zero-row-player")
        val input =
            EvaluationEvidenceProducerInput(
                roster = listOf(zeroRowPlayer),
                operationalOccurrenceIds = emptyList(),
                referencePopulation = population,
                configuration = producerConfiguration(),
            )

        val snapshotId = evaluationEvidenceProducerService.produce(input)
        val snapshot = evaluationEvidenceSnapshotService.readVerifiedPersisted(runId)

        assertEquals(snapshotId, snapshot.id)
        assertEquals(setOf(zeroRowPlayer), snapshot.players)
        assertTrue(snapshot.rows.isEmpty())
    }

    @Test
    fun `persists independent operational games linked to one occurrence with immutable retry semantics`() {
        val runId = finalizedRunWithOccurrences()
        val occurrence = occurrencesOf(runId).first()
        val base = snapshotFor(runId, listOf(occurrence))
        val first = base.rows.single()
        val second = first.copy(gameId = UUID.randomUUID(), loss = 0.35)
        val snapshot = base.copy(rows = listOf(first, second))

        val id = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)

        val retained = evaluationEvidenceSnapshotService.readVerifiedPersisted(runId)
        assertEquals(2, retained.rows.size)
        assertEquals(setOf(first.gameId, second.gameId), retained.rows.map { it.gameId }.toSet())
        assertEquals(setOf(occurrence.id), retained.rows.map { it.occurrenceId }.toSet())
        assertEquals(
            id,
            evaluationEvidenceSnapshotService.persist(
                snapshot.copy(rows = snapshot.rows.reversed()),
                snapshot.referencePopulation,
            ),
        )
        val reconstruction = evaluationEvidenceSnapshotService.reconstructPersisted(runId)
        assertEquals(2, reconstruction.objectiveWeakness.getValue(first.playerId).getValue(0.30))
        assertEquals(1, reconstruction.objectiveWeakness.getValue(first.playerId).getValue(0.50))

        listOf(
            snapshot.copy(rows = listOf(first.copy(loss = 0.60), second)),
            snapshot.copy(rows = listOf(first)),
            snapshot.copy(rows = listOf(first, second, first.copy(gameId = UUID.randomUUID(), loss = 0.25))),
        ).forEach { conflicting ->
            assertFailsWith<EvaluationEvidenceIntegrityException> {
                evaluationEvidenceSnapshotService.persist(conflicting, conflicting.referencePopulation)
            }
        }
        assertEquals(2, evaluationEvidenceSnapshotService.readVerifiedPersisted(runId).rows.size)
    }
}
