package com.chessecho.service

import com.chessecho.dto.HumanMoveCorpusCheckpointRequest
import com.chessecho.dto.HumanMoveCorpusCheckpointResponse
import com.chessecho.dto.HumanMoveCorpusCheckpointRow
import com.chessecho.repository.HumanMoveCorpusRunRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Non-destructive nested checkpoints: aggregates exactly qualifying ordinals
 * `1..N` of one run and applies the legacy `/finalize` position-retention rule
 * (a position is retained when its summed count is at least `minObservations`)
 * without persisting anything or invoking the finalizer.
 */
@Service
class HumanMoveCorpusCheckpointService(
    private val jdbcTemplate: JdbcTemplate,
    private val runRepository: HumanMoveCorpusRunRepository,
) {
    @Transactional(readOnly = true)
    fun calculate(
        runId: UUID,
        request: HumanMoveCorpusCheckpointRequest,
    ): HumanMoveCorpusCheckpointResponse {
        val n = request.qualifyingGames
        val minObservations = request.minObservations
        require(n >= 1) { "qualifyingGames must be >= 1 (got $n)" }
        require(minObservations >= 1) { "minObservations must be >= 1 (got $minObservations)" }

        val run = runRepository.findById(runId).orElseThrow { NoSuchElementException("Corpus run $runId not found") }
        require(n <= run.committedFrontier) {
            "qualifyingGames ($n) exceeds committed frontier (${run.committedFrontier})"
        }

        verifyPrefix(runId, n)

        val aggregate =
            jdbcTemplate.query(
                """
                SELECT o.position_id, o.position_hash, o.move_played, SUM(o.observation_count) AS observation_count
                FROM human_move_corpus_observation o
                JOIN human_move_corpus_game g ON g.id = o.game_id
                WHERE g.run_id = ? AND g.qualifying_ordinal <= ?
                GROUP BY o.position_id, o.position_hash, o.move_played
                """.trimIndent(),
                { rs, _ ->
                    HumanMoveCorpusCheckpointRow(
                        positionId = rs.getObject("position_id", UUID::class.java),
                        positionHash = rs.getString("position_hash"),
                        movePlayed = rs.getString("move_played"),
                        observationCount = rs.getLong("observation_count").toInt(),
                    )
                },
                runId,
                n,
            )

        val totalsByPosition = aggregate.groupBy { it.positionHash }.mapValues { (_, rows) -> rows.sumOf { it.observationCount } }
        val retainedPositions = totalsByPosition.filterValues { it >= minObservations }.keys
        val rows =
            aggregate
                .filter { it.positionHash in retainedPositions }
                .sortedWith(compareBy({ it.positionHash }, { it.movePlayed }))

        return HumanMoveCorpusCheckpointResponse(
            runId = runId,
            ratingBand = run.ratingBand,
            runStatus = run.status,
            requestedN = n,
            committedFrontier = run.committedFrontier,
            minObservations = minObservations,
            positionsEvaluated = totalsByPosition.size,
            positionsRemoved = totalsByPosition.size - retainedPositions.size,
            positionsRetained = retainedPositions.size,
            rowsRetained = rows.size,
            observationsRetained = rows.sumOf { it.observationCount },
            rows = rows,
            distributionSha256 =
                HumanMoveCorpusGameWriter.sha256(
                    rows.joinToString("") { "${it.positionHash}\t${it.movePlayed}\t${it.observationCount}\n" },
                ),
            calculatedAt = Instant.now(),
            calculationVersion = CALCULATION_VERSION,
        )
    }

    private fun verifyPrefix(
        runId: UUID,
        n: Int,
    ) {
        val (count, min, max) =
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*), COALESCE(MIN(qualifying_ordinal), 0), COALESCE(MAX(qualifying_ordinal), 0)
                FROM human_move_corpus_game
                WHERE run_id = ? AND qualifying_ordinal <= ?
                """.trimIndent(),
                { rs, _ -> Triple(rs.getLong(1), rs.getInt(2), rs.getInt(3)) },
                runId,
                n,
            )!!
        if (count != n.toLong() || min != 1 || max != n) {
            throw HumanMoveCorpusIntegrityException(
                "Run $runId prefix 1..$n is not contiguous: $count games, ordinals $min..$max",
            )
        }

        val incompleteOrdinals =
            jdbcTemplate.queryForList(
                """
                SELECT g.qualifying_ordinal
                FROM human_move_corpus_game g
                LEFT JOIN (
                    SELECT o.game_id, COUNT(*) AS row_count, SUM(o.observation_count) AS observation_sum
                    FROM human_move_corpus_observation o
                    JOIN human_move_corpus_game pg ON pg.id = o.game_id
                    WHERE pg.run_id = ? AND pg.qualifying_ordinal <= ?
                    GROUP BY o.game_id
                ) c ON c.game_id = g.id
                WHERE g.run_id = ? AND g.qualifying_ordinal <= ?
                  AND (COALESCE(c.row_count, 0) <> g.distinct_move_count
                       OR COALESCE(c.observation_sum, 0) <> g.observation_total)
                ORDER BY g.qualifying_ordinal
                LIMIT 10
                """.trimIndent(),
                Int::class.java,
                runId,
                n,
                runId,
                n,
            )
        if (incompleteOrdinals.isNotEmpty()) {
            throw HumanMoveCorpusIntegrityException(
                "Run $runId games at ordinals $incompleteOrdinals do not match their committed contribution",
            )
        }

        val divergedHashes =
            jdbcTemplate.queryForList(
                """
                SELECT DISTINCT o.position_hash
                FROM human_move_corpus_observation o
                JOIN human_move_corpus_game g ON g.id = o.game_id
                LEFT JOIN position p ON p.id = o.position_id
                WHERE g.run_id = ? AND g.qualifying_ordinal <= ?
                  AND p.hash IS DISTINCT FROM o.position_hash
                LIMIT 10
                """.trimIndent(),
                String::class.java,
                runId,
                n,
            )
        if (divergedHashes.isNotEmpty()) {
            throw HumanMoveCorpusIntegrityException(
                "Run $runId positions no longer match their committed hash snapshot: $divergedHashes",
            )
        }
    }

    companion object {
        const val CALCULATION_VERSION = "human-move-corpus-checkpoint-v1"
    }
}
