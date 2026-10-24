package com.chessecho.integration.corpus

import com.chessecho.domain.HumanMoveCorpusRunStatus
import com.chessecho.service.HumanMoveCorpusRunNotRunningException
import com.chessecho.service.HumanMoveCorpusRunOutcome
import com.chessecho.service.HumanMoveCorpusService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.dao.DataAccessException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HumanMoveCorpusRunOutcomeIntegrationTest : HumanMoveCorpusBaseIntegrationTest() {
    @Test
    fun `a run persists application-generated identity and immutable canonical configuration`() {
        val request =
            runRequest(
                maxQualifyingGames = 25,
                maxDepth = 3,
                seeds = listOf("Seed-B", "seed-a"),
                excluded = listOf("X"),
                maxPlayers = 40,
                batchSize = 7,
            )
        val runId = gameWriter.createRun(request)
        val other = gameWriter.createRun(request)
        assertNotEquals(runId, other, "every invocation is a distinct run")

        val row = runRow(runId)
        assertEquals("1000-1200", row["rating_band"])
        assertEquals("RUNNING", row["status"])
        assertEquals(0, row["committed_frontier"])
        assertEquals(25, row["max_qualifying_games"])
        assertEquals(3, row["max_depth"])
        assertEquals(40, row["max_players"])
        assertEquals(7, row["batch_size"])
        assertEquals(100, row["max_games_per_player"])
        assertEquals("rev-423-test", row["source_revision"])
        assertEquals(HumanMoveCorpusService.ALGORITHM_VERSION, row["algorithm_version"])
        val seeds = row["seed_players"].toString()
        assertTrue(seeds.indexOf("seed-b") in 0 until seeds.indexOf("seed-a"), "seed order preserved: $seeds")
        assertTrue(row["excluded_players"].toString().contains("x"))
        assertEquals(sha256(row["request_json"].toString()), row["request_sha256"])
        assertEquals(runRow(other)["request_sha256"], row["request_sha256"], "same configuration, same canonical hash")
    }

    @Test
    fun `database rejects configuration changes, run deletion, frontier decrease, and terminal-status exit`() {
        val runId = newRun()
        commitAll(runId, (1..2).map(::syntheticCandidate))

        listOf(
            "UPDATE human_move_corpus_run SET rating_band = '1400-1600' WHERE id = ?",
            "UPDATE human_move_corpus_run SET seed_players = '[\"other\"]' WHERE id = ?",
            "UPDATE human_move_corpus_run SET excluded_players = '[\"other\"]' WHERE id = ?",
            "UPDATE human_move_corpus_run SET max_qualifying_games = 1 WHERE id = ?",
            "UPDATE human_move_corpus_run SET max_depth = 9 WHERE id = ?",
            "UPDATE human_move_corpus_run SET max_players = 1 WHERE id = ?",
            "UPDATE human_move_corpus_run SET batch_size = 1 WHERE id = ?",
            "UPDATE human_move_corpus_run SET max_games_per_player = 1 WHERE id = ?",
            "UPDATE human_move_corpus_run SET source_revision = 'forged' WHERE id = ?",
            "UPDATE human_move_corpus_run SET algorithm_version = 'forged' WHERE id = ?",
            "UPDATE human_move_corpus_run SET request_json = '{}' WHERE id = ?",
            "UPDATE human_move_corpus_run SET request_sha256 = 'forged' WHERE id = ?",
            "UPDATE human_move_corpus_run SET created_at = now() - interval '1 day' WHERE id = ?",
            "UPDATE human_move_corpus_run SET committed_frontier = 1 WHERE id = ?",
            "DELETE FROM human_move_corpus_run WHERE id = ?",
        ).forEach { sql ->
            assertFailsWith<DataAccessException>(sql) { jdbcTemplate.update(sql, runId) }
        }
        assertEquals(2, frontier(runId))

        gameWriter.finishRun(
            runId,
            HumanMoveCorpusRunOutcome(
                status = HumanMoveCorpusRunStatus.COMPLETED,
                stopReason = "EMPTY_FRONTIER",
                rejectedGameCount = 0,
                archiveFetchFailureCount = 0,
                failureDetails = null,
            ),
        )
        listOf("RUNNING", "FAILED", "INCOMPLETE").forEach { status ->
            assertFailsWith<DataAccessException>(status) {
                jdbcTemplate.update("UPDATE human_move_corpus_run SET status = ? WHERE id = ?", status, runId)
            }
        }
        assertEquals("COMPLETED", runRow(runId)["status"])
        assertFailsWith<HumanMoveCorpusRunNotRunningException> { gameWriter.commitGame(runId, syntheticCandidate(3)) }
        assertEquals(2, frontier(runId))
    }

    @Test
    fun `database rejects game and observation UPDATE and DELETE`() {
        val runId = newRun()
        commitAll(runId, listOf(syntheticCandidate(1)))
        val game = gameId(runId, 1)

        listOf(
            "UPDATE human_move_corpus_game SET qualifying_ordinal = 2 WHERE id = ?",
            "UPDATE human_move_corpus_game SET pgn = 'forged' WHERE id = ?",
            "UPDATE human_move_corpus_game SET observation_total = 99 WHERE id = ?",
            "DELETE FROM human_move_corpus_game WHERE id = ?",
            "UPDATE human_move_corpus_observation SET observation_count = 99 WHERE game_id = ?",
            "UPDATE human_move_corpus_observation SET position_hash = 'forged' WHERE game_id = ?",
            "DELETE FROM human_move_corpus_observation WHERE game_id = ?",
        ).forEach { sql ->
            assertFailsWith<DataAccessException>(sql) { jdbcTemplate.update(sql, game) }
        }
        assertEquals(3L, count("SELECT count(*) FROM human_move_corpus_observation"))
    }

    @Test
    fun `mid-run permanent failure keeps earlier games committed and marks the run FAILED`() {
        stubThreeGames()
        installGameFailure("http://b")

        val response = corpusService.runCorpus(runRequest(maxQualifyingGames = null, maxDepth = 0))

        assertEquals(HumanMoveCorpusRunStatus.FAILED, response.status)
        assertEquals("FAILED", runRow(response.runId)["status"])
        assertEquals(1, response.committedFrontier)
        assertTrue(response.failureDetails!!.contains("http://b"), response.failureDetails)
        assertEquals(listOf(1), ordinals(response.runId))
        assertEquals(
            0L,
            count("SELECT count(*) FROM human_move_corpus_game WHERE provider_game_id IN ('http://b', 'http://c')"),
        )
        assertEquals(HumanMoveCorpusRunStatus.FAILED, checkpoint(response.runId, 1).runStatus)
        assertFailsWith<IllegalArgumentException> { checkpoint(response.runId, 2) }
    }

    @Test
    fun `two transient failures then success yield exactly one contribution at the next contiguous ordinal`() {
        stubThreeGames()
        installTransientGameFailure("http://b", failures = 2)

        val response = corpusService.runCorpus(runRequest(maxQualifyingGames = null, maxDepth = 0))

        assertEquals(HumanMoveCorpusRunStatus.COMPLETED, response.status)
        assertEquals(listOf(1, 2, 3), ordinals(response.runId))
        assertEquals(
            listOf("http://a", "http://b", "http://c"),
            jdbcTemplate.queryForList(
                "SELECT provider_game_id FROM human_move_corpus_game WHERE run_id = ? ORDER BY qualifying_ordinal",
                String::class.java,
                response.runId,
            ),
        )
        assertEquals(3L, count("SELECT nextval('corpus_test_attempts')") - 1, "three attempts for game b")
    }

    @Test
    fun `exhausted transient retries mark the run FAILED without consuming an ordinal`() {
        stubThreeGames()
        installTransientGameFailure("http://b", failures = 1000)

        val response = corpusService.runCorpus(runRequest(maxQualifyingGames = null, maxDepth = 0))

        assertEquals(HumanMoveCorpusRunStatus.FAILED, response.status)
        assertEquals(listOf(1), ordinals(response.runId))
        assertEquals(1, frontier(response.runId))
        assertEquals(
            HumanMoveCorpusService.MAX_COMMIT_ATTEMPTS.toLong(),
            count("SELECT nextval('corpus_test_attempts')") - 1,
        )
    }

    @Test
    fun `fetch failure ends the run INCOMPLETE and its frontier stays checkpointable`() {
        stubThreeGames()
        whenever(chessComClient.fetchArchiveUrls("p3")).thenThrow(RuntimeException("upstream unavailable"))

        val response = corpusService.runCorpus(runRequest(maxQualifyingGames = null, maxDepth = 1))

        assertEquals(HumanMoveCorpusRunStatus.INCOMPLETE, response.status)
        assertEquals("INCOMPLETE", runRow(response.runId)["status"])
        assertEquals(1, response.archiveFetchFailureCount)
        assertTrue(response.failureDetails!!.contains("p3"))
        assertEquals(3, response.committedFrontier)
        assertEquals(HumanMoveCorpusRunStatus.INCOMPLETE, checkpoint(response.runId, 3).runStatus)
    }

    @Test
    fun `a crashed RUNNING run is never completed by a new invocation and allows checkpoints only through its frontier`() {
        val crashed = newRun()
        commitAll(crashed, (1..3).map(::syntheticCandidate))
        stubThreeGames()

        val restarted = corpusService.runCorpus(runRequest(maxQualifyingGames = null, maxDepth = 0))

        assertNotEquals(crashed, restarted.runId)
        assertEquals(HumanMoveCorpusRunStatus.COMPLETED, restarted.status)
        assertEquals("RUNNING", runRow(crashed)["status"])
        assertEquals(3, frontier(crashed))
        assertEquals(HumanMoveCorpusRunStatus.RUNNING, checkpoint(crashed, 3).runStatus)
        assertFailsWith<IllegalArgumentException> { checkpoint(crashed, 4) }
        assertEquals(HumanMoveCorpusRunStatus.RUNNING, corpusService.getRun(crashed).status)
        assertTrue(corpusService.listRuns().map { it.runId }.containsAll(listOf(crashed, restarted.runId)))
    }

    @Test
    fun `cross-run and cross-band contributions never contaminate each other`() {
        val runA = newRun("1000-1200")
        val runB = newRun("1400-1600")
        gameWriter.commitGame(runA, candidate("http://shared-url", Triple("pos-common", "e4", 2)))
        gameWriter.commitGame(runB, candidate("http://shared-url", Triple("pos-common", "e4", 5), opponentRating = 1500))
        gameWriter.commitGame(runB, candidate("http://only-b", Triple("pos-common", "d4", 1), opponentRating = 1500))

        val a = checkpoint(runA, 1)
        val b = checkpoint(runB, 2)

        assertEquals(listOf(Triple("pos-common", "e4", 2)), rowsOf(a))
        assertEquals("1000-1200", a.ratingBand)
        assertEquals(listOf(Triple("pos-common", "d4", 1), Triple("pos-common", "e4", 5)), rowsOf(b))
        assertEquals("1400-1600", b.ratingBand)
        assertFailsWith<IllegalArgumentException> { checkpoint(runA, 2) }
    }
}
