package com.chessecho.integration.migration

import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #423 migration boundary: V1 is undeployed, so every corpus structure
 * lives in V1__baseline.sql; no V2 exists, and legacy tables keep their
 * pre-#423 shape and semantics.
 */
class HumanMoveCorpusBaselineSchemaTest : PostgresMigrationTestFixture() {
    @Test
    fun `migration location contains only the V1 baseline and Flyway applied only version 1`() {
        val scripts =
            PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/*")
                .mapNotNull { it.filename }
                .sorted()
        assertEquals(listOf("V1__baseline.sql"), scripts)

        val applied = query("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
        assertEquals(listOf("1"), applied.map { it["version"] })
    }

    @Test
    fun `fresh V1 contains the corpus run, game, and observation tables with required columns`() {
        assertColumns(
            "human_move_corpus_run",
            setOf(
                "id", "rating_band", "seed_players", "excluded_players", "max_qualifying_games",
                "max_games_per_player", "max_players", "max_depth", "batch_size", "algorithm_version",
                "source_revision", "request_json", "request_sha256", "status", "committed_frontier",
                "rejected_game_count", "archive_fetch_failure_count", "stop_reason", "failure_details",
                "created_at", "updated_at", "finished_at",
            ),
        )
        assertColumns(
            "human_move_corpus_game",
            setOf(
                "id", "run_id", "qualifying_ordinal", "provider_game_id", "traversed_player", "opponent",
                "opponent_side", "opponent_rating", "rules", "time_class", "bfs_depth", "pgn", "pgn_sha256",
                "observation_total", "distinct_move_count", "committed_at",
            ),
        )
        assertColumns(
            "human_move_corpus_observation",
            setOf("id", "game_id", "position_id", "position_hash", "move_played", "observation_count"),
        )
        val bandColumns =
            query(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_name IN ('human_move_corpus_game', 'human_move_corpus_observation')
                  AND column_name = 'rating_band'
                """.trimIndent(),
            )
        assertTrue(bandColumns.isEmpty(), "band derives only from the owning run")
    }

    @Test
    fun `fresh V1 contains immutable lossless occurrence evidence and finalized binding`() {
        assertColumns(
            "human_move_corpus_occurrence",
            setOf(
                "id",
                "source_run_id",
                "qualifying_ordinal",
                "provider_game_id",
                "pre_move_ply",
                "move_played",
                "position_hash",
                "content_digest",
                "covered_prefix",
            ),
        )
        assertColumns(
            "human_move_corpus_occurrence_binding",
            setOf(
                "id",
                "source_run_id",
                "content_digest",
                "covered_prefix",
                "occurrence_digest",
                "occurrence_count",
                "finalized_at",
            ),
        )

        val uniques = uniqueConstraintColumns()
        assertTrue(
            listOf("source_run_id", "qualifying_ordinal", "pre_move_ply") in
                uniques.getValue("human_move_corpus_occurrence"),
            "$uniques",
        )
        assertTrue(
            listOf("source_run_id") in uniques.getValue("human_move_corpus_occurrence_binding"),
            "$uniques",
        )

        val triggers =
            query(
                """
                SELECT event_object_table AS table_name, event_manipulation AS event
                FROM information_schema.triggers
                WHERE event_object_table IN
                    ('human_move_corpus_occurrence', 'human_move_corpus_occurrence_binding')
                """.trimIndent(),
            ).map { it["table_name"] to it["event"] }.toSet()
        listOf(
            "human_move_corpus_occurrence" to "INSERT",
            "human_move_corpus_occurrence" to "UPDATE",
            "human_move_corpus_occurrence" to "DELETE",
            "human_move_corpus_occurrence_binding" to "INSERT",
            "human_move_corpus_occurrence_binding" to "UPDATE",
            "human_move_corpus_occurrence_binding" to "DELETE",
        ).forEach { assertTrue(it in triggers, "missing trigger $it in $triggers") }
    }

    @Test
    fun `corpus constraints enforce run-scoped membership, contiguous-ordinal uniqueness, and restrictive ownership`() {
        val uniques = uniqueConstraintColumns()
        assertTrue(listOf("run_id", "provider_game_id") in uniques.getValue("human_move_corpus_game"), "$uniques")
        assertTrue(listOf("run_id", "qualifying_ordinal") in uniques.getValue("human_move_corpus_game"), "$uniques")
        assertTrue(
            listOf("game_id", "position_hash", "move_played") in uniques.getValue("human_move_corpus_observation"),
            "$uniques",
        )

        val foreignKeys =
            query(
                """
                SELECT c.conrelid::regclass::text AS source, t.relname::text AS target,
                       c.confdeltype::text AS on_delete
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.confrelid
                WHERE c.contype = 'f'
                  AND c.conrelid::regclass::text IN ('human_move_corpus_game', 'human_move_corpus_observation')
                """.trimIndent(),
            ).map { Triple(it["source"], it["target"], it["on_delete"]) }.toSet()
        assertEquals(
            setOf(
                Triple("human_move_corpus_game", "human_move_corpus_run", "r"),
                Triple("human_move_corpus_observation", "human_move_corpus_game", "r"),
                Triple("human_move_corpus_observation", "position", "r"),
            ),
            foreignKeys,
        )

        val checks =
            query(
                """
                SELECT pg_get_constraintdef(c.oid) AS def FROM pg_constraint c
                WHERE c.contype = 'c' AND c.conrelid::regclass::text LIKE 'human_move_corpus_%'
                """.trimIndent(),
            ).joinToString("\n") { it["def"].toString() }
        listOf("committed_frontier", "qualifying_ordinal", "observation_total", "observation_count", "status")
            .forEach { assertTrue(checks.contains(it), "missing CHECK on $it:\n$checks") }
    }

    @Test
    fun `immutability and insert-guard triggers exist on every corpus table`() {
        val triggers =
            query(
                """
                SELECT event_object_table AS table_name, event_manipulation AS event
                FROM information_schema.triggers
                WHERE event_object_table LIKE 'human_move_corpus_%'
                """.trimIndent(),
            ).map { it["table_name"] to it["event"] }.toSet()
        listOf(
            "human_move_corpus_run" to "UPDATE",
            "human_move_corpus_run" to "DELETE",
            "human_move_corpus_game" to "INSERT",
            "human_move_corpus_game" to "UPDATE",
            "human_move_corpus_game" to "DELETE",
            "human_move_corpus_observation" to "INSERT",
            "human_move_corpus_observation" to "UPDATE",
            "human_move_corpus_observation" to "DELETE",
        ).forEach { assertTrue(it in triggers, "missing trigger $it in $triggers") }
    }

    @Test
    fun `legacy human-move and position tables keep their pre-423 shape, constraints, and trigger-free semantics`() {
        assertColumnsExactly("human_move_distribution", setOf("id", "position_id", "rating_band", "move_played", "observation_count"))
        assertColumnsExactly("human_move_bfs_seen_game", setOf("game_url", "seen_at"))
        assertColumnsExactly("position", setOf("id", "hash", "fen", "created_at"))

        val legacyUniques = uniqueConstraintColumns()
        assertEquals(
            setOf(listOf("id"), listOf("position_id", "rating_band", "move_played")),
            legacyUniques.getValue("human_move_distribution").toSet(),
        )
        assertEquals(setOf(listOf("game_url")), legacyUniques.getValue("human_move_bfs_seen_game").toSet())

        val distributionFk =
            query(
                """
                SELECT c.confdeltype::text AS on_delete FROM pg_constraint c
                WHERE c.contype = 'f' AND c.conrelid::regclass::text = 'human_move_distribution'
                """.trimIndent(),
            )
        assertEquals(listOf("c"), distributionFk.map { it["on_delete"] }, "legacy ON DELETE CASCADE unchanged")

        val legacyTriggers =
            query(
                """
                SELECT trigger_name FROM information_schema.triggers
                WHERE event_object_table IN ('human_move_distribution', 'human_move_bfs_seen_game', 'position')
                """.trimIndent(),
            )
        assertTrue(legacyTriggers.isEmpty(), "legacy tables gain no triggers: $legacyTriggers")
    }

    private fun assertColumns(
        table: String,
        required: Set<String>,
    ) {
        val actual = columns(table)
        assertTrue(actual.containsAll(required), "$table missing ${required - actual}")
    }

    private fun assertColumnsExactly(
        table: String,
        expected: Set<String>,
    ) = assertEquals(expected, columns(table), "columns of $table")

    private fun columns(table: String): Set<String> =
        query("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = '$table'")
            .map { it["column_name"].toString() }
            .toSet()

    private fun uniqueConstraintColumns(): Map<String, List<List<String>>> =
        query(
            """
            SELECT c.conrelid::regclass::text AS table_name,
                   array_to_string(ARRAY(
                       SELECT a.attname FROM unnest(c.conkey) WITH ORDINALITY k(attnum, ord)
                       JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
                       ORDER BY k.ord), ',') AS cols
            FROM pg_constraint c
            WHERE c.contype IN ('u', 'p')
            """.trimIndent(),
        ).groupBy({ it["table_name"].toString() }, { it["cols"].toString().split(",") })
}
