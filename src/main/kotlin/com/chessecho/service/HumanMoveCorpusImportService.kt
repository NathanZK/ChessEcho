package com.chessecho.service

import com.chessecho.humanmove.artifact.CorpusArtifactConflict
import com.chessecho.humanmove.artifact.HumanMoveCorpusArtifactService
import com.chessecho.humanmove.artifact.HumanMoveCorpusGameRecord
import com.chessecho.humanmove.artifact.HumanMoveCorpusObservationRecord
import com.chessecho.humanmove.artifact.VerifiedCorpusArtifact
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class HumanMoveCorpusImportReceipt(
    val sourceRunId: UUID,
    val contentDigest: String,
    val coveredPrefix: Int,
)

@Service
class HumanMoveCorpusImportService(
    private val jdbcTemplate: JdbcTemplate,
    private val artifactService: HumanMoveCorpusArtifactService,
    transactionManager: PlatformTransactionManager,
) {
    private val namedJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)
    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val transactions = TransactionTemplate(transactionManager)

    fun import(
        bytes: ByteArray,
        expectedDigest: String,
    ): HumanMoveCorpusImportReceipt = import(bytes.inputStream(), expectedDigest)

    fun import(
        input: InputStream,
        expectedDigest: String,
    ): HumanMoveCorpusImportReceipt {
        val staged = artifactService.stageUpload(input)
        try {
            val verified = artifactService.verifyAndArchive(staged, expectedDigest)
            return transactions.execute { publish(verified) }
                ?: error("Import transaction did not return a publication receipt")
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    private fun publish(verified: VerifiedCorpusArtifact): HumanMoveCorpusImportReceipt {
        val archive = artifactService.archivePath(verified.digest)
        val manifest = verified.manifest
        val sourceRunId = manifest.sourceRunId
        val requestJson = manifest.sourceRunMetadata.path("requestJson").asText()
        val requestSha256 = manifest.sourceRunMetadata.path("requestSha256").asText()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        lockSource(jdbcTemplate, sourceRunId)

        val existingImport =
            jdbcTemplate.query(
                "SELECT rating_band, algorithm_version, source_revision, request_sha256 " +
                    "FROM human_move_corpus_import WHERE source_run_id = ?",
                { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) },
                sourceRunId,
            ).singleOrNull()
        val declaredIdentity =
            listOf(
                manifest.ratingBand,
                manifest.sourceRunMetadata.path("algorithmVersion").asText(),
                manifest.sourceRunMetadata.path("sourceRevision").asText(),
                requestSha256,
            )
        if (existingImport == null) {
            jdbcTemplate.update(
                """
                INSERT INTO human_move_corpus_import (
                    source_run_id, rating_band, algorithm_version, source_revision, request_sha256, request_json,
                    raw_available, imported_at
                ) VALUES (?, ?, ?, ?, ?, ?, true, ?)
                """.trimIndent(),
                sourceRunId,
                declaredIdentity[0],
                declaredIdentity[1],
                declaredIdentity[2],
                declaredIdentity[3],
                requestJson,
                now,
            )
        } else if (existingImport != declaredIdentity) {
            throw CorpusArtifactConflict("Corpus run $sourceRunId is already imported with different source configuration")
        }

        val existingMaxOrdinal =
            jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(qualifying_ordinal), 0) FROM human_move_corpus_imported_game WHERE source_run_id = ?",
                Int::class.java,
                sourceRunId,
            )!!
        if (existingMaxOrdinal == 0) {
            jdbcTemplate.query(
                { connection ->
                    connection.prepareStatement(
                        "SELECT content_digest, covered_prefix FROM human_move_corpus_artifact_snapshot WHERE source_run_id = ?",
                    ).apply {
                        fetchSize = 16
                        setObject(1, sourceRunId)
                    }
                },
                RowCallbackHandler { rs ->
                    val digest = rs.getString(1)
                    val previous =
                        artifactService.verifiedArchivePath(digest)
                            ?: throw CorpusArtifactConflict("Prior snapshot $digest is unavailable for overlap verification")
                    val common = minOf(rs.getInt(2), manifest.coveredPrefix)
                    if (prefixFingerprint(previous, common) != prefixFingerprint(archive, common)) {
                        throw CorpusArtifactConflict("Snapshot $digest diverges from the imported source corpus")
                    }
                },
            )
        }
        val overlapEnd = minOf(existingMaxOrdinal, manifest.coveredPrefix)
        if (overlapEnd > 0) verifyOverlap(sourceRunId, archive, overlapEnd)
        if (manifest.coveredPrefix > existingMaxOrdinal) insertSuffix(sourceRunId, archive, existingMaxOrdinal)

        jdbcTemplate.update(
            "UPDATE human_move_corpus_import SET raw_available = true WHERE source_run_id = ?",
            sourceRunId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO human_move_corpus_artifact_snapshot
                (content_digest, source_run_id, covered_prefix, manifest_json, verified_at)
            VALUES (?, ?, ?, ?, ?) ON CONFLICT (content_digest) DO NOTHING
            """.trimIndent(),
            verified.digest,
            sourceRunId,
            manifest.coveredPrefix,
            verified.manifestJson,
            now,
        )
        return HumanMoveCorpusImportReceipt(sourceRunId, verified.digest, manifest.coveredPrefix)
    }

    fun verifyImportedEvidence(
        archive: Path,
        artifact: VerifiedCorpusArtifact,
    ) {
        verifyOverlap(artifact.manifest.sourceRunId, archive, artifact.manifest.coveredPrefix)
    }

    private fun prefixFingerprint(
        archive: Path,
        prefix: Int,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        artifactService.forEachGame(archive) { game ->
            if (game.qualifyingOrdinal <= prefix) {
                val bytes = mapper.writeValueAsBytes(game)
                digest.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
                digest.update(bytes)
            }
        }
        artifactService.forEachObservation(archive) { observation ->
            if (observation.qualifyingOrdinal <= prefix) {
                val bytes = mapper.writeValueAsBytes(observation)
                digest.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verifyOverlap(
        sourceRunId: UUID,
        archive: Path,
        overlapEnd: Int,
    ) {
        jdbcTemplate.query(
            { connection ->
                connection.prepareStatement(
                    """
                    SELECT qualifying_ordinal, provider_game_id, traversed_player, opponent, opponent_side, opponent_rating,
                           rules, time_class, bfs_depth, pgn, pgn_sha256, observation_total, distinct_move_count, committed_at
                    FROM human_move_corpus_imported_game
                    WHERE source_run_id = ? AND qualifying_ordinal <= ? ORDER BY qualifying_ordinal
                    """.trimIndent(),
                ).apply {
                    fetchSize = 16
                    setObject(1, sourceRunId)
                    setInt(2, overlapEnd)
                }
            },
            ResultSetExtractor { rows ->
                artifactService.forEachGame(archive) { game ->
                    if (game.qualifyingOrdinal <= overlapEnd &&
                        (!rows.next() || !sameGame(rows, game))
                    ) {
                        throw CorpusArtifactConflict("Overlap ordinal ${game.qualifyingOrdinal} of run $sourceRunId diverges")
                    }
                }
                if (rows.next()) throw CorpusArtifactConflict("Imported game count differs from the artifact's covered prefix")
                null
            },
        )
        jdbcTemplate.query(
            { connection ->
                connection.prepareStatement(
                    """
                    SELECT g.qualifying_ordinal, o.position_hash, o.move_played, o.observation_count, p.fen
                    FROM human_move_corpus_imported_observation o
                    JOIN human_move_corpus_imported_game g ON g.id = o.game_id
                    JOIN position p ON p.id = o.position_id
                    WHERE g.source_run_id = ? AND g.qualifying_ordinal <= ?
                    ORDER BY g.qualifying_ordinal, o.position_hash, o.move_played
                    """.trimIndent(),
                ).apply {
                    fetchSize = 500
                    setObject(1, sourceRunId)
                    setInt(2, overlapEnd)
                }
            },
            ResultSetExtractor { rows ->
                artifactService.forEachObservation(archive) { observation ->
                    if (observation.qualifyingOrdinal <= overlapEnd &&
                        (!rows.next() || !sameObservation(rows, observation))
                    ) {
                        throw CorpusArtifactConflict("Overlap observations of run $sourceRunId diverge")
                    }
                }
                if (rows.next()) throw CorpusArtifactConflict("Imported observations of run $sourceRunId diverge")
                null
            },
        )
    }

    private fun sameGame(
        rows: ResultSet,
        game: HumanMoveCorpusGameRecord,
    ): Boolean =
        rows.getInt("qualifying_ordinal") == game.qualifyingOrdinal &&
            rows.getString("provider_game_id") == game.providerGameId &&
            rows.getString("traversed_player") == game.traversedPlayer &&
            rows.getString("opponent") == game.opponent &&
            rows.getString("opponent_side") == game.opponentSide &&
            rows.getInt("opponent_rating") == game.opponentRating &&
            rows.getString("rules") == game.rules &&
            rows.getString("time_class") == game.timeClass &&
            rows.getInt("bfs_depth") == game.bfsDepth &&
            rows.getString("pgn") == game.pgn &&
            rows.getString("pgn_sha256") == game.pgnSha256 &&
            rows.getInt("observation_total") == game.observationTotal &&
            rows.getInt("distinct_move_count") == game.distinctMoveCount &&
            rows.getTimestamp("committed_at").toInstant() == game.committedAt

    private fun sameObservation(
        rows: ResultSet,
        observation: HumanMoveCorpusObservationRecord,
    ): Boolean =
        rows.getInt("qualifying_ordinal") == observation.qualifyingOrdinal &&
            rows.getString("position_hash") == observation.positionHash &&
            rows.getString("move_played") == observation.movePlayed &&
            rows.getInt("observation_count") == observation.observationCount &&
            normalizedFen(rows.getString("fen")) == observation.positionFen

    private fun insertSuffix(
        sourceRunId: UUID,
        archive: Path,
        existingMaxOrdinal: Int,
    ) {
        val gameBatch = ArrayList<Array<Any?>>(16)
        val gameSql =
            """
            INSERT INTO human_move_corpus_imported_game (
                id, source_run_id, qualifying_ordinal, provider_game_id, traversed_player, opponent, opponent_side,
                opponent_rating, rules, time_class, bfs_depth, pgn, pgn_sha256, observation_total, distinct_move_count,
                committed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        artifactService.forEachGame(archive) { game ->
            if (game.qualifyingOrdinal > existingMaxOrdinal) {
                gameBatch.add(
                    arrayOf(
                        UUID.randomUUID(), sourceRunId, game.qualifyingOrdinal, game.providerGameId,
                        game.traversedPlayer, game.opponent, game.opponentSide, game.opponentRating,
                        game.rules, game.timeClass, game.bfsDepth, game.pgn, game.pgnSha256,
                        game.observationTotal, game.distinctMoveCount,
                        OffsetDateTime.ofInstant(game.committedAt, ZoneOffset.UTC),
                    ),
                )
                if (gameBatch.size == 16) {
                    jdbcTemplate.batchUpdate(gameSql, gameBatch)
                    gameBatch.clear()
                }
            }
        }
        if (gameBatch.isNotEmpty()) jdbcTemplate.batchUpdate(gameSql, gameBatch)

        val observations = ArrayList<HumanMoveCorpusObservationRecord>(500)
        artifactService.forEachObservation(archive) { observation ->
            if (observation.qualifyingOrdinal > existingMaxOrdinal) {
                observations.add(observation)
                if (observations.size == 500) {
                    insertObservations(sourceRunId, observations)
                    observations.clear()
                }
            }
        }
        if (observations.isNotEmpty()) insertObservations(sourceRunId, observations)
    }

    private fun insertObservations(
        sourceRunId: UUID,
        observations: List<HumanMoveCorpusObservationRecord>,
    ) {
        val positionIds = resolvePositions(observations)
        val gameIds =
            namedJdbcTemplate.query(
                "SELECT qualifying_ordinal, id FROM human_move_corpus_imported_game " +
                    "WHERE source_run_id = :sourceRunId AND qualifying_ordinal IN (:ordinals)",
                mapOf("sourceRunId" to sourceRunId, "ordinals" to observations.map { it.qualifyingOrdinal }.distinct()),
            ) { rs, _ -> rs.getInt(1) to rs.getObject(2, UUID::class.java) }.toMap()
        jdbcTemplate.batchUpdate(
            "INSERT INTO human_move_corpus_imported_observation " +
                "(id, game_id, position_id, position_hash, move_played, observation_count) VALUES (?, ?, ?, ?, ?, ?)",
            observations.map { observation ->
                arrayOf<Any>(
                    UUID.randomUUID(),
                    gameIds.getValue(observation.qualifyingOrdinal),
                    positionIds.getValue(observation.positionHash),
                    observation.positionHash,
                    observation.movePlayed,
                    observation.observationCount,
                )
            },
        )
    }

    private fun resolvePositions(observations: List<HumanMoveCorpusObservationRecord>): Map<String, UUID> {
        val fenByHash = observations.associate { it.positionHash to it.positionFen }
        val existing =
            namedJdbcTemplate.query(
                "SELECT id, hash, fen FROM position WHERE hash IN (:hashes)",
                mapOf("hashes" to fenByHash.keys.toList()),
            ) { rs, _ -> Triple(rs.getObject("id", UUID::class.java), rs.getString("hash"), rs.getString("fen")) }
        existing.forEach { (_, hash, fen) ->
            if (normalizedFen(fen) != fenByHash.getValue(hash)) {
                throw CorpusArtifactConflict("Existing target position for hash $hash is incompatible with the imported FEN")
            }
        }
        val missing = (fenByHash.keys - existing.map { it.second }.toSet()).sorted()
        if (missing.isNotEmpty()) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            jdbcTemplate.batchUpdate(
                "INSERT INTO position (id, hash, fen, created_at) VALUES (?, ?, ?, ?) ON CONFLICT (hash) DO NOTHING",
                missing.map { hash -> arrayOf<Any>(UUID.randomUUID(), hash, "${fenByHash.getValue(hash)} 0 1", now) },
            )
        }
        val resolved =
            namedJdbcTemplate.query(
                "SELECT id, hash, fen FROM position WHERE hash IN (:hashes)",
                mapOf("hashes" to fenByHash.keys.toList()),
            ) { rs, _ -> Triple(rs.getObject("id", UUID::class.java), rs.getString("hash"), rs.getString("fen")) }
        resolved.forEach { (_, hash, fen) ->
            if (normalizedFen(fen) != fenByHash.getValue(hash)) {
                throw CorpusArtifactConflict("Existing target position for hash $hash is incompatible with the imported FEN")
            }
        }
        return resolved.associate { it.second to it.first }
    }

    private fun normalizedFen(fen: String): String = fen.split(" ").take(4).joinToString(" ")

    companion object {
        fun lockSource(
            jdbc: JdbcTemplate,
            sourceRunId: UUID,
        ) {
            jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                { rs -> rs.next() },
                sourceRunId.toString(),
            )
        }
    }
}
