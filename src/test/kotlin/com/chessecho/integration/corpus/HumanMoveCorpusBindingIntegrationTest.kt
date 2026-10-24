package com.chessecho.integration.corpus

import com.chessecho.humanmove.artifact.HumanMoveCorpusExportRequest
import com.chessecho.service.GameParserService
import com.chessecho.service.HumanMoveCorpusCommitOutcome
import com.chessecho.service.HumanMoveCorpusContributionMismatchException
import com.chessecho.service.HumanMoveCorpusMaterializeRequest
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HumanMoveCorpusBindingIntegrationTest : HumanMoveCorpusBaseIntegrationTest() {
    @Test
    fun `partial occurrence binding remains valid through finalization`() {
        val runId = newRun()
        commitAll(runId, adversarialLifecycleCandidates("partial-binding"))
        val exported = artifactService.export(HumanMoveCorpusExportRequest(runId, 1))
        val bytes = Files.readAllBytes(artifactService.archivePath(exported.contentDigest))
        importService.import(bytes, exported.contentDigest)
        val projection =
            materializationService.materialize(
                HumanMoveCorpusMaterializeRequest(
                    contentDigest = exported.contentDigest,
                    prefixN = 1,
                    minObservations = 1,
                ),
            )

        projectionFinalizationService.finalize(projection.projectionId)

        val binding = occurrenceService.verifyRequiredBinding(runId, exported.contentDigest, 1)

        assertEquals(runId, binding.sourceRunId)
        assertEquals(exported.contentDigest, binding.contentDigest)
        assertEquals(1, binding.coveredPrefix)
    }

    @Test
    fun `committing a game retains every occurrence location while preserving aggregate checkpoint positions`() {
        val run = newRun()
        val initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val initialHash = GameParserService.generateHash(initialFen)
        val game = candidate("http://lossless-game", Triple(initialHash, "e4", 1))

        assertEquals(HumanMoveCorpusCommitOutcome.COMMITTED, gameWriter.commitGame(run, game).outcome)

        val occurrences =
            jdbcTemplate.queryForList(
                """
                SELECT source_run_id, qualifying_ordinal, provider_game_id, pre_move_ply,
                       move_played, position_hash
                FROM human_move_corpus_occurrence
                WHERE source_run_id = ?
                ORDER BY qualifying_ordinal, pre_move_ply, provider_game_id, move_played, position_hash
                """.trimIndent(),
                run,
            )
        assertTrue(occurrences.isNotEmpty(), "lossless occurrence evidence must be retained")
        assertEquals(
            occurrences.map { ((it["pre_move_ply"] ?: error("missing ply")) as Number).toInt() }.distinct().size,
            occurrences.size,
            "one source game must retain one row per location",
        )

        val aggregatePositions = checkpoint(run, 1).rows.map { it.positionHash }.toSet()
        assertEquals(
            aggregatePositions,
            occurrences.map { it["position_hash"].toString() }.toSet(),
            "occurrence evidence and aggregate checkpoint must describe the same positions",
        )

        val aggregateFromOccurrences =
            jdbcTemplate.queryForList(
                """
                SELECT position_hash, move_played, COUNT(*) AS occurrence_count
                FROM human_move_corpus_occurrence
                WHERE source_run_id = ? AND covered_prefix <= ?
                GROUP BY position_hash, move_played
                ORDER BY position_hash, move_played
                """.trimIndent(),
                run,
                1,
            ).map {
                Triple(
                    it["position_hash"].toString(),
                    it["move_played"].toString(),
                    ((it["occurrence_count"] ?: error("missing count")) as Number).toInt(),
                )
            }
        assertEquals(rowsOf(checkpoint(run, 1)), aggregateFromOccurrences)
    }

    @Test
    fun `same-game retry compares the complete occurrence contribution and leaves conflicting retries atomic`() {
        val run = newRun()
        val original = candidate("http://retry-occurrence", Triple("retry-position", "e4", 2))
        assertEquals(HumanMoveCorpusCommitOutcome.COMMITTED, gameWriter.commitGame(run, original).outcome)

        val beforeGames = count("SELECT count(*) FROM human_move_corpus_game")
        val beforeOccurrences =
            count("SELECT count(*) FROM human_move_corpus_occurrence WHERE source_run_id = '$run'")

        assertEquals(HumanMoveCorpusCommitOutcome.ALREADY_COMMITTED, gameWriter.commitGame(run, original).outcome)
        assertFailsWith<HumanMoveCorpusContributionMismatchException> {
            gameWriter.commitGame(run, candidate("http://retry-occurrence", Triple("retry-position", "d4", 2)))
        }

        assertEquals(beforeGames, count("SELECT count(*) FROM human_move_corpus_game"))
        assertEquals(
            beforeOccurrences,
            count("SELECT count(*) FROM human_move_corpus_occurrence WHERE source_run_id = '$run'"),
            "a conflicting retry must not partially replace occurrence evidence",
        )
    }

    @Test
    fun `repeated same-position and SAN occurrences at different plies remain distinct lossless evidence`() {
        val run = newRun()
        gameWriter.commitGame(run, candidate("http://same-san", Triple("same-position", "e4", 2)))

        val rows =
            jdbcTemplate.queryForList(
                """
                SELECT pre_move_ply, position_hash, move_played
                FROM human_move_corpus_occurrence
                WHERE source_run_id = ? AND provider_game_id = ?
                ORDER BY pre_move_ply
                """.trimIndent(),
                run,
                "http://same-san",
            )
        assertTrue(rows.size >= 2, "the same position/SAN must be retained at each source ply")
        assertEquals(rows.size, rows.map { it["pre_move_ply"] }.toSet().size)
        assertEquals(setOf("same-position"), rows.map { it["position_hash"].toString() }.toSet())
        assertEquals(setOf("e4"), rows.map { it["move_played"].toString() }.toSet())
    }

    @Test
    fun `post-binding occurrence rows reject tampering and rebinding`() {
        val population = finalizedAndPurgedPopulation("immutable-binding")
        val alternate = finalizedAndPurgedPopulation("alternate-binding")
        val originalRows = occurrenceRows(population.runId)
        val originalBinding = occurrenceBinding(population.runId)
        val occurrenceId = originalRows.first()["id"]

        assertRejectedRowMutations(population, originalRows, originalBinding, alternate.runId, occurrenceId)
        assertRejectedRowReplacementAndRebinding(population, alternate, originalRows, originalBinding, occurrenceId)
    }

    private fun assertRejectedRowMutations(
        population: FinalizedPopulation,
        originalRows: List<Map<String, Any?>>,
        originalBinding: Map<String, Any?>,
        alternateRunId: UUID,
        occurrenceId: Any?,
    ) {
        val rejectedMutations =
            listOf(
                "UPDATE human_move_corpus_occurrence SET source_run_id = ? WHERE id = ?" to
                    arrayOf<Any?>(alternateRunId, occurrenceId),
                "UPDATE human_move_corpus_occurrence SET qualifying_ordinal = qualifying_ordinal + 10 WHERE id = ?" to
                    arrayOf<Any?>(occurrenceId),
                "UPDATE human_move_corpus_occurrence SET provider_game_id = 'substituted' WHERE id = ?" to
                    arrayOf<Any?>(occurrenceId),
                "UPDATE human_move_corpus_occurrence SET pre_move_ply = pre_move_ply + 100 WHERE id = ?" to
                    arrayOf<Any?>(occurrenceId),
                "UPDATE human_move_corpus_occurrence SET move_played = 'd4' WHERE id = ?" to
                    arrayOf<Any?>(occurrenceId),
                "UPDATE human_move_corpus_occurrence SET position_hash = 'substituted' WHERE id = ?" to
                    arrayOf<Any?>(occurrenceId),
                "UPDATE human_move_corpus_occurrence SET content_digest = ? WHERE id = ?" to
                    arrayOf<Any?>("f".repeat(64), occurrenceId),
                "UPDATE human_move_corpus_occurrence SET covered_prefix = covered_prefix + 1 WHERE id = ?" to
                    arrayOf<Any?>(occurrenceId),
            )
        rejectedMutations.forEach { (sql, arguments) ->
            assertFailsWith<DataAccessException>("post-binding mutation must fail: $sql") {
                jdbcTemplate.update(sql, *arguments)
            }
            assertEquals(originalRows, occurrenceRows(population.runId), "rejected mutation must preserve exact original evidence")
            assertEquals(originalBinding, occurrenceBinding(population.runId), "rejected mutation must preserve the database binding")
        }
    }

    private fun assertRejectedRowReplacementAndRebinding(
        population: FinalizedPopulation,
        alternate: FinalizedPopulation,
        originalRows: List<Map<String, Any?>>,
        originalBinding: Map<String, Any?>,
        occurrenceId: Any?,
    ) {
        assertFailsWith<DataAccessException>("a finalized occurrence must not be deleted for replacement") {
            jdbcTemplate.update("DELETE FROM human_move_corpus_occurrence WHERE id = ?", occurrenceId)
        }
        val row = originalRows.first()
        assertFailsWith<DataAccessException>("reinserting a finalized occurrence must not replace its existing row") {
            jdbcTemplate.update(
                """
                INSERT INTO human_move_corpus_occurrence
                    (id, source_run_id, qualifying_ordinal, provider_game_id, pre_move_ply, move_played,
                     position_hash, content_digest, covered_prefix)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                occurrenceId,
                row["source_run_id"],
                row["qualifying_ordinal"],
                row["provider_game_id"],
                row["pre_move_ply"],
                row["move_played"],
                row["position_hash"],
                row["content_digest"],
                row["covered_prefix"],
            )
        }
        assertEquals(originalRows, occurrenceRows(population.runId), "delete/reinsert attempts must leave exact rows unchanged")
        assertEquals(originalBinding, occurrenceBinding(population.runId), "delete/reinsert attempts must preserve its binding")

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "UPDATE human_move_corpus_occurrence_binding SET content_digest = ? WHERE source_run_id = ?",
                alternate.contentDigest,
                population.runId,
            )
        }
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "UPDATE human_move_corpus_occurrence_binding SET source_run_id = ? WHERE source_run_id = ?",
                alternate.runId,
                population.runId,
            )
        }
        assertEquals(originalRows, occurrenceRows(population.runId), "failed rebind must leave original rows intact")
        assertEquals(originalBinding, occurrenceBinding(population.runId), "failed rebind must preserve the database binding")
        assertEquals(alternate.runId, occurrenceBinding(alternate.runId)["source_run_id"])
    }
}
