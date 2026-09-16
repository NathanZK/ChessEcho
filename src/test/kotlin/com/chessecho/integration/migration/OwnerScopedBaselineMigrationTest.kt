package com.chessecho.integration.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OwnerScopedBaselineMigrationTest : PostgresMigrationTestFixture() {
    @Test
    fun `applies the single clean PostgreSQL baseline schema and rejects legacy V3 quarantine artifacts`() {
        applyCurrentMigrations()

        val requiredTables =
            setOf(
                "app_user",
                "auth_identity",
                "auth_session",
                "chess_account",
                "game",
                "position",
                "position_occurrence",
                "engine_analysis",
                "engine_move_evaluation",
                "user_position_weakness",
                "user_position_stats",
                "imported_archive",
                "human_move_distribution",
                "human_move_bfs_seen_game",
                "async_job",
            )
        val tables =
            query(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                    'app_user', 'auth_identity', 'auth_session', 'chess_account',
                    'game', 'position', 'position_occurrence', 'engine_analysis',
                    'engine_move_evaluation', 'user_position_weakness', 'user_position_stats',
                    'imported_archive', 'human_move_distribution', 'human_move_bfs_seen_game',
                    'async_job'
                  )
                """.trimIndent(),
            ).map { it["table_name"].toString() }.toSet()
        assertEquals(requiredTables, tables)

        val nullableUserId =
            query(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name = 'chess_account' AND column_name = 'user_id'",
            )
        assertEquals(1, nullableUserId.size)
        assertEquals("YES", nullableUserId.first()["is_nullable"])

        assertColumnNullable("app_user", "email", true)
        assertIndex("chess_account", "platform", expression = "lower")
        assertForeignKey("chess_account", "user_id", "app_user", "id")
        assertIndex("auth_identity", "app_user_id")
        assertIndex("auth_identity", "issuer, subject", unique = true)
        assertForeignKey("auth_identity", "app_user_id", "app_user", "id")
        assertIndex("auth_session", "app_user_id")
        assertIndex("auth_session", "absolute_expires_at")
        assertIndex("auth_session", "token_hash", unique = true)
        assertForeignKey("auth_session", "app_user_id", "app_user", "id")
        assertColumnLength("auth_session", "token_hash", 64)
        val sessionConstraints = constraintDefinitions("auth_session")
        assertConstraint(sessionConstraints, "token_hash", "64")
        assertConstraint(sessionConstraints, "idle_expires_at", "created_at")
        assertConstraint(sessionConstraints, "absolute_expires_at", "idle_expires_at")
        assertConstraint(sessionConstraints, "revoked_at", "created_at")

        val jobColumns =
            query(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'async_job'
                  AND column_name IN (
                    'chess_account_id', 'from_date', 'to_date', 'time_controls_csv',
                    'player_color', 'configuration_state'
                  )
                """.trimIndent(),
            )
        assertEquals(
            setOf("chess_account_id", "from_date", "to_date", "time_controls_csv", "player_color", "configuration_state"),
            jobColumns.map { it["column_name"].toString() }.toSet(),
        )

        val ownerScopedTables =
            setOf(
                "game",
                "position_occurrence",
                "user_position_weakness",
                "user_position_stats",
                "imported_archive",
            )
        ownerScopedTables.forEach { table ->
            assertColumnNullable(table, "chess_account_id", false)
            assertForeignKey(table, "chess_account_id", "chess_account", "id")
            assertIndex(table, "chess_account_id")
        }
        assertColumnNullable("async_job", "chess_account_id", true)
        assertForeignKey("async_job", "chess_account_id", "chess_account", "id")
        assertIndex("async_job", "chess_account_id")

        listOf(
            "app_user" to "created_at",
            "auth_identity" to "created_at",
            "auth_identity" to "last_seen_at",
            "auth_session" to "created_at",
            "auth_session" to "last_seen_at",
            "chess_account" to "created_at",
            "game" to "created_at",
            "position" to "created_at",
            "position_occurrence" to "created_at",
            "engine_analysis" to "analyzed_at",
            "user_position_weakness" to "updated_at",
            "user_position_stats" to "updated_at",
            "imported_archive" to "imported_at",
            "human_move_bfs_seen_game" to "seen_at",
            "async_job" to "created_at",
            "async_job" to "updated_at",
        ).forEach { (table, column) -> assertColumnDefault(table, column) }

        val constraints = constraintDefinitions("async_job")
        assertConstraint(constraints, "status", "queued", "processing", "completed", "failed")
        assertConstraint(constraints, "platform", "chess_com")
        assertConstraint(constraints, "player_color", "white", "black", "both")
        assertConstraint(constraints, "from_date", "to_date")
        assertConstraint(constraints, "time_controls_csv", "blitz", "rapid", "bullet", "classical")
        assertConstraint(constraints, "configuration_state", "ready")
        assertConstraint(constraints, "chess_account_id", "ready")

        assertIndex(
            "async_job",
            "chess_account_id",
            unique = true,
            predicate = "queued",
            alsoPredicate = "processing",
        )

        assertEquals(
            0,
            query(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'migration_account_quarantine'",
            ).size,
        )
        assertEquals(
            0,
            query(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'migration_row_quarantine'",
            ).size,
        )
        assertEquals(
            0,
            query(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'migration_legacy_account'",
            ).size,
        )
    }

    @Test
    fun `fails closed when a READY async job is incomplete or uses invalid owner-scoped configuration`() {
        applyCurrentMigrations()

        val invalidRows =
            listOf(
                """
                INSERT INTO async_job
                  (id, username, platform, status, chess_account_id, time_controls_csv, player_color, configuration_state)
                VALUES (gen_random_uuid(), 'missing-account', 'CHESS_COM', 'QUEUED', NULL, 'BLITZ', 'WHITE', 'READY')
                """,
                """
                INSERT INTO async_job
                  (id, username, platform, status, chess_account_id, time_controls_csv, player_color, configuration_state)
                VALUES (gen_random_uuid(), 'bad-platform', 'UNKNOWN', 'QUEUED', NULL, 'BLITZ', 'WHITE', 'DRAFT')
                """,
                """
                INSERT INTO async_job
                  (id, username, platform, status, chess_account_id, from_date, to_date, time_controls_csv, player_color, configuration_state)
                VALUES (gen_random_uuid(), 'bad-range', 'CHESS_COM', 'QUEUED', NULL, '2026-08', '2026-01', 'BLITZ', 'WHITE', 'DRAFT')
                """,
            )

        invalidRows.forEach { sql ->
            assertTrue(
                runCatching { connection.createStatement().use { it.executeUpdate(sql) } }.isFailure,
                "invalid async_job row must be rejected: $sql",
            )
        }
    }

    @Test
    fun `deleting an account retains its READY job as unresolved and accountless`() {
        applyCurrentMigrations()

        val userId = "00000000-0000-0000-0000-000000000252"
        val accountId = "00000000-0000-0000-0000-000000000253"
        val jobId = "00000000-0000-0000-0000-000000000254"
        connection.createStatement().use { statement ->
            statement.executeUpdate("INSERT INTO app_user (id) VALUES ('$userId')")
            statement.executeUpdate(
                """
                INSERT INTO chess_account (id, user_id, platform, username)
                VALUES ('$accountId', '$userId', 'CHESS_COM', 'deletion-test')
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                INSERT INTO async_job
                  (id, chess_account_id, username, platform, status, time_controls_csv, player_color, configuration_state)
                VALUES
                  ('$jobId', '$accountId', 'deletion-test', 'CHESS_COM', 'QUEUED', 'BLITZ', 'WHITE', 'READY')
                """.trimIndent(),
            )
            statement.executeUpdate("DELETE FROM chess_account WHERE id = '$accountId'")
        }

        val retained =
            query(
                """
                SELECT chess_account_id, configuration_state
                FROM async_job
                WHERE id = '$jobId'
                """.trimIndent(),
            ).single()
        assertEquals(null, retained["chess_account_id"])
        assertEquals("UNRESOLVED", retained["configuration_state"])
    }

    private fun assertColumnNullable(
        table: String,
        column: String,
        nullable: Boolean,
    ) {
        val row =
            query(
                """
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = '$table' AND column_name = '$column'
                """.trimIndent(),
            ).singleOrNull()
        assertEquals(nullable, row?.get("is_nullable") == "YES", "$table.$column nullability")
    }

    private fun assertColumnDefault(
        table: String,
        column: String,
    ) {
        val row =
            query(
                """
                SELECT column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = '$table' AND column_name = '$column'
                """.trimIndent(),
            ).singleOrNull()
        assertTrue(
            row?.get("column_default")?.toString()?.contains("now", ignoreCase = true) == true ||
                row?.get("column_default")?.toString()?.contains("current_timestamp", ignoreCase = true) == true,
            "$table.$column must have a deliberate current-time default",
        )
    }

    private fun assertColumnLength(
        table: String,
        column: String,
        length: Int,
    ) {
        val row =
            query(
                """
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = '$table' AND column_name = '$column'
                """.trimIndent(),
            ).singleOrNull()
        assertEquals(length, row?.get("character_maximum_length"), "$table.$column length")
    }

    private fun assertForeignKey(
        table: String,
        column: String,
        referencedTable: String,
        referencedColumn: String,
    ) {
        val matches =
            query(
                """
                SELECT 1
                FROM information_schema.referential_constraints rc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_name = rc.constraint_name
                 AND kcu.constraint_schema = rc.constraint_schema
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_name = rc.unique_constraint_name
                 AND ccu.constraint_schema = rc.unique_constraint_schema
                WHERE kcu.table_schema = 'public'
                  AND kcu.table_name = '$table'
                  AND kcu.column_name = '$column'
                  AND ccu.table_name = '$referencedTable'
                  AND ccu.column_name = '$referencedColumn'
                """.trimIndent(),
            )
        assertFalse(matches.isEmpty(), "$table.$column must reference $referencedTable.$referencedColumn")
    }

    private fun constraintDefinitions(table: String): List<String> =
        query(
            """
            SELECT pg_get_constraintdef(c.oid) AS definition
            FROM pg_constraint c
            JOIN pg_class r ON r.oid = c.conrelid
            JOIN pg_namespace n ON n.oid = r.relnamespace
            WHERE n.nspname = 'public' AND r.relname = '$table' AND c.contype = 'c'
            """.trimIndent(),
        ).map { it["definition"].toString().lowercase() }

    private fun assertConstraint(
        constraints: List<String>,
        vararg fragments: String,
    ) {
        assertTrue(
            constraints.any { definition -> fragments.all { fragment -> definition.contains(fragment.lowercase()) } },
            "expected a check constraint containing ${fragments.toList()}, got $constraints",
        )
    }

    private fun assertIndex(
        table: String,
        columns: String,
        unique: Boolean = false,
        predicate: String? = null,
        alsoPredicate: String? = null,
        expression: String? = null,
    ) {
        val indexes =
            query(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = '$table'",
            ).map { it["indexdef"].toString().lowercase() }
        val matching =
            indexes.firstOrNull { definition ->
                definition.contains(columns.lowercase()) &&
                    (!unique || definition.contains("create unique index")) &&
                    (predicate == null || definition.contains(predicate.lowercase())) &&
                    (alsoPredicate == null || definition.contains(alsoPredicate.lowercase())) &&
                    (expression == null || definition.contains(expression.lowercase()))
            }
        assertTrue(matching != null, "expected index on $table($columns), got $indexes")
    }
}
