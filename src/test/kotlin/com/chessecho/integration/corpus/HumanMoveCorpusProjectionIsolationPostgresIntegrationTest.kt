package com.chessecho.integration.corpus

import com.chessecho.dto.HumanMoveCorpusCheckpointRequest
import com.chessecho.dto.HumanMoveCorpusRunRequest
import com.chessecho.service.GameParserService
import com.chessecho.service.HumanMoveCorpusCandidate
import com.chessecho.service.HumanMoveCorpusCheckpointService
import com.chessecho.service.HumanMoveCorpusGameWriter
import com.chessecho.service.HumanMoveCorpusObservedMove
import com.chessecho.service.HumanMoveCorpusSide
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class HumanMoveCorpusProjectionIsolationPostgresIntegrationTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var writer: HumanMoveCorpusGameWriter

    @Autowired
    private lateinit var checkpoints: HumanMoveCorpusCheckpointService

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val base = "/api/admin/human-move-distribution"
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
    fun removeInjectedFailures() {
        if (jdbc.queryForObject("SELECT to_regclass('human_move_corpus_projection_row') IS NOT NULL", Boolean::class.java) == true) {
            jdbc.execute("DROP TRIGGER IF EXISTS corpus_projection_failure ON human_move_corpus_projection_row")
        }
        if (jdbc.queryForObject("SELECT to_regclass('human_move_corpus_imported_game') IS NOT NULL", Boolean::class.java) == true) {
            jdbc.execute("DROP TRIGGER IF EXISTS corpus_purge_failure ON human_move_corpus_imported_game")
        }
        jdbc.execute("DROP FUNCTION IF EXISTS corpus_test_failure()")
    }

    private fun injectFailure(
        table: String,
        trigger: String,
        action: String,
    ) {
        jdbc.execute(
            """
            CREATE OR REPLACE FUNCTION corpus_test_failure() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION 'injected projection lifecycle failure';
            END
            ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbc.execute("CREATE TRIGGER $trigger BEFORE $action ON $table FOR EACH ROW EXECUTE FUNCTION corpus_test_failure()")
    }

    private fun post(
        path: String,
        body: Any,
    ): JsonNode {
        val response =
            mvc.post(path) {
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }.andReturn().response
        assertEquals(200, response.status, "POST $path")
        return mapper.readTree(response.contentAsString)
    }

    private fun sourceRun(band: String = "1000-1200"): UUID {
        val run =
            writer.createRun(
                HumanMoveCorpusRunRequest(
                    ratingBand = band,
                    seedPlayers = listOf("seed"),
                    maxQualifyingGames = 10,
                    sourceRevision = "source-test-revision",
                ),
            )
        return run
    }

    private fun addGame(
        run: UUID,
        ordinal: Int,
        opponentRating: Int = 1100,
        move: String = "e4",
    ) {
        writer.commitGame(
            run,
            HumanMoveCorpusCandidate(
                providerGameId = "https://example.invalid/game/$ordinal",
                traversedPlayer = "seed",
                opponent = "opponent",
                opponentSide = HumanMoveCorpusSide.BLACK,
                opponentRating = opponentRating,
                rules = "chess",
                timeClass = "rapid",
                bfsDepth = 0,
                pgn = "[Event \"Test $ordinal\"]\n\n1. $move ${if (move == "e4") "e5" else "d5"} *",
                observations = listOf(HumanMoveCorpusObservedMove(hash, fen, move, 3)),
            ),
        )
    }

    private fun exported(
        run: UUID,
        prefix: Int,
    ): Pair<String, ByteArray> {
        val receipt = post("$base/corpus-artifacts/export", mapOf("runId" to run, "prefixN" to prefix))
        val digest = receipt.path("contentDigest").asText()
        val response = mvc.get("$base/corpus-artifacts/$digest").andReturn().response
        assertEquals(200, response.status)
        return digest to response.contentAsByteArray
    }

    private fun imported(artifact: Pair<String, ByteArray>) {
        val result =
            mvc.multipart("$base/corpus-artifacts/import") {
                file(MockMultipartFile("artifact", "corpus.zip", "application/zip", artifact.second))
                param("expectedDigest", artifact.first)
            }.andReturn().response
        assertEquals(200, result.status)
    }

    private fun materialize(
        digest: String,
        prefix: Int,
    ): UUID =
        UUID.fromString(
            post(
                "$base/corpus-projections/materialize",
                mapOf("contentDigest" to digest, "prefixN" to prefix, "minObservations" to 5),
            ).path("projectionId").asText(),
        )

    private fun rows(id: UUID): List<Map<String, Any>> =
        jdbc.queryForList(
            "SELECT position_hash, move_played, observation_count FROM human_move_corpus_projection_row " +
                "WHERE projection_id = ? ORDER BY position_hash, move_played",
            id,
        )

    @Test
    fun `two prefixes and legacy same-band rows survive isolated materialization and finalization`() {
        val run = sourceRun()
        addGame(run, 1)
        val one = exported(run, 1)
        addGame(run, 2)
        val sourceCheckpoint = checkpoints.calculate(run, HumanMoveCorpusCheckpointRequest(2, 5))
        val two = exported(run, 2)
        val unrelatedRun = sourceRun()
        addGame(unrelatedRun, 3)
        addGame(unrelatedRun, 4)
        val unrelated = exported(unrelatedRun, 2)
        clean()
        imported(one)
        imported(two)
        imported(unrelated)

        val legacyPosition = UUID.randomUUID()
        val legacyRow = UUID.randomUUID()
        jdbc.update("INSERT INTO position (id, hash, fen) VALUES (?, ?, ?)", legacyPosition, "legacy-hash", "legacy-fen")
        jdbc.update(
            "INSERT INTO human_move_distribution (id, position_id, rating_band, move_played, observation_count) " +
                "VALUES (?, ?, '1000-1200', 'd4', 2)",
            legacyRow,
            legacyPosition,
        )

        val smaller = materialize(one.first, 1)
        val larger = materialize(two.first, 2)
        val otherCorpus = materialize(unrelated.first, 2)
        assertNotEquals(smaller, larger)
        assertNotEquals(larger, otherCorpus)
        val smallerBefore = rows(smaller)
        assertEquals(listOf(3), smallerBefore.map { it["observation_count"] })
        val largerBefore = rows(larger)
        val otherBefore = rows(otherCorpus)
        assertEquals(listOf(6), largerBefore.map { it["observation_count"] })

        post("$base/corpus-projections/$smaller/finalize", emptyMap<String, String>())
        val finalizedLarger = post("$base/corpus-projections/$larger/finalize", emptyMap<String, String>())
        assertEquals(sourceCheckpoint.distributionSha256, finalizedLarger.path("distributionSha256").asText())
        assertEquals(emptyList(), rows(smaller), "position-sum below minObservations must be removed")
        assertEquals(smaller, materialize(one.first, 1), "repeat materialization must not accumulate")
        assertEquals(emptyList(), rows(smaller), "repeat materialization must not restore trimmed rows")
        assertEquals(largerBefore, rows(larger), "one prefix must not overwrite or trim another")
        assertEquals(otherBefore, rows(otherCorpus), "another same-band corpus must remain independent")
        assertEquals(
            2,
            jdbc.queryForObject("SELECT observation_count FROM human_move_distribution WHERE id = ?", Int::class.java, legacyRow),
        )
    }

    @Test
    fun `opposite cohort directions use their own per-player weakness denominators`() {
        val lowRun = sourceRun()
        addGame(lowRun, 1)
        addGame(lowRun, 2)
        val low = exported(lowRun, 2)
        val highRun = sourceRun("2000-2200")
        addGame(highRun, 3, opponentRating = 2100)
        addGame(highRun, 4, opponentRating = 2100)
        val high = exported(highRun, 2)
        clean()
        imported(low)
        imported(high)
        val lowProjection = materialize(low.first, 2)
        val highProjection = materialize(high.first, 2)
        post("$base/corpus-projections/$lowProjection/finalize", emptyMap<String, String>())
        post("$base/corpus-projections/$highProjection/finalize", emptyMap<String, String>())

        val absentPositionHash = GameParserService.generateHash("8/8/8/8/8/8/8/8 w - - 0 1")
        val lowWeaknesses = sortedMapOf("playerA" to listOf(hash, absentPositionHash), "playerB" to emptyList())
        val highWeaknesses = sortedMapOf("playerC" to listOf(hash))
        val result =
            post(
                "$base/corpus-projections/compare",
                mapOf(
                    "firstProjectionId" to lowProjection,
                    "secondProjectionId" to highProjection,
                    "firstEvaluation" to weaknessInput("1000-1200", lowWeaknesses),
                    "secondEvaluation" to weaknessInput("2000-2200", highWeaknesses),
                ),
            )
        assertEquals(2, result.at("/firstToSecond/players/playerA/weaknessCount").asInt())
        assertEquals(1, result.at("/firstToSecond/players/playerA/sharedCount").asInt())
        assertEquals(0.5, result.at("/firstToSecond/players/playerA/coverage").asDouble())
        assertEquals(0, result.at("/firstToSecond/players/playerB/weaknessCount").asInt())
        assertEquals(true, result.at("/firstToSecond/players/playerB/coverage").isNull)
        assertEquals(1, result.at("/secondToFirst/players/playerC/weaknessCount").asInt())
        assertEquals(1.0, result.at("/secondToFirst/players/playerC/coverage").asDouble())

        val mismatchedCohort =
            mvc.post("$base/corpus-projections/compare") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    mapper.writeValueAsString(
                        mapOf(
                            "firstProjectionId" to lowProjection,
                            "secondProjectionId" to highProjection,
                            "firstEvaluation" to weaknessInput("2000-2200", lowWeaknesses),
                            "secondEvaluation" to weaknessInput("2000-2200", highWeaknesses),
                        ),
                    )
            }.andReturn().response
        assertEquals(409, mismatchedCohort.status, "wrong evaluation cohort must be rejected")
    }

    @Test
    fun `purge is gated and an archived snapshot reconstructs raw evidence after purge`() {
        val run = sourceRun()
        addGame(run, 1)
        addGame(run, 2)
        val artifact = exported(run, 2)
        clean()
        imported(artifact)
        val premature =
            mvc.post("$base/corpus-artifacts/$run/purge") {
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("contentDigest" to artifact.first))
            }.andReturn().response
        assertEquals(409, premature.status)
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM human_move_corpus_imported_game", Int::class.java))

        val projection = materialize(artifact.first, 2)
        post("$base/corpus-projections/$projection/finalize", emptyMap<String, String>())
        post("$base/corpus-artifacts/$run/purge", mapOf("contentDigest" to artifact.first))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM human_move_corpus_imported_game", Int::class.java))
        assertEquals(200, mvc.get("$base/corpus-artifacts/${artifact.first}").andReturn().response.status)

        imported(artifact)
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM human_move_corpus_imported_game", Int::class.java))
        assertEquals(listOf(6), rows(projection).map { it["observation_count"] })
    }

    @Test
    fun `materialization failure publishes no partial projection and retry is safe`() {
        val run = sourceRun()
        addGame(run, 1)
        addGame(run, 2)
        val artifact = exported(run, 2)
        clean()
        imported(artifact)
        injectFailure("human_move_corpus_projection_row", "corpus_projection_failure", "INSERT")

        val failed =
            mvc.post("$base/corpus-projections/materialize") {
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("contentDigest" to artifact.first, "prefixN" to 2, "minObservations" to 5))
            }.andReturn().response
        assertEquals(500, failed.status)
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM human_move_corpus_projection", Int::class.java))
        removeInjectedFailures()

        val projection = materialize(artifact.first, 2)
        assertEquals(listOf(6), rows(projection).map { it["observation_count"] })
    }

    @Test
    fun `finalization failure leaves all projections and legacy rows unchanged until retry`() {
        val run = sourceRun()
        addGame(run, 1)
        val first = exported(run, 1)
        addGame(run, 2)
        val second = exported(run, 2)
        clean()
        imported(first)
        imported(second)
        val low = materialize(first.first, 1)
        val high = materialize(second.first, 2)
        val lowBefore = rows(low)
        val highBefore = rows(high)
        val legacyPosition = UUID.randomUUID()
        val legacyRow = UUID.randomUUID()
        jdbc.update("INSERT INTO position (id, hash, fen) VALUES (?, ?, ?)", legacyPosition, "legacy-finalize-hash", "legacy-fen")
        jdbc.update(
            "INSERT INTO human_move_distribution (id, position_id, rating_band, move_played, observation_count) " +
                "VALUES (?, ?, '1000-1200', 'd4', 2)",
            legacyRow,
            legacyPosition,
        )
        injectFailure("human_move_corpus_projection_row", "corpus_projection_failure", "DELETE")

        val failed =
            mvc.post("$base/corpus-projections/$low/finalize") {
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andReturn().response
        assertEquals(500, failed.status)
        assertEquals(lowBefore, rows(low))
        assertEquals(highBefore, rows(high))
        assertEquals(
            2,
            jdbc.queryForObject("SELECT observation_count FROM human_move_distribution WHERE id = ?", Int::class.java, legacyRow),
        )
        removeInjectedFailures()

        post("$base/corpus-projections/$low/finalize", emptyMap<String, String>())
        assertEquals(emptyList(), rows(low))
        assertEquals(highBefore, rows(high))
        assertEquals(
            2,
            jdbc.queryForObject("SELECT observation_count FROM human_move_distribution WHERE id = ?", Int::class.java, legacyRow),
        )
    }

    @Test
    fun `finalization retains all moves when their position sum reaches minObservations`() {
        val run = sourceRun()
        addGame(run, 1, move = "e4")
        addGame(run, 2, move = "d4")
        val checkpoint = checkpoints.calculate(run, HumanMoveCorpusCheckpointRequest(2, 5))
        val artifact = exported(run, 2)
        clean()
        imported(artifact)

        val projection = materialize(artifact.first, 2)
        val before = rows(projection)
        assertEquals(listOf("d4", "e4"), before.map { it["move_played"] })
        assertEquals(listOf(3, 3), before.map { it["observation_count"] })
        val result = post("$base/corpus-projections/$projection/finalize", emptyMap<String, String>())
        assertEquals(before, rows(projection), "both sub-threshold move rows belong to a retained position")
        assertEquals(checkpoint.distributionSha256, result.path("distributionSha256").asText())
    }

    @Test
    fun `interrupted purge preserves recoverable raw corpus and projection`() {
        val run = sourceRun()
        addGame(run, 1)
        addGame(run, 2)
        val artifact = exported(run, 2)
        clean()
        imported(artifact)
        val projection = materialize(artifact.first, 2)
        post("$base/corpus-projections/$projection/finalize", emptyMap<String, String>())
        injectFailure("human_move_corpus_imported_game", "corpus_purge_failure", "DELETE")

        val failed =
            mvc.post("$base/corpus-artifacts/$run/purge") {
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("contentDigest" to artifact.first))
            }.andReturn().response
        assertEquals(500, failed.status)
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM human_move_corpus_imported_game", Int::class.java))
        assertEquals(listOf(6), rows(projection).map { it["observation_count"] })
        removeInjectedFailures()
        post("$base/corpus-artifacts/$run/purge", mapOf("contentDigest" to artifact.first))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM human_move_corpus_imported_game", Int::class.java))
    }

    private fun weaknessInput(
        cohort: String,
        players: Map<String, List<String>>,
    ): Map<String, Any> {
        val evidence = sortedMapOf("cohort" to cohort, "players" to players, "threshold" to "0.30")
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(evidence))
                .joinToString("") { "%02x".format(it) }
        return evidence + mapOf("sourceDigest" to digest)
    }

    companion object {
        private val archiveRoot = Files.createTempDirectory("chessecho-corpus-426-projection-")

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
