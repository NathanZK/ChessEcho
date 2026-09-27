package com.chessecho.humanmove.artifact

import com.chessecho.domain.HumanMoveCorpusRun
import com.chessecho.domain.HumanMoveCorpusRunStatus
import com.chessecho.repository.HumanMoveCorpusRunRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.ResultSet
import java.util.UUID

data class HumanMoveCorpusExportRequest(val runId: UUID, val prefixN: Int)

@ConfigurationProperties(prefix = "chessecho.corpus")
@Component
data class HumanMoveCorpusProperties(
    var archiveRoot: String = "",
    var maxArchiveBytes: Long = 2_147_483_648L,
    var maxExpandedBytes: Long = 4_294_967_296L,
    var maxGames: Int = 100_000,
    var maxObservations: Int = 10_000_000,
    var maxRecordBytes: Int = 4_194_304,
)

data class HumanMoveCorpusExportReceipt(
    val contentDigest: String,
    val sourceRunId: UUID,
    val coveredPrefix: Int,
    val formatVersion: Int,
)

/**
 * Builds, verifies, and durably archives version-1 corpus artifacts (export),
 * and re-verifies uploaded artifacts read-only (verify) without ever exposing
 * a mutation path. Import publication lives in
 * [com.chessecho.service.HumanMoveCorpusImportService].
 */
@Service
class HumanMoveCorpusArtifactService(
    private val jdbcTemplate: JdbcTemplate,
    private val runRepository: HumanMoveCorpusRunRepository,
    private val properties: HumanMoveCorpusProperties,
) {
    private val treeMapper =
        jacksonObjectMapper().apply {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    private val plainMapper = jacksonObjectMapper()

    private fun limits() =
        HumanMoveCorpusArtifactVerifier.Limits(
            maxArchiveBytes = properties.maxArchiveBytes,
            maxExpandedEntryBytes = properties.maxExpandedBytes,
            maxGames = properties.maxGames,
            maxObservations = properties.maxObservations,
            maxRecordBytes = properties.maxRecordBytes,
        )

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun export(request: HumanMoveCorpusExportRequest): HumanMoveCorpusExportReceipt {
        require(request.prefixN >= 1) { "prefixN must be >= 1 (got ${request.prefixN})" }
        val runMetadataFits =
            jdbcTemplate.query(
                """
                SELECT (
                    octet_length(seed_players)::bigint +
                    octet_length(excluded_players)::bigint +
                    octet_length(request_json)::bigint +
                    COALESCE(octet_length(failure_details)::bigint, 0)
                ) <= ?
                FROM human_move_corpus_run
                WHERE id = ?
                """.trimIndent(),
                { rows, _ -> rows.getBoolean(1) },
                properties.maxRecordBytes.toLong(),
                request.runId,
            ).singleOrNull()
        if (runMetadataFits == false) {
            throw CorpusArtifactInvalidException(
                "Source run metadata exceeds the configured maximum manifest record size",
            )
        }
        val run =
            runRepository.findById(request.runId).orElseThrow { NoSuchElementException("Corpus run ${request.runId} not found") }
        require(run.status != HumanMoveCorpusRunStatus.FAILED) {
            "Corpus run ${request.runId} is FAILED; FAILED runs are not exportable"
        }
        require(request.prefixN <= run.committedFrontier) {
            "prefixN (${request.prefixN}) exceeds committed frontier (${run.committedFrontier})"
        }

        val root = Path.of(properties.archiveRoot)
        Files.createDirectories(root)
        val tempFiles = mutableListOf<Path>()
        try {
            val gamesPath = Files.createTempFile(root, "corpus-games-", ".ndjson").also(tempFiles::add)
            val observationsPath = Files.createTempFile(root, "corpus-observations-", ".ndjson").also(tempFiles::add)
            val manifestPath = Files.createTempFile(root, "corpus-manifest-", ".json").also(tempFiles::add)
            val archivePath = Files.createTempFile(root, "corpus-export-", ".zip").also(tempFiles::add)
            val gameWriter =
                HumanMoveCorpusArtifactCodec.EntryWriter(
                    "games.ndjson",
                    gamesPath,
                    properties.maxExpandedBytes,
                    properties.maxRecordBytes,
                )
            try {
                jdbcTemplate.query(
                    { connection ->
                        connection.prepareStatement(
                            """
                            SELECT qualifying_ordinal, provider_game_id, traversed_player, opponent, opponent_side, opponent_rating,
                                   CASE WHEN octet_length(pgn) <= ? THEN pgn ELSE NULL END AS pgn,
                                   octet_length(pgn) AS pgn_bytes,
                                   rules, time_class, bfs_depth, pgn_sha256, observation_total, distinct_move_count, committed_at
                            FROM human_move_corpus_game
                            WHERE run_id = ? AND qualifying_ordinal <= ?
                            ORDER BY qualifying_ordinal
                            """.trimIndent(),
                            ResultSet.TYPE_FORWARD_ONLY,
                            ResultSet.CONCUR_READ_ONLY,
                        ).apply {
                            fetchSize = 16
                            setInt(1, properties.maxRecordBytes)
                            setObject(2, request.runId)
                            setInt(3, request.prefixN)
                        }
                    },
                    ResultSetExtractor { rows ->
                        writeGameRecords(rows, gameWriter)
                        null
                    },
                )
            } finally {
                gameWriter.close()
            }
            val gamesEntry = gameWriter.entryFile()
            val maxObservationBytes = properties.maxExpandedBytes - gamesEntry.size
            if (maxObservationBytes < 0) {
                throw CorpusArtifactInvalidException("Expanded artifact entries exceed the configured maximum")
            }

            val observationWriter =
                HumanMoveCorpusArtifactCodec.EntryWriter(
                    "observations.ndjson",
                    observationsPath,
                    maxObservationBytes,
                    properties.maxRecordBytes,
                )
            try {
                jdbcTemplate.query(
                    { connection ->
                        connection.prepareStatement(
                            """
                            SELECT g.qualifying_ordinal, o.position_hash,
                                   CASE WHEN octet_length(p.fen) <= 512 THEN p.fen ELSE NULL END AS fen,
                                   octet_length(p.fen) AS fen_bytes, o.move_played, o.observation_count
                            FROM human_move_corpus_observation o
                            JOIN human_move_corpus_game g ON g.id = o.game_id
                            JOIN position p ON p.id = o.position_id
                            WHERE g.run_id = ? AND g.qualifying_ordinal <= ?
                            ORDER BY g.qualifying_ordinal, o.position_hash, o.move_played
                            """.trimIndent(),
                            ResultSet.TYPE_FORWARD_ONLY,
                            ResultSet.CONCUR_READ_ONLY,
                        ).apply {
                            fetchSize = 512
                            setObject(1, request.runId)
                            setInt(2, request.prefixN)
                        }
                    },
                    ResultSetExtractor { rows ->
                        writeObservationRecords(rows, observationWriter)
                        null
                    },
                )
            } finally {
                observationWriter.close()
            }
            val observationsEntry = observationWriter.entryFile()

            // Re-check the committed frontier at the end of the repeatable-read snapshot.
            val recheckedFrontier =
                jdbcTemplate.queryForObject(
                    "SELECT committed_frontier FROM human_move_corpus_run WHERE id = ?",
                    Int::class.java,
                    request.runId,
                )!!
            require(recheckedFrontier >= request.prefixN) {
                "Corpus run ${request.runId} no longer covers prefixN (${request.prefixN}) within this snapshot"
            }
            require(gameWriter.count == request.prefixN) {
                "Corpus run ${request.runId} does not contain the complete prefixN (${request.prefixN})"
            }
            require(gameWriter.count <= properties.maxGames && observationWriter.count <= properties.maxObservations) {
                "Corpus snapshot exceeds the configured record count limit"
            }

            val e6Eligible = run.status == HumanMoveCorpusRunStatus.COMPLETED && request.prefixN == recheckedFrontier
            val manifest =
                buildManifest(
                    run = run,
                    coveredPrefix = request.prefixN,
                    sourceFrontier = recheckedFrontier,
                    gameCount = gameWriter.count,
                    observationCount = observationWriter.count,
                    gamesEntry = gamesEntry,
                    observationsEntry = observationsEntry,
                    e6Eligible = e6Eligible,
                )
            val entryBytesBeforeManifest = Math.addExact(gamesEntry.size, observationsEntry.size)
            if (entryBytesBeforeManifest >= properties.maxExpandedBytes) {
                throw CorpusArtifactInvalidException("Expanded artifact entries exceed the configured maximum")
            }
            HumanMoveCorpusArtifactCodec.writeJson(manifest, manifestPath, properties.maxRecordBytes.toLong())
            val manifestEntry = HumanMoveCorpusArtifactCodec.entryFile("manifest.json", manifestPath)
            if (manifestEntry.size > properties.maxExpandedBytes - entryBytesBeforeManifest
            ) {
                throw CorpusArtifactInvalidException("Expanded artifact entries exceed the configured maximum")
            }
            val entries = listOf(gamesEntry, observationsEntry, manifestEntry)
            val digest = HumanMoveCorpusArtifactCodec.digestFiles(entries)
            HumanMoveCorpusArtifactCodec.writeZip(entries, archivePath, properties.maxArchiveBytes)
            writeArchive(digest, archivePath)

            return HumanMoveCorpusExportReceipt(
                contentDigest = digest,
                sourceRunId = request.runId,
                coveredPrefix = request.prefixN,
                formatVersion = HumanMoveCorpusArtifactVerifier.SUPPORTED_FORMAT_VERSION,
            )
        } finally {
            tempFiles.forEach { Files.deleteIfExists(it) }
        }
    }

    /** Fully verifies an uploaded artifact read-only; never writes to the database. */
    fun verify(
        input: InputStream,
        expectedDigest: String,
    ): VerifiedCorpusArtifact = HumanMoveCorpusArtifactVerifier.verify(input, expectedDigest, limits())

    fun stageUpload(input: InputStream): Path {
        val root = Path.of(properties.archiveRoot)
        Files.createDirectories(root)
        val staged = Files.createTempFile(root, "corpus-upload-", ".zip")
        try {
            HumanMoveCorpusArtifactCodec.copyBounded(input, staged, properties.maxArchiveBytes)
            return staged
        } catch (e: Exception) {
            Files.deleteIfExists(staged)
            throw e
        }
    }

    fun verifyArchive(
        path: Path,
        expectedDigest: String,
    ): VerifiedCorpusArtifact =
        HumanMoveCorpusArtifactVerifier.verify(path, expectedDigest, limits())
            .copy(archivePath = path.toAbsolutePath().normalize())

    fun verifyAndArchive(
        path: Path,
        digest: String,
    ): VerifiedCorpusArtifact {
        val verified = verifyArchive(path, digest)
        writeArchive(verified.digest, path)
        return verified.copy(archivePath = archivePath(verified.digest).toAbsolutePath().normalize())
    }

    fun forEachGame(
        artifact: VerifiedCorpusArtifact,
        consume: (HumanMoveCorpusGameRecord) -> Unit,
    ) {
        val archive = requireVerifiedArchive(artifact)
        forEachGame(archive, consume)
    }

    /** Streaming path overload retained for internal lifecycle callers migrating to verified handles. */
    fun forEachGame(
        archive: Path,
        consume: (HumanMoveCorpusGameRecord) -> Unit,
    ) {
        var count = 0
        HumanMoveCorpusArtifactVerifier.forEachGame(archive, properties.maxRecordBytes) { game ->
            count++
            if (count > properties.maxGames) {
                throw CorpusArtifactInvalidException("Archive exceeds the configured game count limit")
            }
            consume(game)
        }
    }

    fun forEachObservation(
        artifact: VerifiedCorpusArtifact,
        consume: (HumanMoveCorpusObservationRecord) -> Unit,
    ) {
        val archive = requireVerifiedArchive(artifact)
        forEachObservation(archive, consume)
    }

    /** Streaming path overload retained for internal lifecycle callers migrating to verified handles. */
    fun forEachObservation(
        archive: Path,
        consume: (HumanMoveCorpusObservationRecord) -> Unit,
    ) {
        var count = 0
        HumanMoveCorpusArtifactVerifier.forEachObservation(archive, properties.maxRecordBytes) { observation ->
            count++
            if (count > properties.maxObservations) {
                throw CorpusArtifactInvalidException("Archive exceeds the configured observation count limit")
            }
            consume(observation)
        }
    }

    private fun requireVerifiedArchive(artifact: VerifiedCorpusArtifact): Path {
        val path =
            artifact.archivePath
                ?: throw IllegalArgumentException("Verified artifact ${artifact.digest} has no retained archive path")
        val expectedPath = archivePath(artifact.digest).toAbsolutePath().normalize()
        require(path.toAbsolutePath().normalize() == expectedPath) {
            "Verified artifact ${artifact.digest} does not refer to its content-addressed archive"
        }
        verifyArchive(path, artifact.digest)
        return path
    }

    fun archivePath(digest: String): Path = Path.of(properties.archiveRoot, "$digest.zip")

    fun verifiedArchivePath(digest: String): Path? {
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "Invalid artifact digest" }
        val path = archivePath(digest)
        if (!Files.isRegularFile(path)) return null
        verifyArchive(path, digest)
        return path
    }

    fun maxArchiveBytes(): Long = properties.maxArchiveBytes

    private fun writeArchive(
        digest: String,
        source: Path,
    ) {
        val root = Path.of(properties.archiveRoot)
        Files.createDirectories(root)
        val target = archivePath(digest)
        if (Files.exists(target)) {
            if (!Files.isRegularFile(target) || Files.mismatch(target, source) != -1L) {
                throw CorpusArtifactConflict("Archived artifact $digest differs from the verified bytes")
            }
            verifyArchive(target, digest)
            return
        }
        try {
            // Same-directory hard-link publication is atomic and fails if another writer published first.
            Files.createLink(target, source)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            if (!Files.isRegularFile(target) || Files.mismatch(target, source) != -1L) {
                throw CorpusArtifactConflict("Concurrent archive publication for $digest has different bytes")
            }
            verifyArchive(target, digest)
        }
    }

    private fun writeGameRecords(
        rows: java.sql.ResultSet,
        writer: HumanMoveCorpusArtifactCodec.EntryWriter,
    ) {
        while (rows.next()) {
            if (writer.count >= properties.maxGames) {
                throw CorpusArtifactInvalidException("Corpus export exceeds the configured game count limit")
            }
            if (rows.getLong("pgn_bytes") > properties.maxRecordBytes) {
                throw CorpusArtifactInvalidException("Game PGN exceeds the configured maximum record size")
            }
            val pgn =
                rows.getString("pgn")
                    ?: throw CorpusArtifactInvalidException("Game ${rows.getInt("qualifying_ordinal")} has no PGN")
            val record =
                sortedMapOf<String, Any?>(
                    "bfsDepth" to rows.getInt("bfs_depth"),
                    "committedAt" to rows.getTimestamp("committed_at").toInstant(),
                    "distinctMoveCount" to rows.getInt("distinct_move_count"),
                    "observationTotal" to rows.getInt("observation_total"),
                    "opponent" to rows.getString("opponent"),
                    "opponentRating" to rows.getInt("opponent_rating"),
                    "opponentSide" to rows.getString("opponent_side"),
                    "pgn" to pgn,
                    "pgnSha256" to rows.getString("pgn_sha256"),
                    "providerGameId" to rows.getString("provider_game_id"),
                    "qualifyingOrdinal" to rows.getInt("qualifying_ordinal"),
                    "rules" to rows.getString("rules"),
                    "timeClass" to rows.getString("time_class"),
                    "traversedPlayer" to rows.getString("traversed_player"),
                )
            writer.writeRecord(HumanMoveCorpusArtifactCodec.mapper.writeValueAsBytes(treeMapper.valueToTree<JsonNode>(record)))
        }
    }

    private fun writeObservationRecords(
        rows: java.sql.ResultSet,
        writer: HumanMoveCorpusArtifactCodec.EntryWriter,
    ) {
        while (rows.next()) {
            if (writer.count >= properties.maxObservations) {
                throw CorpusArtifactInvalidException("Corpus export exceeds the configured observation count limit")
            }
            if (rows.getInt("fen_bytes") > 512) {
                throw CorpusArtifactInvalidException("Source position FEN exceeds the supported 512-byte export bound")
            }
            val fen =
                rows.getString("fen")
                    ?: throw CorpusArtifactInvalidException("Observation ${rows.getInt("qualifying_ordinal")} has no position FEN")
            val record =
                sortedMapOf<String, Any?>(
                    "movePlayed" to rows.getString("move_played"),
                    "observationCount" to rows.getInt("observation_count"),
                    "positionFen" to normalizedFen(fen),
                    "positionHash" to rows.getString("position_hash"),
                    "qualifyingOrdinal" to rows.getInt("qualifying_ordinal"),
                )
            writer.writeRecord(HumanMoveCorpusArtifactCodec.mapper.writeValueAsBytes(treeMapper.valueToTree<JsonNode>(record)))
        }
    }

    private fun normalizedFen(fen: String): String {
        val parts = fen.split(" ")
        return if (parts.size >= 4) "${parts[0]} ${parts[1]} ${parts[2]} ${parts[3]}" else fen
    }

    private fun buildManifest(
        run: HumanMoveCorpusRun,
        coveredPrefix: Int,
        sourceFrontier: Int,
        gameCount: Int,
        observationCount: Int,
        gamesEntry: HumanMoveCorpusArtifactCodec.EntryFile,
        observationsEntry: HumanMoveCorpusArtifactCodec.EntryFile,
        e6Eligible: Boolean,
    ): HumanMoveCorpusArtifactManifest {
        val seedPlayers = plainMapper.readValue<List<String>>(run.seedPlayers)
        val excludedPlayers = plainMapper.readValue<List<String>>(run.excludedPlayers)
        val metadata =
            sortedMapOf<String, Any?>(
                "algorithmVersion" to run.algorithmVersion,
                "archiveFetchFailureCount" to run.archiveFetchFailureCount,
                "batchSize" to run.batchSize,
                "createdAt" to run.createdAt,
                "excludedPlayers" to excludedPlayers,
                "failureDetails" to run.failureDetails,
                "finishedAt" to run.finishedAt,
                "maxDepth" to run.maxDepth,
                "maxGamesPerPlayer" to run.maxGamesPerPlayer,
                "maxPlayers" to run.maxPlayers,
                "maxQualifyingGames" to run.maxQualifyingGames,
                "rejectedGameCount" to run.rejectedGameCount,
                "requestJson" to run.requestJson,
                "requestSha256" to run.requestSha256,
                "seedPlayers" to seedPlayers,
                "sourceRevision" to run.sourceRevision,
                "stopReason" to run.stopReason,
                "updatedAt" to run.updatedAt,
            )
        return HumanMoveCorpusArtifactManifest(
            formatVersion = HumanMoveCorpusArtifactVerifier.SUPPORTED_FORMAT_VERSION,
            snapshotKind = HumanMoveCorpusArtifactVerifier.SNAPSHOT_KIND,
            sourceRunId = run.id,
            sourceStatus = run.status.name,
            sourceFrontier = sourceFrontier,
            coveredPrefix = coveredPrefix,
            ratingBand = run.ratingBand,
            sourceRunMetadata = treeMapper.valueToTree(metadata),
            gameCount = gameCount,
            observationCount = observationCount,
            gameBytes = gamesEntry.size,
            observationBytes = observationsEntry.size,
            gamesSha256 = gamesEntry.sha256,
            observationsSha256 = observationsEntry.sha256,
            e6Eligible = e6Eligible,
        )
    }
}
