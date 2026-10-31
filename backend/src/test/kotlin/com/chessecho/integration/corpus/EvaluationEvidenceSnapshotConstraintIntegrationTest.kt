package com.chessecho.integration.corpus

import com.chessecho.service.EvaluationEvidenceIntegrityException
import com.chessecho.service.EvaluationEvidenceProducerInput
import com.chessecho.service.RetainedEvaluationEvidenceIntegrityException
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EvaluationEvidenceSnapshotConstraintIntegrationTest : EvaluationEvidenceSnapshotBaseIntegrationTest() {
    @Test
    fun `verification failure leaves no producer snapshot or evidence rows`() {
        val runId = finalizedRunWithSamePlyOccurrences()
        val population = producerPopulation(runId, prefixN = 2)
        val fixture = operationalProducerFixture(population)
        val invalidPopulation = population.copy(distributionSha256 = "f".repeat(64))
        val input =
            EvaluationEvidenceProducerInput(
                roster = fixture.input.roster,
                operationalOccurrenceIds = fixture.input.operationalOccurrenceIds,
                referencePopulation = invalidPopulation,
                configuration = fixture.input.configuration,
            )
        assertFailsWith<RetainedEvaluationEvidenceIntegrityException> {
            evaluationEvidenceProducerService.produce(input)
        }
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evaluation_evidence_snapshot WHERE source_run_id = ?",
                Int::class.java,
                runId,
            ),
        )
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM evaluation_evidence_row e
                JOIN evaluation_evidence_snapshot s ON s.id = e.snapshot_id
                WHERE s.source_run_id = ?
                """.trimIndent(),
                Int::class.java,
                runId,
            ),
        )
    }

    @Test
    fun `persistence failure after snapshot insertion rolls back the complete producer write`() {
        val runId = finalizedRunWithSamePlyOccurrences()
        val population = producerPopulation(runId, prefixN = 2)
        val fixture = operationalProducerFixture(population)
        jdbcTemplate.execute(
            """
            CREATE OR REPLACE FUNCTION fail_producer_evidence_row_insert() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION 'forced producer row failure';
            END;
            ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER fail_producer_evidence_row_insert
            BEFORE INSERT ON evaluation_evidence_row
            FOR EACH ROW EXECUTE FUNCTION fail_producer_evidence_row_insert()
            """.trimIndent(),
        )
        try {
            assertFailsWith<Exception> { evaluationEvidenceProducerService.produce(fixture.input) }
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_producer_evidence_row_insert ON evaluation_evidence_row")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_producer_evidence_row_insert()")
        }
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evaluation_evidence_snapshot WHERE source_run_id = ?",
                Int::class.java,
                runId,
            ),
        )
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM evaluation_evidence_row e
                JOIN evaluation_evidence_snapshot s ON s.id = e.snapshot_id
                WHERE s.source_run_id = ?
                """.trimIndent(),
                Int::class.java,
                runId,
            ),
        )
    }

    @Test
    fun `database rejects partial practical evidence without the application validator`() {
        val runId = finalizedRunWithOccurrences()
        val snapshot = withoutNonCoreEvidence(snapshotFor(runId, occurrencesOf(runId)))
        val id = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val row = snapshot.rows.first()

        val error =
            assertFailsWith<DataIntegrityViolationException> {
                jdbcTemplate.update(
                    "INSERT INTO evaluation_evidence_row " +
                        "(id, snapshot_id, player_id, game_id, occurrence_id, position_identity, pre_move_ply, move_played, " +
                        "player_color, loss, engine_depth, observed_outcome, objective_outcome, practical_candidate, " +
                        "practical_eligible, practical_wins, practical_draws, practical_losses) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    id,
                    UUID.randomUUID(),
                    row.gameId,
                    row.occurrenceId,
                    row.positionIdentity,
                    row.preMovePly,
                    row.move,
                    row.playerColor,
                    row.loss,
                    row.engineDepth,
                    row.observedOutcome.name,
                    null,
                    true,
                    null,
                    null,
                    null,
                    null,
                )
            }
        assertTrue(error.message!!.contains("ck_evaluation_evidence_row_practical_complete"))
    }

    @Test
    fun `rejects a row whose occurrence does not exist`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot =
            snapshotFor(runId, occurrences).let { snap ->
                snap.copy(rows = snap.rows.map { it.copy(occurrenceId = UUID.randomUUID()) })
            }

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        }
    }

    @Test
    fun `rejects a row whose occurrence is bound to a different run`() {
        val runId = finalizedRunWithOccurrences()
        val otherRunId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val otherOccurrences = occurrencesOf(otherRunId)
        val snapshot =
            snapshotFor(runId, occurrences).let { snap ->
                snap.copy(rows = listOf(snap.rows.first().copy(occurrenceId = otherOccurrences.first().id)))
            }

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        }
    }

    @Test
    fun `rejects a row whose position identity disagrees with the resolved occurrence`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot =
            snapshotFor(runId, occurrences).let { snap ->
                snap.copy(rows = snap.rows.map { it.copy(positionIdentity = "mismatched-position") })
            }

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        }
    }
}
