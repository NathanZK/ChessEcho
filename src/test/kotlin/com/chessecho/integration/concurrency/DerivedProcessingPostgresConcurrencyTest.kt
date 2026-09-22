package com.chessecho.integration.concurrency

import com.chessecho.integration.migration.PostgresMigrationTestFixture
import com.chessecho.repository.ArchiveDerivedProcessingRepository
import com.chessecho.repository.PositionOccurrenceRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.jpa.repository.Query
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Concurrency evidence against a real PostgreSQL instance running the real
 * baseline migration.
 *
 * The H2-backed Spring integration tests cannot produce this evidence: H2 does
 * not implement `ON CONFLICT ... DO NOTHING`, so the production insert path is
 * not even exercised there, and its locking is not the locking that decides
 * these races. Each statement below is read straight off the production
 * repository's `@Query` annotation, so this test cannot drift away from the
 * statement the application actually issues.
 */
class DerivedProcessingPostgresConcurrencyTest : PostgresMigrationTestFixture() {
    private lateinit var accountId: UUID
    private lateinit var gameId: UUID
    private lateinit var positionId: UUID
    private lateinit var archiveId: UUID

    @BeforeEach
    fun seedFixtures() {
        connection.createStatement().use { statement ->
            statement.execute("DELETE FROM archive_derived_processing")
            statement.execute("DELETE FROM position_occurrence")
            statement.execute("DELETE FROM game")
            statement.execute("DELETE FROM position")
            statement.execute("DELETE FROM imported_archive")
            statement.execute("DELETE FROM chess_account")
        }
        accountId = UUID.randomUUID()
        gameId = UUID.randomUUID()
        positionId = UUID.randomUUID()
        archiveId = UUID.randomUUID()
        execute(
            "INSERT INTO chess_account (id, platform, username) VALUES (?, 'CHESS_COM', 'concurrency-fixture')",
            accountId,
        )
        execute(
            """
            INSERT INTO imported_archive (id, chess_account_id, archive_url, year_month, game_count)
            VALUES (?, ?, 'https://api.chess.com/pub/player/concurrency-fixture/games/2024/01', '2024-01', 1)
            """.trimIndent(),
            archiveId,
            accountId,
        )
        execute(
            """
            INSERT INTO game (id, chess_account_id, imported_archive_id, platform_game_id, pgn)
            VALUES (?, ?, ?, 'concurrency-fixture-game', '1. e4 e5')
            """.trimIndent(),
            gameId,
            accountId,
            archiveId,
        )
        execute(
            "INSERT INTO position (id, hash, fen) VALUES (?, 'concurrency-fixture-hash', 'fen')",
            positionId,
        )
    }

    @Test
    fun `the database rejects a duplicate occurrence identity outright`() {
        insertOccurrenceWithoutConflictHandling(UUID.randomUUID())

        val failure =
            runCatching { insertOccurrenceWithoutConflictHandling(UUID.randomUUID()) }
                .exceptionOrNull()

        assertTrue(
            failure is SQLException && failure.message.orEmpty().contains("uk_position_occurrence_identity"),
            "occurrence identity must be enforced by the database, not only by application filtering: $failure",
        )
        assertEquals(1, countOccurrences())
    }

    @Test
    fun `concurrent replays of the same occurrence identity converge on one row`() {
        val workers = 8
        val barrier = CyclicBarrier(workers)
        val sql = nativeStatementOf(PositionOccurrenceRepository::class.java, "insertIfAbsent")
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val results =
                (1..workers).map {
                    pool.submit<Result<Int>> {
                        runCatching {
                            newConnection().use { worker ->
                                worker.prepareStatement(sql).use { statement ->
                                    bindOccurrence(statement, UUID.randomUUID())
                                    barrier.await(10, TimeUnit.SECONDS)
                                    statement.executeUpdate()
                                }
                            }
                        }
                    }
                }.map { it.get(30, TimeUnit.SECONDS) }

            assertTrue(
                results.all { it.isSuccess },
                "conflict-safe insertion must not surface a unique violation: ${results.mapNotNull { it.exceptionOrNull() }}",
            )
            assertEquals(
                1,
                results.sumOf { it.getOrThrow() },
                "exactly one concurrent writer may insert the logical occurrence",
            )
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, countOccurrences(), "concurrent replay must leave exactly one occurrence row")
    }

    @Test
    fun `a blind first derived claim insert fails, which is why the production path cannot simply retry it`() {
        insertProcessingWithoutConflictHandling(UUID.randomUUID())

        val failure =
            runCatching { insertProcessingWithoutConflictHandling(UUID.randomUUID()) }
                .exceptionOrNull()

        assertTrue(
            failure is SQLException,
            "a second blind first-claim insert must violate uk_archive_derived_processing_archive: $failure",
        )
        assertEquals(1, countProcessing())
    }

    @Test
    fun `the losing first derived claim waits for the winner and then finds the winner's row`() {
        val sql = nativeStatementOf(ArchiveDerivedProcessingRepository::class.java, "insertPendingIfAbsent")
        val winnerInserted = CountDownLatch(1)
        val loserAttempted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val winner =
                pool.submit<Int> {
                    newConnection().use { worker ->
                        worker.autoCommit = false
                        val affected =
                            worker.prepareStatement(sql).use { statement ->
                                bindProcessing(statement, UUID.randomUUID())
                                statement.executeUpdate()
                            }
                        winnerInserted.countDown()
                        // Hold the uncommitted row so the loser provably contends
                        // for the same unique key instead of racing past it.
                        assertTrue(loserAttempted.await(10, TimeUnit.SECONDS))
                        Thread.sleep(200)
                        worker.commit()
                        affected
                    }
                }
            val loser =
                pool.submit<Int> {
                    assertTrue(winnerInserted.await(10, TimeUnit.SECONDS))
                    newConnection().use { worker ->
                        worker.prepareStatement(sql).use { statement ->
                            bindProcessing(statement, UUID.randomUUID())
                            loserAttempted.countDown()
                            statement.executeUpdate()
                        }
                    }
                }

            assertEquals(1, winner.get(30, TimeUnit.SECONDS), "the winner inserts the row")
            assertEquals(
                0,
                loser.get(30, TimeUnit.SECONDS),
                "the loser must become a no-op instead of failing on the unique key",
            )
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, countProcessing(), "a contended first claim must leave exactly one processing record")
        assertFalse(statusOfProcessing().isEmpty())
        assertEquals("PENDING", statusOfProcessing(), "the surviving record must still be claimable")
    }

    private fun bindOccurrence(
        statement: java.sql.PreparedStatement,
        id: UUID,
    ) {
        statement.setObject(1, id)
        statement.setObject(2, gameId)
        statement.setObject(3, positionId)
        statement.setObject(4, accountId)
        statement.setInt(5, 7)
        statement.setString(6, "Nf3")
        statement.setString(7, "WHITE")
        statement.setObject(8, java.sql.Timestamp.from(Instant.now()))
    }

    private fun bindProcessing(
        statement: java.sql.PreparedStatement,
        id: UUID,
    ) {
        statement.setObject(1, id)
        statement.setObject(2, archiveId)
        statement.setObject(3, java.sql.Timestamp.from(Instant.now()))
    }

    private fun insertOccurrenceWithoutConflictHandling(id: UUID) {
        execute(
            """
            INSERT INTO position_occurrence
              (id, game_id, position_id, chess_account_id, ply_number, move_played, player_color)
            VALUES (?, ?, ?, ?, 7, 'Nf3', 'WHITE')
            """.trimIndent(),
            id,
            gameId,
            positionId,
            accountId,
        )
    }

    private fun insertProcessingWithoutConflictHandling(id: UUID) {
        execute(
            "INSERT INTO archive_derived_processing (id, imported_archive_id, status) VALUES (?, ?, 'PENDING')",
            id,
            archiveId,
        )
    }

    private fun countOccurrences(): Int = (query("SELECT count(*) AS c FROM position_occurrence").single()["c"] as Number).toInt()

    private fun countProcessing(): Int = (query("SELECT count(*) AS c FROM archive_derived_processing").single()["c"] as Number).toInt()

    private fun statusOfProcessing(): String = query("SELECT status FROM archive_derived_processing").single()["status"].toString()

    private fun execute(
        sql: String,
        vararg parameters: Any,
    ) {
        newConnection().use { worker ->
            worker.prepareStatement(sql).use { statement ->
                parameters.forEachIndexed { index, parameter -> statement.setObject(index + 1, parameter) }
                statement.executeUpdate()
            }
        }
    }

    private fun newConnection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    /**
     * Reads the production native statement off the repository method so this
     * test can never assert against a hand-copied variant of it, rewriting the
     * named parameters to positional ones in declaration order.
     */
    private fun nativeStatementOf(
        repository: Class<*>,
        method: String,
    ): String {
        val target = repository.declaredMethods.single { it.name == method }
        val query =
            requireNotNull(target.getAnnotation(Query::class.java)) {
                "$method must declare its statement with @Query"
            }
        require(query.nativeQuery) { "$method must be a native statement" }
        var sql = query.value
        target.parameters.forEach { parameter ->
            val name =
                requireNotNull(parameter.getAnnotation(org.springframework.data.repository.query.Param::class.java)) {
                    "$method parameters must be named"
                }.value
            sql = sql.replace(":$name", "?")
        }
        return sql
    }
}
