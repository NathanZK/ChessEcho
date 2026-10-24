package com.chessecho.integration.corpus

import com.chessecho.service.HumanMoveCorpusIntegrityException
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HumanMoveCorpusPurgeIntegrationTest : HumanMoveCorpusBaseIntegrationTest() {
    @Test
    fun `finalized issue 426 artifact binds one immutable occurrence prefix`() {
        val population = finalizedAndPurgedPopulation("binding")
        val binding = occurrenceBinding(population.runId)

        assertEquals(population.runId, binding["source_run_id"])
        assertEquals(population.contentDigest, binding["content_digest"])
        assertEquals(2, binding["covered_prefix"])
        assertTrue(binding["occurrence_digest"].toString().matches(Regex("[0-9a-f]{64}")))
        assertTrue(((binding["occurrence_count"] ?: error("missing count")) as Number).toInt() > 0)
        assertTrue(binding["finalized_at"] != null)
        assertEquals(
            true,
            jdbcTemplate.queryForObject(
                "SELECT finalized AND verified FROM human_move_corpus_projection WHERE id = ?",
                Boolean::class.java,
                population.projectionId,
            ),
            "the binding must be created through the normal finalized #426 projection path",
        )
    }

    @Test
    fun `post-purge recovery preserves canonical location analysis and rejects missing substituted or mismatched evidence`() {
        val first = finalizedAndPurgedPopulation("recovery-first")
        assertSuccessfulPostPurgeRecovery(first)

        val second = finalizedAndPurgedPopulation("recovery-substitute")
        purgeService.purge(first.runId, first.contentDigest)
        val otherPopulationRow = fetchOtherPopulationRow(second.runId)
        val corruptions = buildCorruptions(otherPopulationRow)
        verifyCorruptedRecoveryRejected(corruptions, first)
    }

    private fun assertSuccessfulPostPurgeRecovery(first: FinalizedPopulation) {
        val firstCanonicalAnalysis = first.canonicalLocationAnalysis
        val firstBinding = occurrenceBinding(first.runId)
        assertEquals(2, firstCanonicalAnalysis.map { it["provider_game_id"] }.toSet().size)
        assertEquals(
            0L,
            count("SELECT COUNT(*) FROM human_move_corpus_imported_game WHERE source_run_id = '${first.runId}'"),
            "recovery must start after the purge removed the imported raw rows",
        )

        importService.import(first.artifactBytes, first.contentDigest)
        assertEquals(
            2,
            count("SELECT COUNT(*) FROM human_move_corpus_imported_game WHERE source_run_id = '${first.runId}'"),
            "verified #426 artifact import must execute the raw-evidence recovery path",
        )
        assertEquals(
            firstCanonicalAnalysis,
            canonicalLocationAnalysis(first.runId),
            "canonical per-game, per-ply location analysis must be byte-for-byte stable across purge and recovery",
        )
        assertEquals(firstBinding, occurrenceBinding(first.runId), "recovery must retain the finalized evidence binding")
    }

    private fun fetchOtherPopulationRow(secondRunId: UUID): Map<String, Any?> =
        jdbcTemplate.queryForMap(
            """
            SELECT qualifying_ordinal, provider_game_id, pre_move_ply, position_hash, move_played,
                   content_digest, covered_prefix
            FROM human_move_corpus_occurrence
            WHERE source_run_id = ?
            ORDER BY qualifying_ordinal, provider_game_id, pre_move_ply
            LIMIT 1
            """.trimIndent(),
            secondRunId,
        )

    private fun buildCorruptions(otherPopulationRow: Map<String, Any?>): List<Pair<String, (FinalizedPopulation) -> Unit>> =
        buildStructuralCorruptions(otherPopulationRow) + buildBindingCorruptions()

    private fun buildStructuralCorruptions(otherPopulationRow: Map<String, Any?>): List<Pair<String, (FinalizedPopulation) -> Unit>> =
        listOf(
            "missing occurrence" to { population ->
                withTriggersDisabled {
                    jdbcTemplate.update(
                        "DELETE FROM human_move_corpus_occurrence WHERE id = " +
                            "(SELECT id FROM human_move_corpus_occurrence WHERE source_run_id = ? ORDER BY id LIMIT 1)",
                        population.runId,
                    )
                }
            },
            "substituted evidence from another finalized population" to { population ->
                withTriggersDisabled {
                    jdbcTemplate.update(
                        """
                        UPDATE human_move_corpus_occurrence
                        SET provider_game_id = ?, position_hash = ?, move_played = ?,
                            content_digest = ?, covered_prefix = ?
                        WHERE id = (
                            SELECT id FROM human_move_corpus_occurrence
                            WHERE source_run_id = ? ORDER BY id LIMIT 1
                        )
                        """.trimIndent(),
                        otherPopulationRow["provider_game_id"],
                        otherPopulationRow["position_hash"],
                        otherPopulationRow["move_played"],
                        otherPopulationRow["content_digest"],
                        otherPopulationRow["covered_prefix"],
                        population.runId,
                    )
                }
            },
        )

    private fun buildBindingCorruptions(): List<Pair<String, (FinalizedPopulation) -> Unit>> =
        listOf(
            "mismatched occurrence digest" to { population ->
                withTriggersDisabled {
                    jdbcTemplate.update(
                        "UPDATE human_move_corpus_occurrence_binding SET occurrence_digest = ? WHERE source_run_id = ?",
                        "f".repeat(64),
                        population.runId,
                    )
                }
            },
            "mismatched covered prefix" to { population ->
                withTriggersDisabled {
                    jdbcTemplate.update(
                        "UPDATE human_move_corpus_occurrence_binding SET covered_prefix = covered_prefix + 1 " +
                            "WHERE source_run_id = ?",
                        population.runId,
                    )
                }
            },
            "mismatched occurrence count" to { population ->
                withTriggersDisabled {
                    jdbcTemplate.update(
                        "UPDATE human_move_corpus_occurrence_binding SET occurrence_count = occurrence_count + 1 " +
                            "WHERE source_run_id = ?",
                        population.runId,
                    )
                }
            },
            "mismatched content digest" to { population ->
                withTriggersDisabled {
                    jdbcTemplate.update(
                        "UPDATE human_move_corpus_occurrence SET content_digest = ? WHERE source_run_id = ?",
                        "f".repeat(64),
                        population.runId,
                    )
                }
            },
        )

    private fun verifyCorruptedRecoveryRejected(
        corruptions: List<Pair<String, (FinalizedPopulation) -> Unit>>,
        first: FinalizedPopulation,
    ) {
        corruptions.forEachIndexed { index, (kind, corrupt) ->
            val population =
                when (index) {
                    0 -> finalizedAndPurgedPopulation("recovery-missing")
                    1 -> first
                    else -> finalizedAndPurgedPopulation("recovery-mismatch-$index")
                }
            if (index == 1) {
                assertEquals(
                    0L,
                    count("SELECT COUNT(*) FROM human_move_corpus_imported_game WHERE source_run_id = '${population.runId}'"),
                )
            }
            corrupt(population)
            assertFailsWith<HumanMoveCorpusIntegrityException>(
                "$kind must be rejected by verified post-purge recovery for run ${population.runId}",
            ) {
                importService.import(population.artifactBytes, population.contentDigest)
            }
            assertEquals(
                0L,
                count("SELECT COUNT(*) FROM human_move_corpus_imported_game WHERE source_run_id = '${population.runId}'"),
                "$kind rejection must not republish partial imported evidence",
            )
        }
    }
}
