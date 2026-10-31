package com.chessecho.integration.corpus

import com.chessecho.service.EvaluationEvidenceIntegrityException
import com.chessecho.service.EvaluationEvidencePlayer
import com.chessecho.service.LocationGroupCount
import com.chessecho.service.ObjectiveOutcome
import com.chessecho.service.PositionCoverage
import com.chessecho.service.ReferenceCoverageAnalysisService
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvaluationEvidenceSnapshotPersistenceIntegrationTest : EvaluationEvidenceSnapshotBaseIntegrationTest() {
    @Test
    fun `unavailable non-core evidence round trips and identical retry remains idempotent`() {
        val runId = finalizedRunWithOccurrences()
        val snapshot = withoutNonCoreEvidence(snapshotFor(runId, occurrencesOf(runId)))
        val id = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        assertEquals(
            id,
            evaluationEvidenceSnapshotService.persist(
                snapshot.copy(id = UUID.randomUUID()),
                snapshot.referencePopulation,
            ),
        )
        val retained = evaluationEvidenceSnapshotService.readVerifiedPersisted(runId)
        assertEquals(snapshot.rows, retained.rows)
        assertNull(retained.sourceRevision)
        assertNull(retained.engineIdentity)
        assertNull(retained.parserIdentity)
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evaluation_evidence_snapshot WHERE source_run_id = ?",
                Int::class.java,
                runId,
            ),
        )
        assertEquals(
            snapshot.rows.size,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evaluation_evidence_row WHERE snapshot_id = ?",
                Int::class.java,
                id,
            ),
        )
    }

    @Test
    fun `persists a valid snapshot bound to a verified occurrence and reconstructs it`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot = snapshotFor(runId, occurrences)
        evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val result = evaluationEvidenceSnapshotService.reconstructPersisted(runId)
        assertEquals(2, result.objectiveWeakness.getValue(snapshot.players.first().id).getValue(0.30))
    }

    @Test
    fun `persists and analyzes distinct retained occurrences at the same operational game ply`() {
        val runId = finalizedRunWithSamePlyOccurrences()
        val occurrences = occurrencesOf(runId)
        assertEquals(2, occurrences.size)
        val gameId = UUID.randomUUID()
        val snapshot = snapshotFor(runId, occurrences, game = gameId)
        val snapshotId = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val retainedRows =
            jdbcTemplate.query(
                "SELECT player_id, game_id, occurrence_id, position_identity, pre_move_ply" +
                    " FROM evaluation_evidence_row WHERE snapshot_id = ? ORDER BY occurrence_id",
                { rs, _ ->
                    PersistedEvidenceIdentity(
                        playerId = rs.getObject("player_id", UUID::class.java),
                        gameId = rs.getObject("game_id", UUID::class.java),
                        occurrenceId = rs.getObject("occurrence_id", UUID::class.java),
                        positionIdentity = rs.getString("position_identity"),
                        preMovePly = rs.getInt("pre_move_ply"),
                    )
                },
                snapshotId,
            )
        assertEquals(2, retainedRows.size)
        val projectionId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM human_move_corpus_projection WHERE source_run_id = ? AND finalized AND verified",
                UUID::class.java,
                runId,
            )!!
        val admitted = retainedEvaluationEvidenceService.loadAndAdmit(runId, projectionId, "e6-v1")
        val locations =
            ReferenceCoverageAnalysisService()
                .analyze(admitted.toAnalysisInput())
                .locations
                .getValue(snapshot.players.single().identity)
                .at(0.50)
        assertEquals(2, locations.occurrenceCount)
        assertEquals(2, locations.uniqueGameCount)
    }

    @Test
    fun `identical retry compares roster and retained metadata rather than digest alone`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot = snapshotFor(runId, occurrences)
        evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val conflictingRoster =
            snapshot.copy(
                players = snapshot.players + EvaluationEvidencePlayer(UUID.randomUUID(), "new-player"),
            )
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.persist(conflictingRoster, conflictingRoster.referencePopulation)
        }
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.persist(
                snapshot.copy(engineIdentity = "different-engine"),
                snapshot.referencePopulation,
            )
        }
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.persist(
                snapshot.copy(configuration = snapshot.configuration.copy(minTimesReached = 2)),
                snapshot.referencePopulation,
            )
        }
    }

    @Test
    fun `retained read returns exact metadata roster and every persisted row`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val zeroRowPlayer = UUID.randomUUID()
        val player = UUID.randomUUID()
        val snapshot =
            snapshotFor(runId, occurrences, player = player).copy(
                players =
                    setOf(
                        EvaluationEvidencePlayer(player, "player"),
                        EvaluationEvidencePlayer(zeroRowPlayer, "zero-row-player"),
                    ),
            )
        val persistedId = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val retained = evaluationEvidenceSnapshotService.readVerifiedPersisted(runId)
        assertEquals(persistedId, retained.id)
        assertEquals(snapshot.referencePopulation, retained.referencePopulation)
        assertEquals(snapshot.players, retained.players)
        assertEquals(snapshot.configuration, retained.configuration)
        assertEquals(snapshot.sourceRevision, retained.sourceRevision)
        assertEquals(snapshot.engineIdentity, retained.engineIdentity)
        assertEquals(snapshot.parserIdentity, retained.parserIdentity)
        assertEquals(snapshot.rows, retained.rows)
    }

    @Test
    fun `retained read fails closed when a retained row is inserted after admission`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot = snapshotFor(runId, occurrences).let { it.copy(rows = it.rows.take(1)) }
        val snapshotId = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val extra = snapshot.rows.first()
        jdbcTemplate.update(
            "INSERT INTO evaluation_evidence_row" +
                " (id, snapshot_id, player_id, game_id, occurrence_id, position_identity, pre_move_ply," +
                " move_played, player_color, loss, engine_depth, observed_outcome, objective_outcome," +
                " practical_candidate, practical_eligible, practical_wins, practical_draws, practical_losses)" +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(),
            snapshotId,
            extra.playerId,
            UUID.randomUUID(),
            occurrences.last().id,
            occurrences.last().positionHash,
            occurrences.last().preMovePly,
            extra.move,
            extra.playerColor,
            extra.loss,
            extra.engineDepth,
            extra.observedOutcome.name,
            extra.objectiveOutcome?.name,
            extra.practicalCandidate,
            extra.practicalEligible,
            extra.practicalWins,
            extra.practicalDraws,
            extra.practicalLosses,
        )
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.readVerifiedPersisted(runId)
        }
    }

    @Test
    fun `retained snapshot and row tables are immutable`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot = snapshotFor(runId, occurrences)
        val id = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        assertFailsWith<Exception> {
            jdbcTemplate.update("UPDATE evaluation_evidence_snapshot SET evidence_digest = evidence_digest WHERE id = ?", id)
        }
        assertFailsWith<Exception> {
            jdbcTemplate.update("DELETE FROM evaluation_evidence_snapshot WHERE id = ?", id)
        }
        val rowId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM evaluation_evidence_row WHERE snapshot_id = ? LIMIT 1",
                UUID::class.java,
                id,
            )
        assertFailsWith<Exception> {
            jdbcTemplate.update("DELETE FROM evaluation_evidence_row WHERE id = ?", rowId)
        }
    }

    @Test
    fun `post-purge reconstruction uses only retained evidence and its verified bindings`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot = snapshotFor(runId, occurrences)
        evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        jdbcTemplate.execute("TRUNCATE human_move_corpus_imported_observation, human_move_corpus_imported_game CASCADE")
        jdbcTemplate.execute("TRUNCATE human_move_corpus_observation CASCADE")
        val result = evaluationEvidenceSnapshotService.reconstructPersisted(runId)
        assertTrue(result.objectiveWeakness.getValue(snapshot.players.first().id).getValue(0.30) == 2)
    }

    @Test
    fun `verified retained read survives purge and retains zero-row roster`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val zeroRowPlayer = UUID.randomUUID()
        val player = UUID.randomUUID()
        val snapshot =
            snapshotFor(runId, occurrences, player = player).copy(
                players =
                    setOf(
                        EvaluationEvidencePlayer(player, "player"),
                        EvaluationEvidencePlayer(zeroRowPlayer, "zero-row-player"),
                    ),
            )
        evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        jdbcTemplate.execute("TRUNCATE human_move_corpus_imported_observation, human_move_corpus_imported_game CASCADE")
        jdbcTemplate.execute("TRUNCATE human_move_corpus_observation CASCADE")
        val retained = evaluationEvidenceSnapshotService.readVerifiedPersisted(runId)
        assertEquals(snapshot.players, retained.players)
        assertEquals(snapshot.referencePopulation, retained.referencePopulation)
        assertEquals(snapshot.rows, retained.rows)
    }

    @Test
    fun `retained only analysis admits absent non-core evidence and preserves locations after purge`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val playerId = UUID.randomUUID()
        val zeroRowPlayerId = UUID.randomUUID()
        val snapshot =
            withoutNonCoreEvidence(snapshotFor(runId, occurrences, player = playerId)).copy(
                players =
                    setOf(
                        EvaluationEvidencePlayer(playerId, "evaluated"),
                        EvaluationEvidencePlayer(zeroRowPlayerId, "zero-row"),
                    ),
            )
        evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val projectionId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM human_move_corpus_projection WHERE source_run_id = ? AND finalized AND verified",
                UUID::class.java,
                runId,
            )!!
        jdbcTemplate.execute("TRUNCATE human_move_corpus_imported_observation, human_move_corpus_imported_game CASCADE")
        val admitted = retainedEvaluationEvidenceService.loadAndAdmit(runId, projectionId, "e6-v1")
        val result = ReferenceCoverageAnalysisService().analyze(admitted.toAnalysisInput())
        assertEquals(snapshot.rows, admitted.snapshot.rows)
        assertNull(evaluationEvidenceSnapshotService.reconstructPersisted(runId).practicalEvidence.getValue(playerId))
        assertNull(evaluationEvidenceSnapshotService.reconstructPersisted(runId).practicalEvidence.getValue(zeroRowPlayerId))
        assertEquals(occurrences.map { it.positionHash }.toSet(), admitted.referencePositions)
        assertEquals(occurrences.map { it.id }.toSet(), admitted.resolvedOccurrences.keys)
        assertEquals(PositionCoverage(2, 2, 1.0), result.byPlayer.getValue("evaluated").at(0.50))
        assertEquals(PositionCoverage(0, 0, null), result.byPlayer.getValue("zero-row").at(0.50))
        assertEquals(
            2,
            result.locations
                .getValue("evaluated")
                .at(0.50)
                .occurrenceCount,
        )
        assertEquals(
            1,
            result.locations
                .getValue("evaluated")
                .at(0.50)
                .uniqueGameCount,
        )
    }

    @Test
    fun `retained analysis counts repeated operational links as one reference location`() {
        val runId = finalizedRunWithOccurrences()
        val occurrence = occurrencesOf(runId).first()
        val base = snapshotFor(runId, listOf(occurrence))
        val first = base.rows.single()
        val second = first.copy(gameId = UUID.randomUUID(), loss = 0.35)
        val snapshot = base.copy(rows = listOf(first, second))
        evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val projectionId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM human_move_corpus_projection WHERE source_run_id = ? AND finalized AND verified",
                UUID::class.java,
                runId,
            )!!
        val admitted = retainedEvaluationEvidenceService.loadAndAdmit(runId, projectionId, "e6-v1")
        val result = ReferenceCoverageAnalysisService().analyze(admitted.toAnalysisInput())
        assertEquals(2, admitted.snapshot.rows.size)
        val reconstruction = evaluationEvidenceSnapshotService.reconstructPersisted(runId)
        assertEquals(2, reconstruction.objectiveWeakness.getValue(first.playerId).getValue(0.30))
        assertEquals(1, reconstruction.objectiveWeakness.getValue(first.playerId).getValue(0.50))
        listOf(0.30, 0.50).forEach { threshold ->
            val location = result.locations.getValue("player").at(threshold)
            assertEquals(1, location.occurrenceCount)
            assertEquals(1, location.denominator)
            assertEquals(1, location.uniqueGameCount)
            assertEquals(LocationGroupCount(1, 1), location.groupsByPreMovePly.getValue(occurrence.preMovePly))
        }
    }

    @Test
    fun `absent versus supplied row and metadata evidence conflicts in both directions`() {
        val runId = finalizedRunWithOccurrences()
        val snapshot = snapshotFor(runId, occurrencesOf(runId))
        val absent = withoutNonCoreEvidence(snapshot)
        evaluationEvidenceSnapshotService.persist(absent, absent.referencePopulation)
        listOf(
            absent.copy(sourceRevision = snapshot.sourceRevision),
            absent.copy(engineIdentity = snapshot.engineIdentity),
            absent.copy(parserIdentity = snapshot.parserIdentity),
            absent.copy(rows = absent.rows.map { it.copy(objectiveOutcome = ObjectiveOutcome.WEAK) }),
            absent.copy(rows = snapshot.rows),
        ).forEach { supplied ->
            assertFailsWith<EvaluationEvidenceIntegrityException> {
                evaluationEvidenceSnapshotService.persist(supplied, supplied.referencePopulation)
            }
        }
        val otherRunId = finalizedRunWithOccurrences()
        val complete = snapshotFor(otherRunId, occurrencesOf(otherRunId))
        evaluationEvidenceSnapshotService.persist(complete, complete.referencePopulation)
        listOf(
            complete.copy(sourceRevision = null),
            complete.copy(engineIdentity = null),
            complete.copy(parserIdentity = null),
            complete.copy(rows = complete.rows.map { it.copy(objectiveOutcome = null) }),
            complete.copy(rows = withoutNonCoreEvidence(complete).rows),
        ).forEach { missing ->
            assertFailsWith<EvaluationEvidenceIntegrityException> {
                evaluationEvidenceSnapshotService.persist(missing, missing.referencePopulation)
            }
        }
    }

    @Test
    fun `retrying identical evidence is idempotent and rejects a conflicting replacement`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot = snapshotFor(runId, occurrences)

        val firstId = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val secondId = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        assertEquals(firstId, secondId)
        assertEquals(
            occurrences.size,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evaluation_evidence_row WHERE snapshot_id = ?",
                Int::class.java,
                firstId,
            ),
        )

        val conflicting = snapshot.copy(rows = snapshot.rows.map { it.copy(loss = it.loss + 0.1) })
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.persist(conflicting, conflicting.referencePopulation)
        }
    }
}
