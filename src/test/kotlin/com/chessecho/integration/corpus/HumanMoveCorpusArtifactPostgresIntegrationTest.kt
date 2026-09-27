package com.chessecho.integration.corpus

import com.chessecho.domain.HumanMoveCorpusRunStatus
import com.chessecho.dto.HumanMoveCorpusCheckpointRequest
import com.chessecho.dto.HumanMoveCorpusRunRequest
import com.chessecho.service.GameParserService
import com.chessecho.service.HumanMoveCorpusCandidate
import com.chessecho.service.HumanMoveCorpusCheckpointService
import com.chessecho.service.HumanMoveCorpusGameWriter
import com.chessecho.service.HumanMoveCorpusObservedMove
import com.chessecho.service.HumanMoveCorpusRunOutcome
import com.chessecho.service.HumanMoveCorpusSide
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class HumanMoveCorpusArtifactPostgresIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var writer: HumanMoveCorpusGameWriter

    @Autowired
    private lateinit var checkpoints: HumanMoveCorpusCheckpointService

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val base = "/api/admin/human-move-distribution/corpus-artifacts"
    private val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val hash = GameParserService.generateHash(fen)

    @BeforeEach
    fun clean() {
        if (jdbc.queryForObject("SELECT to_regclass('human_move_corpus_import') IS NOT NULL", Boolean::class.java) == true) {
            jdbc.execute("TRUNCATE human_move_corpus_import CASCADE")
        }
        jdbc.execute(
            "TRUNCATE human_move_corpus_observation, human_move_corpus_game, human_move_corpus_run, " +
                "human_move_distribution, position CASCADE",
        )
    }

    @AfterEach
    fun removeFailureInjection() {
        val observationTableExists =
            jdbc.queryForObject("SELECT to_regclass('human_move_corpus_imported_observation') IS NOT NULL", Boolean::class.java)
        if (observationTableExists == true) {
            jdbc.execute("DROP TRIGGER IF EXISTS corpus_import_failure ON human_move_corpus_imported_observation")
        }
        jdbc.execute("DROP FUNCTION IF EXISTS corpus_import_failure()")
    }

    private fun newRun(): UUID =
        writer.createRun(
            HumanMoveCorpusRunRequest(
                ratingBand = "1000-1200",
                seedPlayers = listOf("seed"),
                maxQualifyingGames = 10,
                sourceRevision = "source-test-revision",
            ),
        )

    private fun addGame(
        runId: UUID,
        ordinal: Int,
    ) {
        writer.commitGame(
            runId,
            HumanMoveCorpusCandidate(
                providerGameId = "https://example.invalid/game/$ordinal",
                traversedPlayer = "seed",
                opponent = "opponent",
                opponentSide = HumanMoveCorpusSide.BLACK,
                opponentRating = 1100,
                rules = "chess",
                timeClass = "rapid",
                bfsDepth = 0,
                pgn = "[Event \"Test $ordinal\"]\n\n1. e4 e5 *",
                observations = listOf(HumanMoveCorpusObservedMove(hash, fen, "e4", 3)),
            ),
        )
    }

    private fun export(
        runId: UUID,
        prefix: Int,
    ): Pair<String, ByteArray> {
        val result =
            mockMvc.post("$base/export") {
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("runId" to runId, "prefixN" to prefix))
            }.andReturn().response
        assertEquals(200, result.status, "an explicit committed prefix must export")
        val receipt = mapper.readTree(result.contentAsString)
        assertEquals(runId.toString(), receipt.path("sourceRunId").asText())
        assertEquals(prefix, receipt.path("coveredPrefix").asInt())
        val digest = receipt.path("contentDigest").asText()
        assertTrue(digest.matches(Regex("[0-9a-f]{64}")))

        val download = mockMvc.get("$base/$digest").andReturn().response
        assertEquals(200, download.status)
        assertTrue(download.contentAsByteArray.isNotEmpty())
        return digest to download.contentAsByteArray
    }

    private fun upload(
        operation: String,
        artifact: Pair<String, ByteArray>,
    ): Pair<Int, JsonNode?> {
        val response =
            mockMvc.multipart("$base/$operation") {
                file(MockMultipartFile("artifact", "corpus.zip", "application/zip", artifact.second))
                param("expectedDigest", artifact.first)
            }.andReturn().response
        val json = response.contentAsString.takeIf { it.isNotBlank() }?.let(mapper::readTree)
        return response.status to json
    }

    private fun import(artifact: Pair<String, ByteArray>): Pair<Int, JsonNode?> = upload("import", artifact)

    private fun importedGames(): Long = jdbc.queryForObject("SELECT COUNT(*) FROM human_move_corpus_imported_game", Long::class.java)!!

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                result[entry.name] = archive.readBytes()
            }
        }
        return result
    }

    private fun canonicalDigest(records: Map<String, ByteArray>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        listOf("games.ndjson", "observations.ndjson", "manifest.json").forEach { name ->
            val bytes = records.getValue(name)
            hash.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
            hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun mutatedArtifact(
        source: Map<String, ByteArray>,
        modify: (MutableMap<String, ByteArray>) -> Unit,
    ): Pair<String, ByteArray> {
        val records = source.toMutableMap()
        modify(records)
        val manifest = mapper.readTree(records.getValue("manifest.json")) as ObjectNode
        listOf("games.ndjson" to "games", "observations.ndjson" to "observations").forEach { (entry, prefix) ->
            val bytes = records.getValue(entry)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            manifest.put("${prefix}Sha256", hash)
            manifest.put(if (prefix == "games") "gameBytes" else "observationBytes", bytes.size)
        }
        records["manifest.json"] = mapper.writeValueAsBytes(manifest)
        val zipBytes =
            ByteArrayOutputStream().use { buffer ->
                ZipOutputStream(buffer).use { zip ->
                    listOf("games.ndjson", "observations.ndjson", "manifest.json").forEach { name ->
                        val data = records.getValue(name)
                        val entry = ZipEntry(name)
                        entry.method = ZipEntry.STORED
                        entry.time = 0
                        entry.size = data.size.toLong()
                        entry.compressedSize = data.size.toLong()
                        entry.crc = CRC32().apply { update(data) }.value
                        zip.putNextEntry(entry)
                        zip.write(data)
                        zip.closeEntry()
                    }
                }
                buffer.toByteArray()
            }
        return canonicalDigest(records) to zipBytes
    }

    @Test
    fun `exported evidence imports exactly and reproduces the checkpoint without provider access`() {
        val run = newRun()
        addGame(run, 1)
        addGame(run, 2)
        val source = checkpoints.calculate(run, HumanMoveCorpusCheckpointRequest(2, 5))
        val gameColumns =
            "qualifying_ordinal, provider_game_id, traversed_player, opponent, opponent_side, opponent_rating, " +
                "rules, time_class, bfs_depth, pgn, pgn_sha256, observation_total, distinct_move_count"
        val sourceGames =
            jdbc.queryForList(
                "SELECT $gameColumns FROM human_move_corpus_game WHERE run_id = ? ORDER BY qualifying_ordinal",
                run,
            )
        val contributionSql =
            "SELECT g.qualifying_ordinal, o.position_hash, p.fen, o.move_played, o.observation_count " +
                "FROM %s g JOIN %s o ON o.game_id = g.id JOIN position p ON p.id = o.position_id " +
                "ORDER BY g.qualifying_ordinal, o.position_hash, o.move_played"
        val sourceContributions =
            jdbc.queryForList(contributionSql.format("human_move_corpus_game", "human_move_corpus_observation"))
        val sourceRun =
            jdbc.queryForMap(
                "SELECT rating_band, algorithm_version, source_revision, request_sha256 FROM human_move_corpus_run WHERE id = ?",
                run,
            )
        val artifact = export(run, 2)
        clean()

        assertEquals(200, import(artifact).first)
        assertEquals(2, importedGames())
        val importedGames =
            jdbc.queryForList("SELECT $gameColumns FROM human_move_corpus_imported_game ORDER BY qualifying_ordinal")
        val importedContributions =
            jdbc.queryForList(contributionSql.format("human_move_corpus_imported_game", "human_move_corpus_imported_observation"))
        assertEquals(sourceGames, importedGames, "every portable game field must survive import")
        assertEquals(sourceContributions, importedContributions, "every contribution must remain with its original game")
        assertEquals(source.observationsRetained, importedContributions.sumOf { (it["observation_count"] as Number).toInt() })
        assertEquals(
            sourceRun,
            jdbc.queryForMap(
                "SELECT rating_band, algorithm_version, source_revision, request_sha256 " +
                    "FROM human_move_corpus_import WHERE source_run_id = ?",
                run,
            ),
        )
    }

    @Test
    fun `independent artifact verification is read only before import`() {
        val run = newRun()
        addGame(run, 1)
        val artifact = export(run, 1)
        clean()

        assertEquals(200, upload("verify", artifact).first)
        assertEquals(0, importedGames())
        val invalid = "0".repeat(64) to artifact.second
        val rejected = upload("verify", invalid).first
        assertTrue(rejected in 400..499 && rejected != 404)
        assertEquals(0, importedGames())
    }

    @Test
    fun `running prefixes are partial and only a completed terminal frontier is E6 eligible`() {
        val run = newRun()
        addGame(run, 1)
        val partial = export(run, 1)
        val partialManifest = mapper.readTree(entries(partial.second).getValue("manifest.json"))
        assertEquals("RUNNING", partialManifest.path("sourceStatus").asText())
        assertEquals(false, partialManifest.path("e6Eligible").asBoolean())

        addGame(run, 2)
        writer.finishRun(
            run,
            HumanMoveCorpusRunOutcome(
                status = HumanMoveCorpusRunStatus.COMPLETED,
                stopReason = "MAX_QUALIFYING_GAMES",
                rejectedGameCount = 0,
                archiveFetchFailureCount = 0,
                failureDetails = null,
            ),
        )
        val terminal = export(run, 2)
        val terminalManifest = mapper.readTree(entries(terminal.second).getValue("manifest.json"))
        assertEquals("COMPLETED", terminalManifest.path("sourceStatus").asText())
        assertEquals(2, terminalManifest.path("sourceFrontier").asInt())
        assertEquals(true, terminalManifest.path("e6Eligible").asBoolean())
        val earlier = export(run, 1)
        assertEquals(false, mapper.readTree(entries(earlier.second).getValue("manifest.json")).path("e6Eligible").asBoolean())
    }

    @Test
    fun `overlapping snapshots reuse source identity without duplicate imports`() {
        val run = newRun()
        addGame(run, 1)
        val one = export(run, 1)
        addGame(run, 2)
        val two = export(run, 2)
        assertNotEquals(one.first, two.first)
        clean()

        assertEquals(200, import(one).first)
        assertEquals(200, import(two).first)
        assertEquals(200, import(two).first)
        assertEquals(200, import(one).first)
        assertEquals(2, importedGames())
        assertEquals(
            1,
            jdbc.queryForObject("SELECT COUNT(*) FROM human_move_corpus_import", Int::class.java),
        )
        assertEquals(
            2,
            jdbc.queryForObject("SELECT COUNT(*) FROM human_move_corpus_artifact_snapshot", Int::class.java),
        )
    }

    @Test
    fun `a validly rehashed but divergent overlap cannot replace an imported game`() {
        val run = newRun()
        addGame(run, 1)
        val original = export(run, 1)
        val divergent =
            mutatedArtifact(entries(original.second)) { records ->
                val game = mapper.readTree(records.getValue("games.ndjson").toString(Charsets.UTF_8).trim()) as ObjectNode
                val changedPgn = "[Event \"Different game\"]\n\n1. d4 d5 *"
                val changedHash =
                    MessageDigest.getInstance("SHA-256")
                        .digest(changedPgn.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                game.put("pgn", changedPgn)
                game.put("pgnSha256", changedHash)
                records["games.ndjson"] = (mapper.writeValueAsString(game) + "\n").toByteArray(Charsets.UTF_8)
            }
        clean()

        assertEquals(200, import(original).first)
        val before =
            jdbc.queryForList("SELECT qualifying_ordinal, pgn_sha256 FROM human_move_corpus_imported_game ORDER BY qualifying_ordinal")
        val status = import(divergent).first
        assertEquals(409, status)
        assertEquals(
            before,
            jdbc.queryForList("SELECT qualifying_ordinal, pgn_sha256 FROM human_move_corpus_imported_game ORDER BY qualifying_ordinal"),
        )
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM human_move_corpus_artifact_snapshot", Int::class.java))
    }

    @Test
    fun `altered bytes and wrong expected digest fail before database mutation`() {
        val run = newRun()
        addGame(run, 1)
        val artifact = export(run, 1)
        clean()

        val changed = artifact.second.copyOf().also { it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 1).toByte() }
        val changedStatus = import(artifact.first to changed).first
        val wrongDigestStatus = import("0".repeat(64) to artifact.second).first
        assertTrue(changedStatus in 400..499 && changedStatus != 404)
        assertTrue(wrongDigestStatus in 400..499 && wrongDigestStatus != 404)
        assertEquals(0, importedGames())
    }

    @Test
    fun `truncated or malformed archive cannot publish any corpus rows`() {
        val run = newRun()
        addGame(run, 1)
        val artifact = export(run, 1)
        clean()
        val corruptArchives =
            listOf(
                artifact.first to artifact.second.copyOf(artifact.second.size / 2),
                artifact.first to "not a zip file".toByteArray(Charsets.UTF_8),
            )
        corruptArchives.forEach { corrupt ->
            val status = import(corrupt).first
            assertTrue(status in 400..499 && status != 404)
            assertEquals(0, importedGames())
        }
    }

    @Test
    fun `configured archive size limit rejects an oversized upload before database mutation`() {
        val oversized = ByteArray(100_001)
        val status = import("0".repeat(64) to oversized).first
        assertTrue(status in 400..499 && status != 404)
        assertEquals(0, importedGames())
    }

    @Test
    fun `import rejects incompatible target position mapping without partial corpus`() {
        val run = newRun()
        addGame(run, 1)
        val artifact = export(run, 1)
        clean()
        jdbc.update(
            "INSERT INTO position (id, hash, fen) VALUES (?, ?, ?)",
            UUID.randomUUID(),
            hash,
            "8/8/8/8/8/8/8/8 w - - 0 1",
        )

        val status = import(artifact).first
        assertTrue(status in 400..499 && status != 404)
        assertEquals(0, importedGames())
    }

    @Test
    fun `artifact contains complete ordered evidence and run provenance without local position identifiers`() {
        val run = newRun()
        addGame(run, 1)
        addGame(run, 2)
        val first = export(run, 1)
        val repeated = export(run, 1)
        assertEquals(first.first, repeated.first)
        assertTrue(first.second.contentEquals(repeated.second), "the same snapshot must export deterministic bytes")

        val entries = entries(first.second)
        assertEquals(listOf("games.ndjson", "observations.ndjson", "manifest.json"), entries.keys.toList())
        assertEquals(first.first, canonicalDigest(entries))
        val manifest = mapper.readTree(entries.getValue("manifest.json"))
        assertEquals(run.toString(), manifest.path("sourceRunId").asText())
        assertEquals(1, manifest.path("coveredPrefix").asInt())
        assertEquals(1, manifest.path("gameCount").asInt())
        assertEquals(1, manifest.path("observationCount").asInt())
        assertEquals("1000-1200", manifest.path("ratingBand").asText())
        assertEquals("source-test-revision", manifest.at("/sourceRunMetadata/sourceRevision").asText())

        val game = mapper.readTree(entries.getValue("games.ndjson").toString(Charsets.UTF_8).trim())
        val observation = mapper.readTree(entries.getValue("observations.ndjson").toString(Charsets.UTF_8).trim())
        assertEquals(1, game.path("qualifyingOrdinal").asInt())
        assertEquals(1, observation.path("qualifyingOrdinal").asInt())
        assertEquals(hash, observation.path("positionHash").asText())
        assertEquals(fen.substringBeforeLast(" 0 1"), observation.path("positionFen").asText())
        assertEquals(3, observation.path("observationCount").asInt())
        assertTrue(game.path("id").isMissingNode)
        assertTrue(observation.path("positionId").isMissingNode)
        val pgnHash =
            MessageDigest.getInstance("SHA-256")
                .digest(game.path("pgn").asText().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        assertEquals(pgnHash, game.path("pgnSha256").asText())
    }

    @Test
    fun `a recomputed digest cannot hide missing reordered or inconsistent evidence`() {
        val run = newRun()
        addGame(run, 1)
        addGame(run, 2)
        val source = entries(export(run, 2).second)
        clean()
        val games = source.getValue("games.ndjson").toString(Charsets.UTF_8).trimEnd().lines()
        val observations = source.getValue("observations.ndjson").toString(Charsets.UTF_8).trimEnd().lines()

        fun change(
            line: String,
            field: String,
            value: Any,
        ): String {
            val node = mapper.readTree(line) as ObjectNode
            when (value) {
                is Int -> node.put(field, value)
                is String -> node.put(field, value)
            }
            return mapper.writeValueAsString(node)
        }

        fun lines(vararg records: String): ByteArray = (records.joinToString("\n") + "\n").toByteArray()

        val mutations =
            listOf<(MutableMap<String, ByteArray>) -> Unit>(
                { it["games.ndjson"] = lines(games.first()) },
                { it["games.ndjson"] = lines(*games.reversed().toTypedArray()) },
                { it["games.ndjson"] = lines(games.first(), change(games.last(), "qualifyingOrdinal", 1)) },
                { it["games.ndjson"] = lines(change(games.first(), "pgn", "tampered PGN"), games.last()) },
                { it["games.ndjson"] = lines(change(games.first(), "observationTotal", 99), games.last()) },
                { it["observations.ndjson"] = lines(observations.first()) },
                { it["observations.ndjson"] = lines(observations.first(), observations.first()) },
                {
                    it["observations.ndjson"] =
                        lines(change(observations.first(), "observationCount", 99), observations.last())
                },
                {
                    it["observations.ndjson"] =
                        lines(change(observations.first(), "positionFen", "8/8/8/8/8/8/8/8 w - -"), observations.last())
                },
                {
                    val manifest = mapper.readTree(it.getValue("manifest.json")) as ObjectNode
                    it["manifest.json"] = mapper.writeValueAsBytes(manifest.put("ratingBand", "2000-2200"))
                },
                {
                    val manifest = mapper.readTree(it.getValue("manifest.json")) as ObjectNode
                    it["manifest.json"] = mapper.writeValueAsBytes(manifest.put("formatVersion", 999))
                },
            )
        mutations.forEachIndexed { index, mutation ->
            val status = import(mutatedArtifact(source, mutation)).first
            assertTrue(status in 400..499 && status != 404, "mutation $index must fail on its content")
            assertEquals(0, importedGames())
        }
    }

    @Test
    fun `export refuses a prefix beyond the committed frontier and a failed run`() {
        val run = newRun()
        addGame(run, 1)
        val tooLarge =
            mockMvc.post("$base/export") {
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("runId" to run, "prefixN" to 2))
            }.andReturn().response
        assertTrue(tooLarge.status in 400..499 && tooLarge.status != 404)
        writer.finishRun(
            run,
            HumanMoveCorpusRunOutcome(
                status = HumanMoveCorpusRunStatus.FAILED,
                stopReason = "TEST_FAILURE",
                rejectedGameCount = 0,
                archiveFetchFailureCount = 0,
                failureDetails = "test failure",
            ),
        )
        val failed =
            mockMvc.post("$base/export") {
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("runId" to run, "prefixN" to 1))
            }.andReturn().response
        assertTrue(failed.status in 400..499 && failed.status != 404)
    }

    @Test
    fun `interrupted import rolls back every row and retry reconstructs the exact prefix`() {
        val run = newRun()
        addGame(run, 1)
        addGame(run, 2)
        val artifact = export(run, 2)
        clean()
        jdbc.execute(
            """
            CREATE FUNCTION corpus_import_failure() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION 'injected import observation failure';
            END
            ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE TRIGGER corpus_import_failure BEFORE INSERT ON human_move_corpus_imported_observation " +
                "FOR EACH ROW EXECUTE FUNCTION corpus_import_failure()",
        )
        try {
            assertTrue(import(artifact).first in 400..599)
            assertEquals(0, importedGames())
        } finally {
            removeFailureInjection()
        }
        assertEquals(200, import(artifact).first)
        assertEquals(2, importedGames())
    }

    companion object {
        private val archiveRoot = Files.createTempDirectory("chessecho-corpus-426-artifact-")

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("chessecho.corpus.archive-root", archiveRoot::toString)
            registry.add("chessecho.corpus.max-archive-bytes", { "100000" })
        }

        @AfterAll
        @JvmStatic
        fun cleanupArchive() {
            Files.walk(archiveRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
