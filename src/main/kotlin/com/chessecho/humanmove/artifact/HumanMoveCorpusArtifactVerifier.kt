package com.chessecho.humanmove.artifact

import com.chessecho.service.GameParserService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Fully verifies a version-1 corpus artifact archive before any database
 * mutation is attempted: format/size bounds, the caller-supplied expected
 * digest, manifest self-consistency, and every cross-record invariant
 * described by the Issue #426 artifact contract. Nothing here is a signature;
 * an internally consistent forged artifact with a substituted expected
 * digest is not authenticated by this alone, only trusted-channel provenance is.
 */
object HumanMoveCorpusArtifactVerifier {
    const val SUPPORTED_FORMAT_VERSION = 1
    const val SNAPSHOT_KIND = "human-move-corpus-v1"

    private val mapper = HumanMoveCorpusArtifactCodec.mapper.copy().apply { registerModule(JavaTimeModule()) }

    data class Limits(
        val maxArchiveBytes: Long,
        val maxExpandedEntryBytes: Long,
        val maxGames: Int,
        val maxObservations: Int,
        val maxRecordBytes: Int,
    )

    fun verify(
        input: InputStream,
        expectedDigest: String,
        limits: Limits,
    ): VerifiedCorpusArtifact =
        withTemporaryDirectory { directory ->
            val archive = Files.createTempFile(directory, "corpus-upload-", ".zip")
            try {
                HumanMoveCorpusArtifactCodec.copyBounded(input, archive, limits.maxArchiveBytes)
                verify(archive, expectedDigest, limits)
            } finally {
                Files.deleteIfExists(archive)
            }
        }

    fun verify(
        archive: Path,
        expectedDigest: String,
        limits: Limits,
    ): VerifiedCorpusArtifact {
        if (!expectedDigest.matches(Regex("[0-9a-f]{64}"))) {
            throw CorpusArtifactInvalidException("expectedDigest must be a 64-character lowercase hex SHA-256 value")
        }
        if (Files.size(archive) > limits.maxArchiveBytes) {
            throw CorpusArtifactInvalidException("Archive exceeds the configured maximum of ${limits.maxArchiveBytes} bytes")
        }

        val entries = HumanMoveCorpusArtifactCodec.spoolZip(archive, archive.parent, limits.maxArchiveBytes, limits.maxExpandedEntryBytes)
        try {
            if (entries.sumOf { it.size } > limits.maxExpandedEntryBytes) {
                throw CorpusArtifactInvalidException(
                    "Expanded archive entries exceed the configured maximum of ${limits.maxExpandedEntryBytes} bytes",
                )
            }
            if (!HumanMoveCorpusArtifactCodec.canonicalZipMatches(archive, entries, archive.parent, limits.maxArchiveBytes)) {
                throw CorpusArtifactInvalidException("Archive is not the canonical v1 STORED ZIP (entry metadata or trailing bytes differ)")
            }
            val manifestFile = entries[2]
            if (manifestFile.size > limits.maxRecordBytes) {
                throw CorpusArtifactInvalidException("Manifest exceeds the configured maximum record size")
            }
            val manifestBytes = Files.readAllBytes(manifestFile.path)
            val manifest = decodeManifest(HumanMoveCorpusArtifactCodec.readTree(manifestBytes))
            if (manifest.formatVersion != SUPPORTED_FORMAT_VERSION) {
                throw CorpusArtifactInvalidException("Unsupported artifact formatVersion ${manifest.formatVersion}")
            }
            if (manifest.snapshotKind != SNAPSHOT_KIND) {
                throw CorpusArtifactInvalidException("Unsupported artifact snapshotKind '${manifest.snapshotKind}'")
            }
            if (manifest.coveredPrefix !in 1..limits.maxGames ||
                manifest.gameCount !in 0..limits.maxGames ||
                manifest.observationCount !in 0..limits.maxObservations
            ) {
                throw CorpusArtifactInvalidException("Manifest record counts exceed the configured limits")
            }
            if (manifest.gamesSha256 != entries[0].sha256 || manifest.observationsSha256 != entries[1].sha256) {
                throw CorpusArtifactInvalidException("Manifest evidence hashes do not match the archived entries")
            }
            if (manifest.gameBytes != entries[0].size || manifest.observationBytes != entries[1].size) {
                throw CorpusArtifactInvalidException("Manifest evidence lengths do not match the archived entries")
            }
            verifyRunMetadataConsistency(manifest)

            val digest = HumanMoveCorpusArtifactCodec.digestFiles(entries)
            if (digest != expectedDigest) {
                throw CorpusArtifactConflict("Recomputed content digest does not match the supplied expected digest")
            }

            val expectedDistinct = IntArray(manifest.coveredPrefix + 1)
            val expectedTotals = LongArray(manifest.coveredPrefix + 1)
            var gameCount = 0
            readRecords(entries[0].path, limits.maxRecordBytes) { node ->
                val game = decodeGame(node)
                gameCount++
                if (gameCount > limits.maxGames || game.qualifyingOrdinal != gameCount) {
                    throw CorpusArtifactInvalidException("games.ndjson must be exactly ordinal-sorted 1..${manifest.coveredPrefix}")
                }
                verifyPgnHash(game)
                expectedDistinct[game.qualifyingOrdinal] = game.distinctMoveCount
                expectedTotals[game.qualifyingOrdinal] = game.observationTotal.toLong()
            }
            if (gameCount != manifest.gameCount || gameCount != manifest.coveredPrefix) {
                throw CorpusArtifactInvalidException("Game count does not match the manifest's covered prefix")
            }

            val observationCounts = IntArray(manifest.coveredPrefix + 1)
            val observationTotals = LongArray(manifest.coveredPrefix + 1)
            var observationCount = 0
            var previous: HumanMoveCorpusObservationRecord? = null
            readRecords(entries[1].path, limits.maxRecordBytes) { node ->
                val observation = decodeObservation(node)
                observationCount++
                if (observationCount > limits.maxObservations) {
                    throw CorpusArtifactInvalidException("Archive declares more observations than the configured maximum")
                }
                if (observation.qualifyingOrdinal !in 1..gameCount) {
                    throw CorpusArtifactInvalidException("Observation refers to an ordinal outside the covered game prefix")
                }
                if (previous?.let { compareObservations(it, observation) >= 0 } == true) {
                    throw CorpusArtifactInvalidException("observations.ndjson must be strictly ordinal/hash/move-sorted")
                }
                if (GameParserService.generateHash(observation.positionFen) != observation.positionHash) {
                    throw CorpusArtifactInvalidException(
                        "Observation positionHash ${observation.positionHash} does not match its declared positionFen",
                    )
                }
                observationCounts[observation.qualifyingOrdinal]++
                observationTotals[observation.qualifyingOrdinal] += observation.observationCount
                previous = observation
            }
            if (observationCount != manifest.observationCount) {
                throw CorpusArtifactInvalidException("Observation count does not match the manifest's declared count")
            }
            for (ordinal in 1..gameCount) {
                if (observationCounts[ordinal] != expectedDistinct[ordinal] ||
                    observationTotals[ordinal] != expectedTotals[ordinal]
                ) {
                    throw CorpusArtifactInvalidException(
                        "Game $ordinal observationTotal/distinctMoveCount does not match its observations",
                    )
                }
            }

            return VerifiedCorpusArtifact(
                digest = digest,
                manifest = manifest,
                manifestJson = manifestBytes.toString(Charsets.UTF_8),
            )
        } finally {
            entries.forEach { Files.deleteIfExists(it.path) }
        }
    }

    fun forEachGame(
        archive: Path,
        maxRecordBytes: Int,
        consume: (HumanMoveCorpusGameRecord) -> Unit,
    ) {
        forEachArchiveRecord(archive, "games.ndjson", maxRecordBytes) { node -> consume(decodeGame(node)) }
    }

    fun forEachObservation(
        archive: Path,
        maxRecordBytes: Int,
        consume: (HumanMoveCorpusObservationRecord) -> Unit,
    ) {
        forEachArchiveRecord(archive, "observations.ndjson", maxRecordBytes) { node -> consume(decodeObservation(node)) }
    }

    private fun decodeManifest(node: JsonNode): HumanMoveCorpusArtifactManifest =
        try {
            mapper.treeToValue(node, HumanMoveCorpusArtifactManifest::class.java)
        } catch (e: Exception) {
            throw CorpusArtifactInvalidException("Malformed manifest: ${e.message}")
        }

    private fun decodeGame(node: JsonNode): HumanMoveCorpusGameRecord =
        try {
            mapper.treeToValue(node, HumanMoveCorpusGameRecord::class.java)
        } catch (e: Exception) {
            throw CorpusArtifactInvalidException("Malformed game record: ${e.message}")
        }

    private fun decodeObservation(node: JsonNode): HumanMoveCorpusObservationRecord =
        try {
            mapper.treeToValue(node, HumanMoveCorpusObservationRecord::class.java)
        } catch (e: Exception) {
            throw CorpusArtifactInvalidException("Malformed observation record: ${e.message}")
        }

    private fun verifyPgnHash(game: HumanMoveCorpusGameRecord) {
        val recomputed =
            MessageDigest.getInstance("SHA-256").digest(game.pgn.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        if (recomputed != game.pgnSha256) {
            throw CorpusArtifactInvalidException("Game ${game.qualifyingOrdinal} pgnSha256 does not match its pgn")
        }
    }

    private fun compareObservations(
        left: HumanMoveCorpusObservationRecord,
        right: HumanMoveCorpusObservationRecord,
    ): Int =
        compareValuesBy(
            left,
            right,
            { it.qualifyingOrdinal },
            { it.positionHash },
            { it.movePlayed },
        )

    private fun forEachArchiveRecord(
        path: Path,
        name: String,
        maxRecordBytes: Int,
        consume: (JsonNode) -> Unit,
    ) {
        var found = false
        ZipInputStream(Files.newInputStream(path).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) {
                    readRecords(zip as InputStream, maxRecordBytes, consume)
                    zip.closeEntry()
                    found = true
                    break
                }
                zip.closeEntry()
            }
        }
        if (!found) {
            throw CorpusArtifactInvalidException("Missing archive entry '$name'")
        }
    }

    private fun readRecords(
        path: Path,
        maxRecordBytes: Int,
        consume: (JsonNode) -> Unit,
    ) {
        Files.newInputStream(path).buffered().use { input -> readRecords(input, maxRecordBytes, consume) }
    }

    private fun readRecords(
        input: InputStream,
        maxRecordBytes: Int,
        consume: (JsonNode) -> Unit,
    ) {
        val record = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            for (index in 0 until count) {
                val value = buffer[index].toInt() and 0xff
                if (value == '\n'.code) {
                    if (record.size() == 0) {
                        throw CorpusArtifactInvalidException("NDJSON contains an empty record")
                    }
                    val bytes = record.toByteArray()
                    val text =
                        try {
                            Charsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes))
                                .toString()
                        } catch (e: Exception) {
                            throw CorpusArtifactInvalidException("NDJSON contains malformed UTF-8")
                        }
                    if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
                        throw CorpusArtifactInvalidException("NDJSON must be valid UTF-8")
                    }
                    consume(HumanMoveCorpusArtifactCodec.readTree(bytes))
                    record.reset()
                } else {
                    if (record.size() >= maxRecordBytes) {
                        throw CorpusArtifactInvalidException("A record exceeds the configured maximum record size")
                    }
                    record.write(value)
                }
            }
        }
        if (record.size() > 0) {
            throw CorpusArtifactInvalidException("NDJSON must be LF-terminated")
        }
    }

    private inline fun <T> withTemporaryDirectory(block: (Path) -> T): T {
        val directory = Files.createTempDirectory("chessecho-corpus-verify-")
        try {
            return block(directory)
        } finally {
            Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    /** The top-level [manifest].ratingBand must not diverge from the immutable, hash-verified run request. */
    private fun verifyRunMetadataConsistency(manifest: HumanMoveCorpusArtifactManifest) {
        val metadata = manifest.sourceRunMetadata
        val requestJson =
            metadata.path("requestJson").asText(null)
                ?: throw CorpusArtifactInvalidException("Missing sourceRunMetadata.requestJson")
        val requestSha256 =
            metadata.path("requestSha256").asText(null)
                ?: throw CorpusArtifactInvalidException("Missing sourceRunMetadata.requestSha256")
        val recomputed =
            MessageDigest.getInstance(
                "SHA-256",
            ).digest(requestJson.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        if (recomputed != requestSha256) {
            throw CorpusArtifactInvalidException("sourceRunMetadata.requestJson does not match its declared requestSha256")
        }
        val requestNode = HumanMoveCorpusArtifactCodec.readTree(requestJson.toByteArray(Charsets.UTF_8))
        val requestBand = requestNode.path("ratingBand").asText(null)
        if (requestBand != null && requestBand != manifest.ratingBand) {
            throw CorpusArtifactInvalidException("manifest.ratingBand does not match the run request's ratingBand")
        }
    }
}
