package com.chessecho.integration.migration

import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #457, tasks T1/T3. The baseline must define `app_user_id` personal scoping and the
 * approved `NULLS NOT DISTINCT` source-linked uniqueness invariant.
 *
 * This lives in the Flyway-backed PostgreSQL migration suite because the ordinary test profile
 * uses H2 with `ddl-auto: create-drop`, which can represent neither the partial predicate nor
 * `NULLS NOT DISTINCT`.
 */
class AccountDataBoundaryBaselineSchemaTest : PostgresMigrationTestFixture() {
    @Test
    fun `app_user_id is nullable on every personally scoped table`() {
        listOf("puzzle_scheduling_event", "training_attempt", "async_job").forEach { table ->
            val column =
                query(
                    """
                    SELECT is_nullable, data_type
                    FROM information_schema.columns
                    WHERE table_name = '$table' AND column_name = 'app_user_id'
                    """.trimIndent(),
                ).singleOrNull()

            assertNotNull(column, "$table is missing app_user_id")
            assertEquals("YES", column["is_nullable"], "$table.app_user_id must be nullable")
            assertEquals("uuid", column["data_type"], "$table.app_user_id must be uuid")
        }
    }

    @Test
    fun `app_user_id references app_user on every personally scoped table`() {
        listOf("puzzle_scheduling_event", "training_attempt", "async_job").forEach { table ->
            val foreignKeys =
                query(
                    """
                    SELECT pg_get_constraintdef(oid) AS definition, confdeltype
                    FROM pg_constraint
                    WHERE contype = 'f' AND conrelid = '$table'::regclass
                    """.trimIndent(),
                )

            val appUserFk =
                foreignKeys.singleOrNull {
                    (it["definition"] as String).contains("(app_user_id)")
                }
            assertNotNull(appUserFk, "$table must have exactly one app_user_id foreign key")
            assertTrue(
                (appUserFk["definition"] as String).contains("REFERENCES app_user(id)"),
                "$table.app_user_id must reference app_user(id); was ${appUserFk["definition"]}",
            )

            // 'a' is NO ACTION. The approved policy is deliberately non-destructive: CASCADE would
            // erase personal history and job audit rows, and SET NULL would reclassify an
            // attributed row as a guest row, colliding under uk_puzzle_event_source_type.
            assertEquals(
                "a",
                appUserFk["confdeltype"]?.toString(),
                "$table.app_user_id must use ON DELETE NO ACTION",
            )
        }
    }

    @Test
    fun `source-linked uniqueness is user scoped and treats nulls as not distinct`() {
        val definition =
            query(
                """
                SELECT indexdef
                FROM pg_indexes
                WHERE tablename = 'puzzle_scheduling_event' AND indexname = 'uk_puzzle_event_source_type'
                """.trimIndent(),
            ).singleOrNull()?.get("indexdef") as String?

        assertNotNull(definition, "uk_puzzle_event_source_type is missing")
        assertTrue(
            definition.contains("(app_user_id, position_occurrence_id, event_type)"),
            "index must be keyed by the personal namespace; was: $definition",
        )
        assertTrue(
            definition.contains("NULLS NOT DISTINCT"),
            "index must use NULLS NOT DISTINCT to preserve guest idempotency; was: $definition",
        )
        assertTrue(
            definition.contains("WHERE (position_occurrence_id IS NOT NULL)"),
            "index must remain partial to source-linked events; was: $definition",
        )
    }

    @Test
    fun `two authenticated users may independently claim the same occurrence and event type`() {
        val fixture = seedFixture("independent-users")

        insertEvent(fixture, appUserId = fixture.userA, occurrenceId = fixture.occurrence)
        insertEvent(fixture, appUserId = fixture.userB, occurrenceId = fixture.occurrence)

        assertEquals(2, countEvents(fixture.occurrence))
    }

    @Test
    fun `a repeated claim by the same authenticated user is rejected`() {
        val fixture = seedFixture("same-user-replay")

        insertEvent(fixture, appUserId = fixture.userA, occurrenceId = fixture.occurrence)

        assertFailsWith<SQLException> {
            insertEvent(fixture, appUserId = fixture.userA, occurrenceId = fixture.occurrence)
        }
    }

    @Test
    fun `a repeated guest claim remains rejected`() {
        val fixture = seedFixture("guest-replay")

        insertEvent(fixture, appUserId = null, occurrenceId = fixture.occurrence)

        assertFailsWith<SQLException> {
            insertEvent(fixture, appUserId = null, occurrenceId = fixture.occurrence)
        }
    }

    @Test
    fun `events without a source occurrence stay outside the uniqueness index`() {
        val fixture = seedFixture("training-history")

        insertEvent(fixture, appUserId = fixture.userA, occurrenceId = null, eventType = "SOLVED")
        insertEvent(fixture, appUserId = fixture.userA, occurrenceId = null, eventType = "SOLVED")
        insertEvent(fixture, appUserId = null, occurrenceId = null, eventType = "SOLVED")
        insertEvent(fixture, appUserId = null, occurrenceId = null, eventType = "SOLVED")

        val count =
            query(
                """
                SELECT count(*) AS total
                FROM puzzle_scheduling_event
                WHERE position_id = '${fixture.position}' AND position_occurrence_id IS NULL
                """.trimIndent(),
            ).single()["total"] as Long

        assertEquals(4L, count)
    }

    private data class Fixture(
        val userA: String,
        val userB: String,
        val account: String,
        val position: String,
        val occurrence: String,
    )

    private fun seedFixture(tag: String): Fixture {
        val userA = insertReturningId("INSERT INTO app_user (email) VALUES ('$tag-a@example.com') RETURNING id")
        val userB = insertReturningId("INSERT INTO app_user (email) VALUES ('$tag-b@example.com') RETURNING id")
        val account =
            insertReturningId(
                "INSERT INTO chess_account (platform, username) VALUES ('CHESS_COM', '$tag') RETURNING id",
            )
        val position =
            insertReturningId(
                "INSERT INTO position (hash, fen) VALUES ('$tag-hash', '$tag-fen') RETURNING id",
            )
        val game =
            insertReturningId(
                """
                INSERT INTO game (chess_account_id, platform_game_id, pgn)
                VALUES ('$account', '$tag-game', '1. e4')
                RETURNING id
                """.trimIndent(),
            )
        val occurrence =
            insertReturningId(
                """
                INSERT INTO position_occurrence
                    (game_id, position_id, chess_account_id, ply_number, move_played, player_color)
                VALUES ('$game', '$position', '$account', 1, 'e4', 'WHITE')
                RETURNING id
                """.trimIndent(),
            )

        return Fixture(userA, userB, account, position, occurrence)
    }

    private fun insertEvent(
        fixture: Fixture,
        appUserId: String?,
        occurrenceId: String?,
        eventType: String = "GAME_MISTAKE",
    ) {
        val user = appUserId?.let { "'$it'" } ?: "NULL"
        val occurrence = occurrenceId?.let { "'$it'" } ?: "NULL"
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                INSERT INTO puzzle_scheduling_event
                    (app_user_id, chess_account_id, position_id, player_color, event_type, position_occurrence_id)
                VALUES ($user, '${fixture.account}', '${fixture.position}', 'WHITE', '$eventType', $occurrence)
                """.trimIndent(),
            )
        }
    }

    private fun countEvents(occurrenceId: String): Int =
        (
            query(
                "SELECT count(*) AS total FROM puzzle_scheduling_event WHERE position_occurrence_id = '$occurrenceId'",
            ).single()["total"] as Long
        ).toInt()

    private fun insertReturningId(sql: String): String =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                result.next()
                result.getObject(1).toString()
            }
        }
}
