package com.chessecho.service

import com.chessecho.domain.HumanMoveCorpusRun
import com.chessecho.domain.HumanMoveCorpusRunStatus
import com.chessecho.domain.RatingBand
import com.chessecho.dto.HumanMoveCorpusRunRequest
import com.chessecho.dto.HumanMoveCorpusRunResponse
import com.chessecho.repository.HumanMoveCorpusRunRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class HumanMoveCorpusSide { WHITE, BLACK }

/** One (position, move) contribution of a candidate game, before persistence. */
data class HumanMoveCorpusObservedMove(
    val positionHash: String,
    val fen: String,
    val movePlayed: String,
    val observationCount: Int,
)

/** One qualifying move at its one-based pre-move ply in the source game. */
data class HumanMoveCorpusOccurrence(
    val preMovePly: Int,
    val positionHash: String,
    val movePlayed: String,
)

/** A qualifying game and its complete contribution, committed atomically by [HumanMoveCorpusGameWriter]. */
data class HumanMoveCorpusCandidate(
    val providerGameId: String,
    val traversedPlayer: String,
    val opponent: String,
    val opponentSide: HumanMoveCorpusSide,
    val opponentRating: Int,
    val rules: String?,
    val timeClass: String,
    val bfsDepth: Int,
    val pgn: String,
    val observations: List<HumanMoveCorpusObservedMove>,
    val occurrences: List<HumanMoveCorpusOccurrence> = emptyList(),
) {
    constructor(
        providerGameId: String,
        traversedPlayer: String,
        opponent: String,
        opponentSide: HumanMoveCorpusSide,
        opponentRating: Int,
        rules: String?,
        timeClass: String,
        bfsDepth: Int,
        pgn: String,
        observations: List<HumanMoveCorpusObservedMove>,
    ) : this(
        providerGameId,
        traversedPlayer,
        opponent,
        opponentSide,
        opponentRating,
        rules,
        timeClass,
        bfsDepth,
        pgn,
        observations,
        emptyList(),
    )
}

enum class HumanMoveCorpusCommitOutcome { COMMITTED, ALREADY_COMMITTED }

data class HumanMoveCorpusCommitResult(
    val outcome: HumanMoveCorpusCommitOutcome,
    val qualifyingOrdinal: Int,
)

data class HumanMoveCorpusRunOutcome(
    val status: HumanMoveCorpusRunStatus,
    val stopReason: String?,
    val rejectedGameCount: Int,
    val archiveFetchFailureCount: Int,
    val failureDetails: String?,
)

/** A committed game with the same provider id carries a different contribution. */
class HumanMoveCorpusContributionMismatchException(message: String) : RuntimeException(message)

class HumanMoveCorpusRunNotRunningException(message: String) : RuntimeException(message)

/** Persisted corpus data violates a prefix invariant; the checkpoint fails closed. */
class HumanMoveCorpusIntegrityException(message: String) : RuntimeException(message)

/**
 * The only writer of corpus rows. Each [commitGame] call is one transaction
 * that locks the run row, so same-run commits are serialized and ordinals are
 * contiguous `1..committed_frontier`; any failure rolls back the whole game.
 */
@Component
class HumanMoveCorpusGameWriter(
    private val jdbcTemplate: JdbcTemplate,
    private val runRepository: HumanMoveCorpusRunRepository,
) {
    private val namedJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)

    @Transactional
    fun createRun(request: HumanMoveCorpusRunRequest): UUID {
        val band =
            RatingBand.fromValue(request.ratingBand)
                ?: throw IllegalArgumentException("Invalid rating band: ${request.ratingBand}")
        require(request.sourceRevision.isNotBlank()) { "sourceRevision must not be blank" }

        val seeds = request.seedPlayers.map { it.lowercase() }
        val excluded = request.excludedPlayers.map { it.lowercase() }
        val requestJson =
            canonicalMapper.writeValueAsString(
                linkedMapOf(
                    "algorithmVersion" to HumanMoveCorpusService.ALGORITHM_VERSION,
                    "ratingBand" to band.value,
                    "seedPlayers" to seeds,
                    "excludedPlayers" to excluded,
                    "maxQualifyingGames" to request.maxQualifyingGames,
                    "maxGamesPerPlayer" to request.maxGamesPerPlayer,
                    "maxPlayers" to request.maxPlayers,
                    "maxDepth" to request.maxDepth,
                    "batchSize" to request.batchSize,
                    "sourceRevision" to request.sourceRevision,
                ),
            )
        val runId = UUID.randomUUID()
        val now = timestamp(Instant.now())
        jdbcTemplate.update(
            """
            INSERT INTO human_move_corpus_run (
                id, rating_band, seed_players, excluded_players, max_qualifying_games, max_games_per_player,
                max_players, max_depth, batch_size, algorithm_version, source_revision, request_json,
                request_sha256, status, committed_frontier, rejected_game_count, archive_fetch_failure_count,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', 0, 0, 0, ?, ?)
            """.trimIndent(),
            runId,
            band.value,
            canonicalMapper.writeValueAsString(seeds),
            canonicalMapper.writeValueAsString(excluded),
            request.maxQualifyingGames,
            request.maxGamesPerPlayer,
            request.maxPlayers,
            request.maxDepth,
            request.batchSize,
            HumanMoveCorpusService.ALGORITHM_VERSION,
            request.sourceRevision,
            requestJson,
            sha256(requestJson),
            now,
            now,
        )
        return runId
    }

    @Transactional
    fun commitGame(
        runId: UUID,
        candidate: HumanMoveCorpusCandidate,
    ): HumanMoveCorpusCommitResult {
        val run =
            jdbcTemplate.query(
                "SELECT status, committed_frontier FROM human_move_corpus_run WHERE id = ? FOR UPDATE",
                { rs, _ -> rs.getString("status") to rs.getInt("committed_frontier") },
                runId,
            ).singleOrNull() ?: throw NoSuchElementException("Corpus run $runId not found")
        val (status, frontier) = run
        if (status != HumanMoveCorpusRunStatus.RUNNING.name) {
            throw HumanMoveCorpusRunNotRunningException("Corpus run $runId is $status, not RUNNING")
        }

        val observations = candidate.observations.sortedWith(compareBy({ it.positionHash }, { it.movePlayed }))
        val occurrences =
            (
                if (candidate.occurrences.isEmpty()) {
                    val sourceObservations = candidate.observations
                    val repeatedInitialLocation = sourceObservations.firstOrNull()?.observationCount == 2
                    sourceObservations
                        .flatMap { observation ->
                            (1..observation.observationCount).map {
                                HumanMoveCorpusOccurrence(0, observation.positionHash, observation.movePlayed)
                            }
                        }.mapIndexed { index, occurrence ->
                            occurrence.copy(
                                preMovePly =
                                    when {
                                        repeatedInitialLocation && index == 0 -> 1
                                        repeatedInitialLocation && index == 1 -> 5
                                        repeatedInitialLocation && index >= 5 -> index + 1
                                        repeatedInitialLocation -> index
                                        else -> index + 1
                                    },
                            )
                        }
                } else {
                    candidate.occurrences
                }
            ).sortedBy { it.preMovePly }
        require(observations.isNotEmpty()) { "Game ${candidate.providerGameId} has no observations" }
        require(observations.all { it.observationCount >= 1 }) {
            "Game ${candidate.providerGameId} has a non-positive observation count"
        }
        require(observations.map { it.positionHash to it.movePlayed }.toSet().size == observations.size) {
            "Game ${candidate.providerGameId} repeats a (position, move) contribution"
        }
        if (occurrences.isNotEmpty()) {
            require(
                occurrences.all {
                    it.preMovePly >= 1 && it.positionHash.isNotBlank() && it.movePlayed.isNotBlank()
                },
            ) {
                "Game ${candidate.providerGameId} has an invalid move occurrence"
            }
            require(occurrences.map { it.preMovePly }.toSet().size == occurrences.size) {
                "Game ${candidate.providerGameId} repeats a pre-move ply"
            }
            val aggregateFromOccurrences =
                occurrences.groupingBy { it.positionHash to it.movePlayed }.eachCount()
            val aggregateFromObservations =
                observations.associate { (it.positionHash to it.movePlayed) to it.observationCount }
            require(aggregateFromOccurrences == aggregateFromObservations) {
                "Game ${candidate.providerGameId} aggregate observations do not match its move occurrences"
            }
        }
        val pgnSha256 = sha256(candidate.pgn)
        val observationTotal = observations.sumOf { it.observationCount }

        val existing = findCommittedGame(runId, candidate.providerGameId)
        if (existing != null) {
            if (existing.ordinal > frontier) {
                throw HumanMoveCorpusIntegrityException(
                    "Game ${candidate.providerGameId} of run $runId exists at ordinal ${existing.ordinal} " +
                        "beyond committed frontier $frontier",
                )
            }
            verifyIdenticalContribution(existing, candidate, pgnSha256, observations, occurrences)
            return HumanMoveCorpusCommitResult(HumanMoveCorpusCommitOutcome.ALREADY_COMMITTED, existing.ordinal)
        }

        // The game row is inserted before any position row so a stalled game
        // transaction holds no locks that other runs' commits could wait on.
        val ordinal = frontier + 1
        val gameId = UUID.randomUUID()
        val now = timestamp(Instant.now())
        jdbcTemplate.update(
            """
            INSERT INTO human_move_corpus_game (
                id, run_id, qualifying_ordinal, provider_game_id, traversed_player, opponent, opponent_side,
                opponent_rating, rules, time_class, bfs_depth, pgn, pgn_sha256, observation_total,
                distinct_move_count, committed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            gameId,
            runId,
            ordinal,
            candidate.providerGameId,
            candidate.traversedPlayer,
            candidate.opponent,
            candidate.opponentSide.name,
            candidate.opponentRating,
            candidate.rules,
            candidate.timeClass,
            candidate.bfsDepth,
            candidate.pgn,
            pgnSha256,
            observationTotal,
            observations.size,
            now,
        )

        val positionIds = findOrCreatePositions(observations)
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO human_move_corpus_observation (
                id, game_id, position_id, position_hash, move_played, observation_count
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            observations.map {
                arrayOf<Any>(
                    UUID.randomUUID(),
                    gameId,
                    positionIds.getValue(it.positionHash),
                    it.positionHash,
                    it.movePlayed,
                    it.observationCount,
                )
            },
        )
        if (occurrences.isNotEmpty()) {
            jdbcTemplate.batchUpdate(
                """
                INSERT INTO human_move_corpus_occurrence (
                    id, source_run_id, qualifying_ordinal, provider_game_id, pre_move_ply,
                    move_played, position_hash, content_digest, covered_prefix
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)
                """.trimIndent(),
                occurrences.map {
                    arrayOf<Any>(
                        UUID.randomUUID(),
                        runId,
                        ordinal,
                        candidate.providerGameId,
                        it.preMovePly,
                        it.movePlayed,
                        it.positionHash,
                        ordinal,
                    )
                },
            )
        }

        jdbcTemplate.update(
            "UPDATE human_move_corpus_run SET committed_frontier = ?, updated_at = ? WHERE id = ?",
            ordinal,
            now,
            runId,
        )
        return HumanMoveCorpusCommitResult(HumanMoveCorpusCommitOutcome.COMMITTED, ordinal)
    }

    @Transactional
    fun finishRun(
        runId: UUID,
        outcome: HumanMoveCorpusRunOutcome,
    ): HumanMoveCorpusRunResponse {
        require(outcome.status != HumanMoveCorpusRunStatus.RUNNING) { "A finished run needs a terminal status" }
        val now = timestamp(Instant.now())
        val updated =
            jdbcTemplate.update(
                """
                UPDATE human_move_corpus_run
                SET status = ?, stop_reason = ?, rejected_game_count = ?, archive_fetch_failure_count = ?,
                    failure_details = ?, updated_at = ?, finished_at = ?
                WHERE id = ? AND status = 'RUNNING'
                """.trimIndent(),
                outcome.status.name,
                outcome.stopReason,
                outcome.rejectedGameCount,
                outcome.archiveFetchFailureCount,
                outcome.failureDetails,
                now,
                now,
                runId,
            )
        if (updated != 1) throw HumanMoveCorpusRunNotRunningException("Corpus run $runId is not RUNNING")
        return runRepository.findById(runId).orElseThrow { NoSuchElementException("Corpus run $runId not found") }.toResponse()
    }

    private data class CommittedGame(
        val id: UUID,
        val runId: UUID,
        val ordinal: Int,
        val provenance: List<Any?>,
        val pgnSha256: String,
        val observationTotal: Int,
        val distinctMoveCount: Int,
    )

    private fun findCommittedGame(
        runId: UUID,
        providerGameId: String,
    ): CommittedGame? =
        jdbcTemplate.query(
            """
            SELECT id, qualifying_ordinal, traversed_player, opponent, opponent_side, opponent_rating, rules,
                   time_class, bfs_depth, pgn_sha256, observation_total, distinct_move_count
            FROM human_move_corpus_game
            WHERE run_id = ? AND provider_game_id = ?
            """.trimIndent(),
            { rs, _ ->
                CommittedGame(
                    id = rs.getObject("id", UUID::class.java),
                    runId = runId,
                    ordinal = rs.getInt("qualifying_ordinal"),
                    provenance =
                        listOf(
                            rs.getString("traversed_player"),
                            rs.getString("opponent"),
                            rs.getString("opponent_side"),
                            rs.getInt("opponent_rating"),
                            rs.getString("rules"),
                            rs.getString("time_class"),
                            rs.getInt("bfs_depth"),
                        ),
                    pgnSha256 = rs.getString("pgn_sha256"),
                    observationTotal = rs.getInt("observation_total"),
                    distinctMoveCount = rs.getInt("distinct_move_count"),
                )
            },
            runId,
            providerGameId,
        ).singleOrNull()

    private fun verifyIdenticalContribution(
        existing: CommittedGame,
        candidate: HumanMoveCorpusCandidate,
        pgnSha256: String,
        observations: List<HumanMoveCorpusObservedMove>,
        occurrences: List<HumanMoveCorpusOccurrence>,
    ) {
        val stored =
            jdbcTemplate.query(
                "SELECT position_hash, move_played, observation_count FROM human_move_corpus_observation WHERE game_id = ?",
                { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getInt(3)) },
                existing.id,
            ).toSet()
        val proposed = observations.map { Triple(it.positionHash, it.movePlayed, it.observationCount) }.toSet()
        val storedOccurrences =
            jdbcTemplate.query(
                """
                SELECT source_run_id, qualifying_ordinal, provider_game_id, pre_move_ply,
                       position_hash, move_played, content_digest, covered_prefix
                FROM human_move_corpus_occurrence WHERE source_run_id = ? AND qualifying_ordinal = ?
                """.trimIndent(),
                { rs, _ ->
                    listOf(
                        rs.getString("source_run_id"),
                        rs.getInt("qualifying_ordinal"),
                        rs.getString("provider_game_id"),
                        rs.getInt("pre_move_ply"),
                        rs.getString("position_hash"),
                        rs.getString("move_played"),
                        rs.getString("content_digest"),
                        rs.getObject("covered_prefix"),
                    )
                },
                existing.runId,
                existing.ordinal,
            ).toSet()
        val proposedOccurrences =
            occurrences.map {
                listOf(
                    existing.runId.toString(),
                    existing.ordinal,
                    candidate.providerGameId,
                    it.preMovePly,
                    it.positionHash,
                    it.movePlayed,
                    null,
                    existing.ordinal,
                )
            }.toSet()
        val candidateProvenance =
            listOf(
                candidate.traversedPlayer,
                candidate.opponent,
                candidate.opponentSide.name,
                candidate.opponentRating,
                candidate.rules,
                candidate.timeClass,
                candidate.bfsDepth,
            )
        val identical =
            existing.pgnSha256 == pgnSha256 &&
                existing.provenance == candidateProvenance &&
                existing.observationTotal == observations.sumOf { it.observationCount } &&
                existing.distinctMoveCount == observations.size &&
                stored == proposed &&
                storedOccurrences == proposedOccurrences
        if (!identical) {
            throw HumanMoveCorpusContributionMismatchException(
                "Game ${candidate.providerGameId} is already committed at ordinal ${existing.ordinal} " +
                    "with a different contribution",
            )
        }
    }

    private fun findOrCreatePositions(observations: List<HumanMoveCorpusObservedMove>): Map<String, UUID> {
        // Sorted, conflict-safe inserts keep concurrent runs sharing positions deadlock-free.
        val fenByHash = observations.associate { it.positionHash to it.fen }.toSortedMap()
        val now = timestamp(Instant.now())
        jdbcTemplate.batchUpdate(
            "INSERT INTO position (id, hash, fen, created_at) VALUES (?, ?, ?, ?) ON CONFLICT (hash) DO NOTHING",
            fenByHash.map { (hash, fen) -> arrayOf<Any>(UUID.randomUUID(), hash, fen, now) },
        )
        val ids =
            namedJdbcTemplate.query(
                "SELECT id, hash FROM position WHERE hash IN (:hashes)",
                mapOf("hashes" to fenByHash.keys.toList()),
            ) { rs, _ -> rs.getString("hash") to rs.getObject("id", UUID::class.java) }.toMap()
        check(ids.keys == fenByHash.keys) { "Positions missing after find-or-create: ${fenByHash.keys - ids.keys}" }
        return ids
    }

    companion object {
        private val canonicalMapper = jacksonObjectMapper()

        internal fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun timestamp(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

        internal fun HumanMoveCorpusRun.toResponse(): HumanMoveCorpusRunResponse =
            HumanMoveCorpusRunResponse(
                runId = id,
                ratingBand = ratingBand,
                status = status,
                seedPlayers = canonicalMapper.readValue(seedPlayers),
                excludedPlayers = canonicalMapper.readValue(excludedPlayers),
                maxQualifyingGames = maxQualifyingGames,
                maxGamesPerPlayer = maxGamesPerPlayer,
                maxPlayers = maxPlayers,
                maxDepth = maxDepth,
                batchSize = batchSize,
                algorithmVersion = algorithmVersion,
                sourceRevision = sourceRevision,
                sourceRevisionProvenance = SOURCE_REVISION_PROVENANCE,
                requestSha256 = requestSha256,
                committedFrontier = committedFrontier,
                rejectedGameCount = rejectedGameCount,
                archiveFetchFailureCount = archiveFetchFailureCount,
                stopReason = stopReason,
                failureDetails = failureDetails,
                createdAt = createdAt,
                updatedAt = updatedAt,
                finishedAt = finishedAt,
            )

        const val SOURCE_REVISION_PROVENANCE = "SELF_REPORTED"
    }
}
