package com.chessecho.integration.migration

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PuzzleAttemptCountBaselineTest : PostgresMigrationTestFixture() {
    @Test
    fun `fresh baseline has identified submission fields and replay uniqueness`() {
        val columns =
            query(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'puzzle_scheduling_event'
                  AND column_name IN ('submission_id', 'submitted_move')
                """.trimIndent(),
            ).map { it.getValue("column_name") }

        assertTrue(columns.containsAll(listOf("submission_id", "submitted_move")))

        val indexes =
            query(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'puzzle_scheduling_event'
                  AND indexname = 'uk_puzzle_event_user_submission'
                """.trimIndent(),
            )
        assertTrue(indexes.isNotEmpty())
    }
}
