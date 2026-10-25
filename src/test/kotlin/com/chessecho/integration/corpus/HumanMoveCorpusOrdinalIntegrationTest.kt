package com.chessecho.integration.corpus

import com.chessecho.domain.HumanMoveCorpusRunStatus
import com.chessecho.dto.HumanMoveBfsRequest
import com.chessecho.dto.HumanMoveFinalizeRequest
import com.chessecho.service.HumanMoveCorpusCommitOutcome
import com.chessecho.service.HumanMoveCorpusContributionMismatchException
import com.chessecho.service.HumanMoveCorpusIntegrityException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataAccessException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HumanMoveCorpusOrdinalIntegrationTest : HumanMoveCorpusBaseIntegrationTest() {
    @Test
    fun `late observation insert into an already committed game is rejected with triggers enabled`() {
        val runId = newRun()
        commitAll(runId, listOf(syntheticCandidate(1)))
        val game = gameId(runId, 1)
        val positionId = jdbcTemplate.queryForObject("SELECT id FROM position WHERE hash = 'pos-shared'", UUID::class.java)

        assertFailsWith<DataAccessException> { jdbcTemplate.update(observationInsert, UUID.randomUUID(), game, positionId) }

        assertEquals(3L, count("SELECT count(*) FROM human_move_corpus_observation"))
        assertEquals(1, checkpoint(runId, 1).requestedN)
    }

    @Test
    fun `direct game inserts must be the next contiguous ordinal of a RUNNING run`() {
        val runId = newRun()
        commitAll(runId, listOf(syntheticCandidate(1)))

        assertFailsWith<DataAccessException>("gap ordinal") {
            jdbcTemplate.update(gameInsert, UUID.randomUUID(), runId, 3, "http://gap", "0".repeat(64))
        }
        assertFailsWith<DataAccessException>("reused ordinal") {
            jdbcTemplate.update(gameInsert, UUID.randomUUID(), runId, 1, "http://reuse", "0".repeat(64))
        }
        assertEquals(listOf(1), ordinals(runId))
    }

    @Test
    fun `duplicate provider game and duplicate ordinal are rejected by constraints`() {
        val runId = newRun()
        commitAll(runId, listOf(syntheticCandidate(1)))

        assertFailsWith<DataAccessException>("duplicate provider game") {
            withTriggersDisabled {
                jdbcTemplate.update(gameInsert, UUID.randomUUID(), runId, 2, "http://game-1", "0".repeat(64))
            }
        }
        assertFailsWith<DataAccessException>("duplicate ordinal") {
            withTriggersDisabled {
                jdbcTemplate.update(gameInsert, UUID.randomUUID(), runId, 1, "http://other", "0".repeat(64))
            }
        }
        assertEquals(listOf(1), ordinals(runId))
    }

    @Test
    fun `writer resolves a verified identical duplicate as ALREADY_COMMITTED and fails closed on mismatch`() {
        val runId = newRun()
        val first = syntheticCandidate(1)
        commitAll(runId, listOf(first, syntheticCandidate(2)))
        val before = snapshot()

        val retry = gameWriter.commitGame(runId, first)
        assertEquals(HumanMoveCorpusCommitOutcome.ALREADY_COMMITTED, retry.outcome)
        assertEquals(1, retry.qualifyingOrdinal)
        assertEquals(before, snapshot(), "duplicate retry changes nothing and consumes no ordinal")

        val differentObservations = candidate("http://game-1", Triple("pos-0", "e4", 7))
        assertFailsWith<HumanMoveCorpusContributionMismatchException> { gameWriter.commitGame(runId, differentObservations) }
        val differentPgn = first.copy(pgn = first.pgn + " ")
        assertFailsWith<HumanMoveCorpusContributionMismatchException> { gameWriter.commitGame(runId, differentPgn) }
        assertEquals(before, snapshot())
    }

    @Test
    fun `failure after the game row insert leaves no partial game and consumes no ordinal`() {
        val runId = newRun()
        commitAll(runId, listOf(syntheticCandidate(1)))
        installGameFailure("http://boom")

        assertFailsWith<Exception> { gameWriter.commitGame(runId, candidate("http://boom", Triple("pos-x", "e4", 1))) }

        assertEquals(1, frontier(runId))
        assertEquals(0L, count("SELECT count(*) FROM human_move_corpus_game WHERE provider_game_id = 'http://boom'"))
        assertEquals(3L, count("SELECT count(*) FROM human_move_corpus_observation"))
        assertEquals(2, gameWriter.commitGame(runId, syntheticCandidate(2)).qualifyingOrdinal, "ordinal not consumed")
        assertEquals(listOf(1, 2), ordinals(runId))
    }

    @Test
    fun `failure during observation insert rolls back the whole game`() {
        val runId = newRun()
        commitAll(runId, listOf(syntheticCandidate(1)))
        installObservationFailure("FAIL")

        assertFailsWith<Exception> {
            gameWriter.commitGame(
                runId,
                candidate(
                    "http://partial",
                    Triple("pos-new-1", "e4", 2),
                    Triple("pos-new-2", "FAIL", 1),
                    Triple("pos-new-3", "d4", 1),
                ),
            )
        }

        assertEquals(1, frontier(runId))
        assertEquals(0L, count("SELECT count(*) FROM human_move_corpus_game WHERE provider_game_id = 'http://partial'"))
        assertEquals(3L, count("SELECT count(*) FROM human_move_corpus_observation"), "no orphan observations remain")
        assertEquals(2, gameWriter.commitGame(runId, syntheticCandidate(2)).qualifyingOrdinal)
    }

    @Test
    fun `concurrent same-run commits yield unique contiguous ordinals with complete contributions, and runs stay isolated`() {
        val runA = newRun()
        val runB = newRun("1400-1600")
        val gate = 423_423L
        // Holds the first run-A writer inside its open game transaction until the
        // test observes other same-run writers blocked behind it.
        installTrigger(
            "human_move_corpus_game",
            "corpus_test_fail_game",
            "IF NEW.run_id = '$runA' AND NEW.provider_game_id = 'http://game-0' THEN " +
                "PERFORM pg_advisory_xact_lock($gate); END IF;",
        )
        val threads = 8
        val perThread = 5
        val ratingFor = mapOf(runA to 1100, runB to 1500)
        val pool = Executors.newFixedThreadPool(threads * 2)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        try {
            executeGatedOrdinalConcurrency(gate, pool, runA, runB, threads, perThread, errors)
            pool.shutdown()
            assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }

        assertTrue(errors.isEmpty(), "unexpected errors: $errors")
        val total = threads * perThread
        listOf(runA, runB).forEach { run ->
            val all = (0 until total).map { syntheticCandidate(it, ratingFor.getValue(run)) }
            assertEquals((1..total).toList(), ordinals(run))
            assertEquals(total, frontier(run))
            assertEquals(all.associate { it.providerGameId to contributionOf(it) }, persistedContributions(run))
            assertEquals(expectedRows(all, 1), rowsOf(checkpoint(run, total)), "per-game contributions complete")
        }
    }

    @Test
    fun `persisted game rows carry exact qualification and source provenance for both opponent colours`() {
        val archive2 = "https://api.chess.com/pub/player/p1/games/2024/02"
        val blackPgn = "[Event \"Live Chess\"]\n[Result \"0-1\"]\n\n1. d4 d5 2. c4 e6 0-1"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archive, archive2))
        whenever(chessComClient.fetchMonthlyGames(archive2)).thenReturn(listOf(rapid("http://white-p1", "p1", "q1")))
        whenever(chessComClient.fetchMonthlyGames(archive)).thenReturn(
            listOf(
                mapOf(
                    "url" to "http://black-p1",
                    "rules" to "chess",
                    "time_class" to "rapid",
                    "white" to mapOf("username" to "q2", "rating" to 1150),
                    "black" to mapOf("username" to "p1", "rating" to 1700),
                    "pgn" to blackPgn,
                ),
            ),
        )
        whenever(chessComClient.fetchArchiveUrls("q1")).thenReturn(emptyList())
        whenever(chessComClient.fetchArchiveUrls("q2")).thenReturn(emptyList())

        val response = corpusService.runCorpus(runRequest(maxQualifyingGames = null, maxDepth = 1))

        assertEquals(HumanMoveCorpusRunStatus.COMPLETED, response.status)
        val rows =
            jdbcTemplate.queryForList(
                "SELECT * FROM human_move_corpus_game WHERE run_id = ? ORDER BY qualifying_ordinal",
                response.runId,
            )
        assertEquals(listOf("http://white-p1", "http://black-p1"), rows.map { it["provider_game_id"] })
        val (asWhite, asBlack) = rows
        listOf(
            Triple(asWhite, listOf("q1", "BLACK", 1100), pgn),
            Triple(asBlack, listOf("q2", "WHITE", 1150), blackPgn),
        ).forEach { (row, expected, expectedPgn) ->
            assertEquals("p1", row["traversed_player"])
            assertEquals(expected[0], row["opponent"])
            assertEquals(expected[1], row["opponent_side"])
            assertEquals(expected[2], row["opponent_rating"])
            assertEquals("chess", row["rules"])
            assertEquals("rapid", row["time_class"])
            assertEquals(0, row["bfs_depth"])
            assertEquals(expectedPgn, row["pgn"])
            assertEquals(sha256(row["pgn"].toString()), row["pgn_sha256"])
        }
        val contributions = persistedContributions(response.runId)
        assertEquals(setOf("e5", "Nc6"), contributions.getValue("http://white-p1").map { it.second }.toSet())
        assertEquals(setOf("d4", "c4"), contributions.getValue("http://black-p1").map { it.second }.toSet())
        rows.forEach { row ->
            val children = contributions.getValue(row["provider_game_id"].toString())
            assertEquals(children.sumOf { it.third }, ((row["observation_total"] ?: error("missing total")) as Number).toInt())
            assertEquals(children.size, ((row["distinct_move_count"] ?: error("missing count")) as Number).toInt())
        }
    }

    @Test
    fun `nested prefixes contain exactly ordinals 1 to N, including non-round N`() {
        val runId = newRun()
        val candidates = (1..10).map(::syntheticCandidate)
        commitAll(runId, candidates)

        listOf(1, 2, 3, 5, 7, 10).forEach { n ->
            listOf(1, 2, 4).forEach { min ->
                val response = checkpoint(runId, n, min)
                val expected = expectedRows(candidates.take(n), min)
                val evaluated = candidates.take(n).flatMap { it.observations }.map { it.positionHash }.distinct().size
                assertEquals(expected, rowsOf(response), "prefix $n min $min")
                assertEquals(n, response.requestedN)
                assertEquals(10, response.committedFrontier)
                assertEquals(min, response.minObservations)
                assertEquals(expected.size, response.rowsRetained)
                assertEquals(expected.sumOf { it.third }, response.observationsRetained)
                assertEquals(evaluated, response.positionsEvaluated)
                assertEquals(expected.map { it.first }.distinct().size, response.positionsRetained)
                assertEquals(evaluated - response.positionsRetained, response.positionsRemoved)
                assertEquals("1000-1200", response.ratingBand)
                assertEquals(HumanMoveCorpusRunStatus.RUNNING, response.runStatus)
                assertEquals(64, response.distributionSha256.length)
            }
        }
        assertFailsWith<IllegalArgumentException> { checkpoint(runId, 11) }
        assertFailsWith<IllegalArgumentException> { checkpoint(runId, 0) }
        assertFailsWith<IllegalArgumentException> { checkpoint(runId, 5, min = 0) }
        assertFailsWith<NoSuchElementException> { checkpoint(UUID.randomUUID(), 1) }
    }

    @Test
    fun `earlier prefixes stay deterministic-equal while the running corpus is extended`() {
        val runId = newRun()
        commitAll(runId, (1..5).map(::syntheticCandidate))
        val before2 = checkpoint(runId, 2, 2)
        val before5 = checkpoint(runId, 5, 2)
        assertEquals(rowsOf(before5), rowsOf(checkpoint(runId, 5, 2)))
        assertEquals(before5.distributionSha256, checkpoint(runId, 5, 2).distributionSha256)
        assertFailsWith<IllegalArgumentException> { checkpoint(runId, 6) }

        commitAll(runId, (6..9).map(::syntheticCandidate))

        listOf(before2 to checkpoint(runId, 2, 2), before5 to checkpoint(runId, 5, 2)).forEach { (old, new) ->
            assertEquals(rowsOf(old), rowsOf(new))
            assertEquals(old.distributionSha256, new.distributionSha256)
            assertEquals(old.positionsEvaluated, new.positionsEvaluated)
            assertEquals(old.observationsRetained, new.observationsRetained)
            assertEquals(9, new.committedFrontier)
        }
        assertNotEquals(before5.distributionSha256, checkpoint(runId, 9, 2).distributionSha256)
    }

    @Test
    fun `checkpoint calculation is read-only and never invokes destructive finalization`() {
        val runId = newRun()
        commitAll(runId, (1..6).map(::syntheticCandidate))
        val legacyPosition = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO position (id, hash, fen) VALUES (?, 'legacy-pos', 'legacy-fen')", legacyPosition)
        jdbcTemplate.update(
            "INSERT INTO human_move_distribution (id, position_id, rating_band, move_played, observation_count) " +
                "VALUES (?, ?, '1000-1200', 'e4', 1)",
            UUID.randomUUID(),
            legacyPosition,
        )
        jdbcTemplate.update("INSERT INTO human_move_bfs_seen_game (game_url) VALUES ('http://legacy-seen')")
        val before = snapshot()

        repeat(3) { listOf(1, 3, 6).forEach { n -> checkpoint(runId, n, 5) } }

        assertEquals(before, snapshot())
        verify(finalizationService, never()).finalize(any())
    }

    @Test
    fun `retention matches the legacy minObservations finalization rule on the same aggregate`() {
        val runId = newRun()
        val candidates = (1..8).map(::syntheticCandidate)
        commitAll(runId, candidates)
        val min = 3
        val response = checkpoint(runId, 8, min)

        val legacyBand = "2200+"
        val positionIds =
            jdbcTemplate.queryForList("SELECT id, hash FROM position").associate {
                it["hash"].toString() to ((it["id"] ?: error("missing id")) as UUID)
            }
        expectedRows(candidates, 1).forEach { (hash, move, observationCount) ->
            jdbcTemplate.update(
                "INSERT INTO human_move_distribution (id, position_id, rating_band, move_played, observation_count) " +
                    "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                positionIds.getValue(hash),
                legacyBand,
                move,
                observationCount,
            )
        }
        val finalized = finalizationService.finalize(HumanMoveFinalizeRequest(ratingBand = legacyBand, minObservations = min))
        val legacyRetained =
            jdbcTemplate.queryForList(
                "SELECT p.hash, d.move_played, d.observation_count FROM human_move_distribution d " +
                    "JOIN position p ON p.id = d.position_id WHERE d.rating_band = ? ORDER BY p.hash, d.move_played",
                legacyBand,
            ).map {
                Triple(
                    it["hash"].toString(),
                    it["move_played"].toString(),
                    ((it["observation_count"] ?: error("missing count")) as Number).toInt(),
                )
            }

        assertEquals(legacyRetained, rowsOf(response))
        assertEquals(finalized.positionsEvaluated, response.positionsEvaluated)
        assertEquals(finalized.positionsRemoved, response.positionsRemoved)
        assertEquals(finalized.positionsRetained, response.positionsRetained)
    }

    @Test
    fun `checkpoint fails closed on an ordinal gap spanning the prefix`() {
        val runId = newRun()
        commitAll(runId, (1..3).map(::syntheticCandidate))
        withTriggersDisabled {
            val game = gameId(runId, 2)
            jdbcTemplate.update("DELETE FROM human_move_corpus_observation WHERE game_id = ?", game)
            jdbcTemplate.update("DELETE FROM human_move_corpus_game WHERE id = ?", game)
        }

        assertEquals(1, checkpoint(runId, 1).requestedN)
        assertFailsWith<HumanMoveCorpusIntegrityException> { checkpoint(runId, 2) }
        assertFailsWith<HumanMoveCorpusIntegrityException> { checkpoint(runId, 3) }
    }

    @Test
    fun `checkpoint fails closed on extra, missing, or reduced per-game observations`() {
        val firstObservationOf = "(SELECT id FROM human_move_corpus_observation WHERE game_id = ? ORDER BY id LIMIT 1)"
        val corruptions: List<(UUID, UUID) -> Unit> =
            listOf(
                { game, position -> jdbcTemplate.update(observationInsert, UUID.randomUUID(), game, position) },
                { game, _ -> jdbcTemplate.update("DELETE FROM human_move_corpus_observation WHERE id = $firstObservationOf", game) },
                { game, _ ->
                    jdbcTemplate.update(
                        "UPDATE human_move_corpus_observation SET observation_count = observation_count + 1 " +
                            "WHERE id = $firstObservationOf",
                        game,
                    )
                },
            )
        corruptions.forEachIndexed { index, corrupt ->
            resetDatabase()
            val runId = newRun()
            commitAll(runId, (1..3).map(::syntheticCandidate))
            val position = jdbcTemplate.queryForObject("SELECT id FROM position WHERE hash = 'pos-shared'", UUID::class.java)!!
            withTriggersDisabled { corrupt(gameId(runId, 2), position) }

            assertEquals(1, checkpoint(runId, 1).requestedN, "corruption $index lies outside prefix 1")
            assertFailsWith<HumanMoveCorpusIntegrityException>("corruption $index") { checkpoint(runId, 3) }
        }
    }

    @Test
    fun `checkpoint fails closed when a shared position hash diverges from the committed snapshot`() {
        val runId = newRun()
        commitAll(runId, (1..3).map(::syntheticCandidate))

        jdbcTemplate.update("UPDATE position SET hash = 'mutated' WHERE hash = 'pos-shared'")

        assertFailsWith<HumanMoveCorpusIntegrityException> { checkpoint(runId, 1) }
    }

    @Test
    fun `corpus ignores and never writes the legacy global seen-game and distribution tables`() {
        stubThreeGames()
        jdbcTemplate.update("INSERT INTO human_move_bfs_seen_game (game_url) VALUES ('http://b')")

        val corpus = corpusService.runCorpus(runRequest(maxQualifyingGames = null, maxDepth = 0))

        assertEquals(HumanMoveCorpusRunStatus.COMPLETED, corpus.status)
        assertEquals(3, corpus.committedFrontier, "globally seen game is still a corpus member")
        assertEquals(1L, count("SELECT count(*) FROM human_move_bfs_seen_game"))
        assertEquals(0L, count("SELECT count(*) FROM human_move_distribution"))

        val legacy =
            legacyBfsService.runBfs(HumanMoveBfsRequest(ratingBand = "1000-1200", seedPlayers = listOf("p1"), maxDepth = 0))

        assertEquals(2, legacy.qualifyingGames, "legacy still skips its own globally seen game")
        assertEquals(
            setOf("http://a", "http://b", "http://c"),
            jdbcTemplate.queryForList("SELECT game_url FROM human_move_bfs_seen_game", String::class.java).toSet(),
        )
        assertEquals(3, frontier(corpus.runId), "legacy ingestion does not touch the corpus")
    }

    private fun submitOrdinalWorkers(
        pool: java.util.concurrent.ExecutorService,
        run: UUID,
        rating: Int,
        threads: Int,
        perThread: Int,
        errors: MutableList<Throwable>,
    ) = (0 until threads).map { t ->
        pool.submit {
            try {
                for (i in 0 until perThread) {
                    gameWriter.commitGame(run, syntheticCandidate(t * perThread + i, rating))
                }
            } catch (e: Throwable) {
                errors += e
            }
        }
    }

    private fun waitForOverlappedWriter(
        holder: java.sql.Connection,
        deadlineNanos: Long,
    ): Boolean {
        while (System.nanoTime() < deadlineNanos) {
            val overlapped = checkOverlappedAdvisory(holder)
            if (overlapped) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun checkOverlappedAdvisory(holder: java.sql.Connection): Boolean =
        holder.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity gated " +
                    "JOIN pg_stat_activity waiter ON gated.pid = ANY(pg_blocking_pids(waiter.pid)) " +
                    "WHERE gated.wait_event = 'advisory' AND gated.pid <> pg_backend_pid() " +
                    "AND waiter.pid <> pg_backend_pid())",
            ).use { rs -> rs.next() && rs.getBoolean(1) }
        }

    private fun executeAdvisory(
        holder: java.sql.Connection,
        sql: String,
    ) {
        holder.createStatement().use { it.execute(sql) }
    }

    private fun executeGatedOrdinalConcurrency(
        gate: Long,
        pool: java.util.concurrent.ExecutorService,
        runA: UUID,
        runB: UUID,
        threads: Int,
        perThread: Int,
        errors: MutableList<Throwable>,
    ) {
        dataSource.connection.use { holder ->
            executeAdvisory(holder, "SELECT pg_advisory_lock($gate)")
            try {
                submitOrdinalWorkers(pool, runA, 1100, threads, perThread, errors)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                val overlapped = waitForOverlappedWriter(holder, deadline)
                assertTrue(overlapped, "a second run-A writer must block behind the in-flight run-A game")
                submitOrdinalWorkers(pool, runB, 1500, threads, perThread, errors).forEach { it.get(60, TimeUnit.SECONDS) }
                assertEquals(threads * perThread, frontier(runB), "run B is not serialized behind run A")
            } finally {
                executeAdvisory(holder, "SELECT pg_advisory_unlock($gate)")
            }
        }
    }
}
