package com.chessecho.integration.corpus

import com.chessecho.domain.HumanMoveCorpusRunStatus
import com.chessecho.dto.HumanMoveBfsRequest
import com.chessecho.dto.HumanMoveCorpusCheckpointRequest
import com.chessecho.dto.HumanMoveCorpusCheckpointResponse
import com.chessecho.dto.HumanMoveCorpusRunRequest
import com.chessecho.dto.HumanMoveFinalizeRequest
import com.chessecho.humanmove.artifact.HumanMoveCorpusArtifactService
import com.chessecho.humanmove.artifact.HumanMoveCorpusExportRequest
import com.chessecho.service.ChessComClient
import com.chessecho.service.GameParserService
import com.chessecho.service.HumanMoveBfsService
import com.chessecho.service.HumanMoveCorpusCandidate
import com.chessecho.service.HumanMoveCorpusCheckpointService
import com.chessecho.service.HumanMoveCorpusCommitOutcome
import com.chessecho.service.HumanMoveCorpusContributionMismatchException
import com.chessecho.service.HumanMoveCorpusGameWriter
import com.chessecho.service.HumanMoveCorpusImportService
import com.chessecho.service.HumanMoveCorpusIntegrityException
import com.chessecho.service.HumanMoveCorpusMaterializationService
import com.chessecho.service.HumanMoveCorpusMaterializeRequest
import com.chessecho.service.HumanMoveCorpusObservedMove
import com.chessecho.service.HumanMoveCorpusOccurrenceService
import com.chessecho.service.HumanMoveCorpusProjectionFinalizationService
import com.chessecho.service.HumanMoveCorpusPurgeService
import com.chessecho.service.HumanMoveCorpusRunNotRunningException
import com.chessecho.service.HumanMoveCorpusRunOutcome
import com.chessecho.service.HumanMoveCorpusService
import com.chessecho.service.HumanMoveCorpusSide
import com.chessecho.service.HumanMoveDistributionFinalizationService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue #423 PostgreSQL evidence: V1 via Flyway, entities under
 * `ddl-auto: validate`, database-enforced immutability, per-game atomic
 * commits with contiguous ordinals, and non-destructive nested checkpoints.
 * Every row of the issue's failure-mode matrix maps to at least one test here
 * or to the unit and schema tests named in the approved plan.
 */
@SpringBootTest
@Testcontainers
class HumanMoveCorpusPostgresIntegrationTest {
    @Autowired
    private lateinit var corpusService: HumanMoveCorpusService

    @Autowired
    private lateinit var gameWriter: HumanMoveCorpusGameWriter

    @Autowired
    private lateinit var checkpointService: HumanMoveCorpusCheckpointService

    @Autowired
    private lateinit var artifactService: HumanMoveCorpusArtifactService

    @Autowired
    private lateinit var importService: HumanMoveCorpusImportService

    @Autowired
    private lateinit var materializationService: HumanMoveCorpusMaterializationService

    @Autowired
    private lateinit var purgeService: HumanMoveCorpusPurgeService

    @Autowired
    private lateinit var projectionFinalizationService: HumanMoveCorpusProjectionFinalizationService

    @Autowired
    private lateinit var occurrenceService: HumanMoveCorpusOccurrenceService

    @Autowired
    private lateinit var legacyBfsService: HumanMoveBfsService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var dataSource: DataSource

    @MockBean
    private lateinit var chessComClient: ChessComClient

    @SpyBean
    private lateinit var finalizationService: HumanMoveDistributionFinalizationService

    @BeforeEach
    fun setUp() = resetDatabase()

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS corpus_test_fail_observation ON human_move_corpus_observation")
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS corpus_test_fail_game ON human_move_corpus_game")
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS corpus_test_fail_observation()")
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS corpus_test_fail_game()")
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS corpus_test_attempts")
        resetDatabase()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun resetDatabase() =
        withTriggersDisabled {
            jdbcTemplate.execute(
                "TRUNCATE human_move_corpus_observation, human_move_corpus_game, human_move_corpus_run, " +
                    "human_move_distribution, human_move_bfs_seen_game, position CASCADE",
            )
        }

    /** Bypasses user and FK triggers for one transaction only, to inject corruption. */
    private fun withTriggersDisabled(block: () -> Unit) {
        TransactionTemplate(transactionManager).executeWithoutResult {
            jdbcTemplate.execute("SET LOCAL session_replication_role = replica")
            block()
        }
    }

    private fun runRequest(
        band: String = "1000-1200",
        maxQualifyingGames: Int? = 1000,
        maxDepth: Int? = null,
        seeds: List<String> = listOf("p1"),
        excluded: List<String> = emptyList(),
        maxPlayers: Int? = null,
        batchSize: Int = 5000,
    ) = HumanMoveCorpusRunRequest(
        ratingBand = band,
        seedPlayers = seeds,
        excludedPlayers = excluded,
        maxQualifyingGames = maxQualifyingGames,
        maxPlayers = maxPlayers,
        maxDepth = maxDepth,
        batchSize = batchSize,
        sourceRevision = "rev-423-test",
    )

    private fun newRun(band: String = "1000-1200"): UUID = gameWriter.createRun(runRequest(band = band))

    private fun candidate(
        url: String,
        vararg observations: Triple<String, String, Int>,
        opponentRating: Int = 1100,
    ) = HumanMoveCorpusCandidate(
        providerGameId = url,
        traversedPlayer = "p1",
        opponent = "opp",
        opponentSide = HumanMoveCorpusSide.BLACK,
        opponentRating = opponentRating,
        rules = "chess",
        timeClass = "rapid",
        bfsDepth = 0,
        pgn = "[Event \"Live Chess\"]\n\n1. e4 e5 * {$url}",
        observations = observations.map { (hash, move, count) -> HumanMoveCorpusObservedMove(hash, "fen-$hash", move, count) },
    )

    /** Deterministic per-game contributions over a shared position pool. */
    private fun syntheticCandidate(
        index: Int,
        opponentRating: Int = 1100,
    ): HumanMoveCorpusCandidate {
        val moves = listOf("e4", "d4", "c4", "Nf3")
        return candidate(
            "http://game-$index",
            Triple("pos-${index % 3}", moves[index % 4], 1 + index % 2),
            Triple("pos-${3 + (index + 1) % 5}", moves[(index + 2) % 4], 1),
            Triple("pos-shared", "e4", 1),
            opponentRating = opponentRating,
        )
    }

    /** Exact persisted child rows per provider game of one run. */
    private fun persistedContributions(runId: UUID): Map<String, Set<Triple<String, String, Int>>> =
        jdbcTemplate.queryForList(
            "SELECT g.provider_game_id, o.position_hash, o.move_played, o.observation_count " +
                "FROM human_move_corpus_game g JOIN human_move_corpus_observation o ON o.game_id = g.id " +
                "WHERE g.run_id = ?",
            runId,
        ).groupBy({ it["provider_game_id"].toString() }) {
            Triple(it["position_hash"].toString(), it["move_played"].toString(), (it["observation_count"] as Number).toInt())
        }.mapValues { it.value.toSet() }

    private fun contributionOf(candidate: HumanMoveCorpusCandidate) =
        candidate.observations.map { Triple(it.positionHash, it.movePlayed, it.observationCount) }.toSet()

    private fun commitAll(
        runId: UUID,
        candidates: List<HumanMoveCorpusCandidate>,
    ) = candidates.forEach {
        assertEquals(HumanMoveCorpusCommitOutcome.COMMITTED, gameWriter.commitGame(runId, it).outcome)
    }

    private fun checkpoint(
        runId: UUID,
        n: Int,
        min: Int = 1,
    ): HumanMoveCorpusCheckpointResponse =
        checkpointService.calculate(runId, HumanMoveCorpusCheckpointRequest(qualifyingGames = n, minObservations = min))

    private fun expectedRows(
        candidates: List<HumanMoveCorpusCandidate>,
        min: Int,
    ): List<Triple<String, String, Int>> {
        val aggregate =
            candidates
                .flatMap { it.observations }
                .groupBy { it.positionHash to it.movePlayed }
                .mapValues { (_, rows) -> rows.sumOf { it.observationCount } }
        val perPosition = aggregate.entries.groupBy({ it.key.first }, { it.value }).mapValues { it.value.sum() }
        return aggregate
            .filter { (key, _) -> perPosition.getValue(key.first) >= min }
            .map { (key, count) -> Triple(key.first, key.second, count) }
            .sortedWith(compareBy({ it.first }, { it.second }))
    }

    private fun rowsOf(response: HumanMoveCorpusCheckpointResponse) =
        response.rows.map { Triple(it.positionHash, it.movePlayed, it.observationCount) }

    private fun runRow(runId: UUID): Map<String, Any?> = jdbcTemplate.queryForMap("SELECT * FROM human_move_corpus_run WHERE id = ?", runId)

    private fun frontier(runId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT committed_frontier FROM human_move_corpus_run WHERE id = ?",
            Int::class.java,
            runId,
        )!!

    private fun ordinals(runId: UUID): List<Int> =
        jdbcTemplate.queryForList(
            "SELECT qualifying_ordinal FROM human_move_corpus_game WHERE run_id = ? ORDER BY qualifying_ordinal",
            Int::class.java,
            runId,
        )

    private fun count(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    private fun snapshot(): Map<String, List<Map<String, Any?>>> =
        listOf(
            "human_move_corpus_run",
            "human_move_corpus_game",
            "human_move_corpus_observation",
            "human_move_distribution",
            "human_move_bfs_seen_game",
            "position",
        ).associateWith { jdbcTemplate.queryForList("SELECT * FROM $it ORDER BY 1") }

    private fun gameId(
        runId: UUID,
        ordinal: Int,
    ): UUID =
        jdbcTemplate.queryForObject(
            "SELECT id FROM human_move_corpus_game WHERE run_id = ? AND qualifying_ordinal = ?",
            UUID::class.java,
            runId,
            ordinal,
        )!!

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `partial occurrence binding remains valid through finalization`() {
        val runId = newRun()
        commitAll(runId, adversarialLifecycleCandidates("partial-binding"))
        val exported = artifactService.export(HumanMoveCorpusExportRequest(runId, 1))
        val bytes = Files.readAllBytes(artifactService.archivePath(exported.contentDigest))
        importService.import(bytes, exported.contentDigest)
        val projection =
            materializationService.materialize(
                HumanMoveCorpusMaterializeRequest(
                    contentDigest = exported.contentDigest,
                    prefixN = 1,
                    minObservations = 1,
                ),
            )

        projectionFinalizationService.finalize(projection.projectionId)

        val binding = occurrenceService.verifyRequiredBinding(runId, exported.contentDigest, 1)

        assertEquals(runId, binding.sourceRunId)
        assertEquals(exported.contentDigest, binding.contentDigest)
        assertEquals(1, binding.coveredPrefix)
    }

    private data class FinalizedPopulation(
        val runId: UUID,
        val contentDigest: String,
        val artifactBytes: ByteArray,
        val projectionId: UUID,
        val canonicalLocationAnalysis: List<Map<String, Any?>>,
    )

    private fun finalizedAndPurgedPopulation(label: String): FinalizedPopulation {
        val runId = newRun()
        val candidates = adversarialLifecycleCandidates(label)
        commitAll(runId, candidates)
        val exported = artifactService.export(HumanMoveCorpusExportRequest(runId, 2))
        val bytes = Files.readAllBytes(artifactService.archivePath(exported.contentDigest))
        importService.import(bytes, exported.contentDigest)
        val projection =
            materializationService.materialize(
                HumanMoveCorpusMaterializeRequest(
                    contentDigest = exported.contentDigest,
                    prefixN = 2,
                    minObservations = 1,
                ),
            )
        projectionFinalizationService.finalize(projection.projectionId)
        val canonicalAnalysis = canonicalLocationAnalysis(runId)
        assertTrue(canonicalAnalysis.isNotEmpty(), "finalized population must expose location analysis before purge")
        assertEquals(14, canonicalAnalysis.size, "the two source games must retain all one-based pre-move occurrences")
        val firstGameId = candidates[0].providerGameId
        val secondGameId = candidates[1].providerGameId
        assertNotEquals(firstGameId, secondGameId, "the fixture must use distinct provider game identities")
        assertEquals(
            setOf(firstGameId, secondGameId),
            canonicalAnalysis.map { it["provider_game_id"].toString() }.toSet(),
            "the location analysis must retain both source games",
        )
        assertEquals(
            (1..9).toList(),
            canonicalAnalysis.filter { it["provider_game_id"] == firstGameId }
                .map { (it["pre_move_ply"] as Number).toInt() },
            "the first game's locations must be canonically ordered by one-based pre-move ply",
        )
        assertEquals(
            (1..5).toList(),
            canonicalAnalysis.filter { it["provider_game_id"] == secondGameId }
                .map { (it["pre_move_ply"] as Number).toInt() },
            "the second game's locations must be canonically ordered by one-based pre-move ply",
        )

        val initialPositionHash =
            GameParserService.generateHash("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        assertEquals(
            listOf(1, 5),
            canonicalAnalysis.filter {
                it["provider_game_id"] == firstGameId &&
                    it["position_hash"] == initialPositionHash &&
                    it["move_played"] == "Nf3"
            }.map { (it["pre_move_ply"] as Number).toInt() },
            "the same position and SAN must remain distinct at two plies in one game",
        )
        assertEquals(
            setOf(firstGameId, secondGameId),
            canonicalAnalysis.filter { it["position_hash"] == initialPositionHash }
                .map { it["provider_game_id"].toString() }.toSet(),
            "the same position must be retained across distinct source games",
        )
        val transposedPositionHash =
            GameParserService.generateHash(
                "rnbqkb1r/ppp1pppp/5n2/3p4/8/5NP1/PPPPPP1P/RNBQKB1R w KQkq d6 0 5",
            )
        assertEquals(
            mapOf(firstGameId to listOf(9), secondGameId to listOf(5)),
            canonicalAnalysis.filter {
                it["position_hash"] == transposedPositionHash && it["move_played"] == "Bg2"
            }.groupBy { it["provider_game_id"].toString() }
                .mapValues { (_, rows) -> rows.map { (it["pre_move_ply"] as Number).toInt() } },
            "different move orders must preserve the same transposed position at their canonical plies",
        )
        assertEquals(
            jdbcTemplate.queryForObject(
                "SELECT occurrence_count FROM human_move_corpus_occurrence_binding WHERE source_run_id = ?",
                Int::class.java,
                runId,
            ),
            canonicalAnalysis.size,
            "the canonical analysis must cover every occurrence bound at finalization",
        )
        purgeService.purge(runId, exported.contentDigest)
        assertEquals(
            0L,
            count("SELECT COUNT(*) FROM human_move_corpus_imported_game WHERE source_run_id = '$runId'"),
            "the raw #426 rows must actually be purged before recovery is exercised",
        )
        return FinalizedPopulation(runId, exported.contentDigest, bytes, projection.projectionId, canonicalAnalysis)
    }

    private fun adversarialLifecycleCandidates(label: String): List<HumanMoveCorpusCandidate> {
        val initialPosition = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val afterNf3 = "rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 1 1"
        val afterNf3Nf6 = "rnbqkb1r/pppppppp/5n2/8/8/5N2/PPPPPPPP/RNBQKB1R w KQkq - 2 2"
        val afterNg1 = "rnbqkb1r/pppppppp/5n2/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 1 2"
        val afterG3 = "rnbqkbnr/pppppppp/8/8/8/6P1/PPPPPP1P/RNBQKBNR b KQkq - 0 1"
        val afterG3Nf6 = "rnbqkb1r/pppppppp/5n2/8/8/6P1/PPPPPP1P/RNBQKBNR w KQkq - 1 2"
        val afterG3Nf6Nf3 = "rnbqkb1r/pppppppp/5n2/8/8/5NP1/PPPPPP1P/RNBQKB1R b KQkq - 2 2"
        val transposedPosition = "rnbqkb1r/ppp1pppp/5n2/3p4/8/5NP1/PPPPPP1P/RNBQKB1R w KQkq d6 0 5"
        val afterG3ForFirstGame = "rnbqkb1r/pppppppp/5n2/8/8/5NP1/PPPPPP1P/RNBQKB1R b KQkq - 0 4"

        return listOf(
            lifecycleCandidate(
                label = "$label-game-1",
                pgn = "1. Nf3 Nf6 2. Ng1 Ng8 3. Nf3 Nf6 4. g3 d5 5. Bg2 *",
                lifecycleObservation(initialPosition, "Nf3", 2),
                lifecycleObservation(afterNf3, "Nf6", 2),
                lifecycleObservation(afterNf3Nf6, "Ng1"),
                lifecycleObservation(afterNg1, "Ng8"),
                lifecycleObservation(afterNf3Nf6, "g3"),
                lifecycleObservation(afterG3ForFirstGame, "d5"),
                lifecycleObservation(transposedPosition, "Bg2"),
            ),
            lifecycleCandidate(
                label = "$label-game-2",
                pgn = "1. g3 Nf6 2. Nf3 d5 3. Bg2 *",
                lifecycleObservation(initialPosition, "g3"),
                lifecycleObservation(afterG3, "Nf6"),
                lifecycleObservation(afterG3Nf6, "Nf3"),
                lifecycleObservation(afterG3Nf6Nf3, "d5"),
                lifecycleObservation(transposedPosition, "Bg2"),
            ),
        )
    }

    private fun lifecycleObservation(
        fen: String,
        move: String,
        count: Int = 1,
    ): HumanMoveCorpusObservedMove {
        return HumanMoveCorpusObservedMove(GameParserService.generateHash(fen), fen, move, count)
    }

    private fun lifecycleCandidate(
        label: String,
        pgn: String,
        vararg observations: HumanMoveCorpusObservedMove,
    ) = HumanMoveCorpusCandidate(
        providerGameId = "http://$label",
        traversedPlayer = "p1",
        opponent = "opponent",
        opponentSide = HumanMoveCorpusSide.BLACK,
        opponentRating = 1100,
        rules = "chess",
        timeClass = "rapid",
        bfsDepth = 0,
        pgn = "[Event \"$label\"]\n\n$pgn",
        observations = observations.toList(),
    )

    private fun canonicalLocationAnalysis(runId: UUID): List<Map<String, Any?>> =
        jdbcTemplate.queryForList(
            """
            SELECT occurrence.source_run_id, occurrence.qualifying_ordinal, occurrence.provider_game_id,
                   occurrence.pre_move_ply, occurrence.position_hash, occurrence.move_played,
                   occurrence.content_digest, occurrence.covered_prefix
            FROM human_move_corpus_occurrence occurrence
            JOIN human_move_corpus_imported_game imported
              ON imported.source_run_id = occurrence.source_run_id
             AND imported.qualifying_ordinal = occurrence.qualifying_ordinal
             AND imported.provider_game_id = occurrence.provider_game_id
            WHERE occurrence.source_run_id = ?
            ORDER BY occurrence.source_run_id, occurrence.qualifying_ordinal, occurrence.provider_game_id,
                     occurrence.pre_move_ply, occurrence.position_hash, occurrence.move_played
            """.trimIndent(),
            runId,
        )

    private fun occurrenceRows(runId: UUID): List<Map<String, Any?>> =
        jdbcTemplate.queryForList(
            """
            SELECT id, source_run_id, qualifying_ordinal, provider_game_id, pre_move_ply,
                   move_played, position_hash, content_digest, covered_prefix
            FROM human_move_corpus_occurrence
            WHERE source_run_id = ?
            ORDER BY qualifying_ordinal, provider_game_id, pre_move_ply, position_hash, move_played, id
            """.trimIndent(),
            runId,
        )

    private fun occurrenceBinding(runId: UUID): Map<String, Any?> =
        jdbcTemplate.queryForMap(
            """
            SELECT source_run_id, content_digest, covered_prefix, occurrence_digest, occurrence_count, finalized_at
            FROM human_move_corpus_occurrence_binding
            WHERE source_run_id = ?
            """.trimIndent(),
            runId,
        )

    private val pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 2. Nf3 Nc6 1-0"

    private fun rapid(
        url: String,
        white: String,
        black: String,
    ): Map<String, Any> =
        mapOf(
            "url" to url,
            "rules" to "chess",
            "time_class" to "rapid",
            "white" to mapOf("username" to white, "rating" to 1300),
            "black" to mapOf("username" to black, "rating" to 1100),
            "pgn" to pgn,
        )

    private val archive = "https://api.chess.com/pub/player/p1/games/2024/01"

    /** p1 plays three in-band opponents; newest-first traversal order is a, b, c. */
    private fun stubThreeGames() {
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archive))
        whenever(chessComClient.fetchMonthlyGames(archive)).thenReturn(
            listOf(rapid("http://c", "p1", "p4"), rapid("http://b", "p1", "p3"), rapid("http://a", "p1", "p2")),
        )
        listOf("p2", "p3", "p4").forEach { whenever(chessComClient.fetchArchiveUrls(it)).thenReturn(emptyList()) }
    }

    private fun installTrigger(
        table: String,
        function: String,
        body: String,
    ) {
        jdbcTemplate.execute(
            "CREATE FUNCTION $function() RETURNS trigger AS \$\$ BEGIN $body RETURN NEW; END; \$\$ LANGUAGE plpgsql",
        )
        jdbcTemplate.execute("CREATE TRIGGER $function AFTER INSERT ON $table FOR EACH ROW EXECUTE FUNCTION $function()")
    }

    private fun installGameFailure(url: String) =
        installTrigger(
            "human_move_corpus_game",
            "corpus_test_fail_game",
            "IF NEW.provider_game_id = '$url' THEN RAISE EXCEPTION 'injected corpus game failure'; END IF;",
        )

    private fun installTransientGameFailure(
        url: String,
        failures: Int,
    ) {
        jdbcTemplate.execute("CREATE SEQUENCE corpus_test_attempts")
        installTrigger(
            "human_move_corpus_game",
            "corpus_test_fail_game",
            "IF NEW.provider_game_id = '$url' THEN " +
                "IF nextval('corpus_test_attempts') <= $failures THEN " +
                "RAISE EXCEPTION 'injected transient failure' USING ERRCODE = '40001'; END IF; END IF;",
        )
    }

    private fun installObservationFailure(move: String) =
        installTrigger(
            "human_move_corpus_observation",
            "corpus_test_fail_observation",
            "IF NEW.move_played = '$move' THEN RAISE EXCEPTION 'injected corpus observation failure'; END IF;",
        )

    private val gameInsert =
        "INSERT INTO human_move_corpus_game (id, run_id, qualifying_ordinal, provider_game_id, traversed_player, " +
            "opponent, opponent_side, opponent_rating, rules, time_class, bfs_depth, pgn, pgn_sha256, " +
            "observation_total, distinct_move_count, committed_at) VALUES (?, ?, ?, ?, 'p1', 'opp', 'BLACK', 1100, " +
            "'chess', 'rapid', 0, 'pgn', ?, 1, 1, now())"

    private val observationInsert =
        "INSERT INTO human_move_corpus_observation (id, game_id, position_id, position_hash, move_played, " +
            "observation_count) VALUES (?, ?, ?, 'pos-shared', 'Qh5', 1)"

    // ── Run identity and immutable configuration ───────────────────────────

    @Test
    fun `a run persists application-generated identity and immutable canonical configuration`() {
        val request =
            runRequest(
                maxQualifyingGames = 25,
                maxDepth = 3,
                seeds = listOf("Seed-B", "seed-a"),
                excluded = listOf("X"),
                maxPlayers = 40,
                batchSize = 7,
            )
        val runId = gameWriter.createRun(request)
        val other = gameWriter.createRun(request)
        assertNotEquals(runId, other, "every invocation is a distinct run")

        val row = runRow(runId)
        assertEquals("1000-1200", row["rating_band"])
        assertEquals("RUNNING", row["status"])
        assertEquals(0, row["committed_frontier"])
        assertEquals(25, row["max_qualifying_games"])
        assertEquals(3, row["max_depth"])
        assertEquals(40, row["max_players"])
        assertEquals(7, row["batch_size"])
        assertEquals(100, row["max_games_per_player"])
        assertEquals("rev-423-test", row["source_revision"])
        assertEquals(HumanMoveCorpusService.ALGORITHM_VERSION, row["algorithm_version"])
        val seeds = row["seed_players"].toString()
        assertTrue(seeds.indexOf("seed-b") in 0 until seeds.indexOf("seed-a"), "seed order preserved: $seeds")
        assertTrue(row["excluded_players"].toString().contains("x"))
        assertEquals(sha256(row["request_json"].toString()), row["request_sha256"])
        assertEquals(runRow(other)["request_sha256"], row["request_sha256"], "same configuration, same canonical hash")
    }

    @Test
    fun `database rejects configuration changes, run deletion, frontier decrease, and terminal-status exit`() {
        val runId = newRun()
        commitAll(runId, (1..2).map(::syntheticCandidate))

        listOf(
            "UPDATE human_move_corpus_run SET rating_band = '1400-1600' WHERE id = ?",
            "UPDATE human_move_corpus_run SET seed_players = '[\"other\"]' WHERE id = ?",
            "UPDATE human_move_corpus_run SET excluded_players = '[\"other\"]' WHERE id = ?",
            "UPDATE human_move_corpus_run SET max_qualifying_games = 1 WHERE id = ?",
            "UPDATE human_move_corpus_run SET max_depth = 9 WHERE id = ?",
            "UPDATE human_move_corpus_run SET max_players = 1 WHERE id = ?",
            "UPDATE human_move_corpus_run SET batch_size = 1 WHERE id = ?",
            "UPDATE human_move_corpus_run SET max_games_per_player = 1 WHERE id = ?",
            "UPDATE human_move_corpus_run SET source_revision = 'forged' WHERE id = ?",
            "UPDATE human_move_corpus_run SET algorithm_version = 'forged' WHERE id = ?",
            "UPDATE human_move_corpus_run SET request_json = '{}' WHERE id = ?",
            "UPDATE human_move_corpus_run SET request_sha256 = 'forged' WHERE id = ?",
            "UPDATE human_move_corpus_run SET created_at = now() - interval '1 day' WHERE id = ?",
            "UPDATE human_move_corpus_run SET committed_frontier = 1 WHERE id = ?",
            "DELETE FROM human_move_corpus_run WHERE id = ?",
        ).forEach { sql ->
            assertFailsWith<DataAccessException>(sql) { jdbcTemplate.update(sql, runId) }
        }
        assertEquals(2, frontier(runId))

        gameWriter.finishRun(
            runId,
            HumanMoveCorpusRunOutcome(
                status = HumanMoveCorpusRunStatus.COMPLETED,
                stopReason = "EMPTY_FRONTIER",
                rejectedGameCount = 0,
                archiveFetchFailureCount = 0,
                failureDetails = null,
            ),
        )
        listOf("RUNNING", "FAILED", "INCOMPLETE").forEach { status ->
            assertFailsWith<DataAccessException>(status) {
                jdbcTemplate.update("UPDATE human_move_corpus_run SET status = ? WHERE id = ?", status, runId)
            }
        }
        assertEquals("COMPLETED", runRow(runId)["status"])
        assertFailsWith<HumanMoveCorpusRunNotRunningException> { gameWriter.commitGame(runId, syntheticCandidate(3)) }
        assertEquals(2, frontier(runId))
    }

    @Test
    fun `database rejects game and observation UPDATE and DELETE`() {
        val runId = newRun()
        commitAll(runId, listOf(syntheticCandidate(1)))
        val game = gameId(runId, 1)

        listOf(
            "UPDATE human_move_corpus_game SET qualifying_ordinal = 2 WHERE id = ?",
            "UPDATE human_move_corpus_game SET pgn = 'forged' WHERE id = ?",
            "UPDATE human_move_corpus_game SET observation_total = 99 WHERE id = ?",
            "DELETE FROM human_move_corpus_game WHERE id = ?",
            "UPDATE human_move_corpus_observation SET observation_count = 99 WHERE game_id = ?",
            "UPDATE human_move_corpus_observation SET position_hash = 'forged' WHERE game_id = ?",
            "DELETE FROM human_move_corpus_observation WHERE game_id = ?",
        ).forEach { sql ->
            assertFailsWith<DataAccessException>(sql) { jdbcTemplate.update(sql, game) }
        }
        assertEquals(3L, count("SELECT count(*) FROM human_move_corpus_observation"))
    }

    @Test
    fun `late observation insert into an already committed game is rejected with triggers enabled`() {
        val runId = newRun()
        commitAll(runId, listOf(syntheticCandidate(1)))
        val game = gameId(runId, 1)
        val positionId = jdbcTemplate.queryForObject("SELECT id FROM position WHERE hash = 'pos-shared'", UUID::class.java)

        assertFailsWith<DataAccessException> { jdbcTemplate.update(observationInsert, UUID.randomUUID(), game, positionId) }

        assertEquals(3L, count("SELECT count(*) FROM human_move_corpus_observation"))
        assertEquals(1, checkpoint(runId, 1).requestedN)
    }

    @Test
    fun `direct game inserts must be the next contiguous ordinal of a RUNNING run`() {
        val runId = newRun()
        commitAll(runId, listOf(syntheticCandidate(1)))

        assertFailsWith<DataAccessException>("gap ordinal") {
            jdbcTemplate.update(gameInsert, UUID.randomUUID(), runId, 3, "http://gap", "0".repeat(64))
        }
        assertFailsWith<DataAccessException>("reused ordinal") {
            jdbcTemplate.update(gameInsert, UUID.randomUUID(), runId, 1, "http://reuse", "0".repeat(64))
        }
        assertEquals(listOf(1), ordinals(runId))
    }

    // ── Membership constraints (triggers bypassed to isolate the constraint) ─

    @Test
    fun `duplicate provider game and duplicate ordinal are rejected by constraints`() {
        val runId = newRun()
        commitAll(runId, listOf(syntheticCandidate(1)))

        assertFailsWith<DataAccessException>("duplicate provider game") {
            withTriggersDisabled {
                jdbcTemplate.update(gameInsert, UUID.randomUUID(), runId, 2, "http://game-1", "0".repeat(64))
            }
        }
        assertFailsWith<DataAccessException>("duplicate ordinal") {
            withTriggersDisabled {
                jdbcTemplate.update(gameInsert, UUID.randomUUID(), runId, 1, "http://other", "0".repeat(64))
            }
        }
        assertEquals(listOf(1), ordinals(runId))
    }

    @Test
    fun `writer resolves a verified identical duplicate as ALREADY_COMMITTED and fails closed on mismatch`() {
        val runId = newRun()
        val first = syntheticCandidate(1)
        commitAll(runId, listOf(first, syntheticCandidate(2)))
        val before = snapshot()

        val retry = gameWriter.commitGame(runId, first)
        assertEquals(HumanMoveCorpusCommitOutcome.ALREADY_COMMITTED, retry.outcome)
        assertEquals(1, retry.qualifyingOrdinal)
        assertEquals(before, snapshot(), "duplicate retry changes nothing and consumes no ordinal")

        val differentObservations = candidate("http://game-1", Triple("pos-0", "e4", 7))
        assertFailsWith<HumanMoveCorpusContributionMismatchException> { gameWriter.commitGame(runId, differentObservations) }
        val differentPgn = first.copy(pgn = first.pgn + " ")
        assertFailsWith<HumanMoveCorpusContributionMismatchException> { gameWriter.commitGame(runId, differentPgn) }
        assertEquals(before, snapshot())
    }

    // ── Atomic per-game persistence ─────────────────────────────────────────

    @Test
    fun `failure after the game row insert leaves no partial game and consumes no ordinal`() {
        val runId = newRun()
        commitAll(runId, listOf(syntheticCandidate(1)))
        installGameFailure("http://boom")

        assertFailsWith<Exception> { gameWriter.commitGame(runId, candidate("http://boom", Triple("pos-x", "e4", 1))) }

        assertEquals(1, frontier(runId))
        assertEquals(0L, count("SELECT count(*) FROM human_move_corpus_game WHERE provider_game_id = 'http://boom'"))
        assertEquals(3L, count("SELECT count(*) FROM human_move_corpus_observation"))
        assertEquals(2, gameWriter.commitGame(runId, syntheticCandidate(2)).qualifyingOrdinal, "ordinal not consumed")
        assertEquals(listOf(1, 2), ordinals(runId))
    }

    @Test
    fun `failure during observation insert rolls back the whole game`() {
        val runId = newRun()
        commitAll(runId, listOf(syntheticCandidate(1)))
        installObservationFailure("FAIL")

        assertFailsWith<Exception> {
            gameWriter.commitGame(
                runId,
                candidate(
                    "http://partial",
                    Triple("pos-new-1", "e4", 2),
                    Triple("pos-new-2", "FAIL", 1),
                    Triple("pos-new-3", "d4", 1),
                ),
            )
        }

        assertEquals(1, frontier(runId))
        assertEquals(0L, count("SELECT count(*) FROM human_move_corpus_game WHERE provider_game_id = 'http://partial'"))
        assertEquals(3L, count("SELECT count(*) FROM human_move_corpus_observation"), "no orphan observations remain")
        assertEquals(2, gameWriter.commitGame(runId, syntheticCandidate(2)).qualifyingOrdinal)
    }

    @Test
    fun `mid-run permanent failure keeps earlier games committed and marks the run FAILED`() {
        stubThreeGames()
        installGameFailure("http://b")

        val response = corpusService.runCorpus(runRequest(maxQualifyingGames = null, maxDepth = 0))

        assertEquals(HumanMoveCorpusRunStatus.FAILED, response.status)
        assertEquals("FAILED", runRow(response.runId)["status"])
        assertEquals(1, response.committedFrontier)
        assertTrue(response.failureDetails!!.contains("http://b"), response.failureDetails)
        assertEquals(listOf(1), ordinals(response.runId))
        assertEquals(
            0L,
            count("SELECT count(*) FROM human_move_corpus_game WHERE provider_game_id IN ('http://b', 'http://c')"),
        )
        assertEquals(HumanMoveCorpusRunStatus.FAILED, checkpoint(response.runId, 1).runStatus)
        assertFailsWith<IllegalArgumentException> { checkpoint(response.runId, 2) }
    }

    @Test
    fun `two transient failures then success yield exactly one contribution at the next contiguous ordinal`() {
        stubThreeGames()
        installTransientGameFailure("http://b", failures = 2)

        val response = corpusService.runCorpus(runRequest(maxQualifyingGames = null, maxDepth = 0))

        assertEquals(HumanMoveCorpusRunStatus.COMPLETED, response.status)
        assertEquals(listOf(1, 2, 3), ordinals(response.runId))
        assertEquals(
            listOf("http://a", "http://b", "http://c"),
            jdbcTemplate.queryForList(
                "SELECT provider_game_id FROM human_move_corpus_game WHERE run_id = ? ORDER BY qualifying_ordinal",
                String::class.java,
                response.runId,
            ),
        )
        assertEquals(3L, count("SELECT nextval('corpus_test_attempts')") - 1, "three attempts for game b")
    }

    @Test
    fun `exhausted transient retries mark the run FAILED without consuming an ordinal`() {
        stubThreeGames()
        installTransientGameFailure("http://b", failures = 1000)

        val response = corpusService.runCorpus(runRequest(maxQualifyingGames = null, maxDepth = 0))

        assertEquals(HumanMoveCorpusRunStatus.FAILED, response.status)
        assertEquals(listOf(1), ordinals(response.runId))
        assertEquals(1, frontier(response.runId))
        assertEquals(
            HumanMoveCorpusService.MAX_COMMIT_ATTEMPTS.toLong(),
            count("SELECT nextval('corpus_test_attempts')") - 1,
        )
    }

    // ── Lifecycle: fetch failure, crash/restart ─────────────────────────────

    @Test
    fun `fetch failure ends the run INCOMPLETE and its frontier stays checkpointable`() {
        stubThreeGames()
        whenever(chessComClient.fetchArchiveUrls("p3")).thenThrow(RuntimeException("upstream unavailable"))

        val response = corpusService.runCorpus(runRequest(maxQualifyingGames = null, maxDepth = 1))

        assertEquals(HumanMoveCorpusRunStatus.INCOMPLETE, response.status)
        assertEquals("INCOMPLETE", runRow(response.runId)["status"])
        assertEquals(1, response.archiveFetchFailureCount)
        assertTrue(response.failureDetails!!.contains("p3"))
        assertEquals(3, response.committedFrontier)
        assertEquals(HumanMoveCorpusRunStatus.INCOMPLETE, checkpoint(response.runId, 3).runStatus)
    }

    @Test
    fun `a crashed RUNNING run is never completed by a new invocation and allows checkpoints only through its frontier`() {
        val crashed = newRun()
        commitAll(crashed, (1..3).map(::syntheticCandidate))
        stubThreeGames()

        val restarted = corpusService.runCorpus(runRequest(maxQualifyingGames = null, maxDepth = 0))

        assertNotEquals(crashed, restarted.runId)
        assertEquals(HumanMoveCorpusRunStatus.COMPLETED, restarted.status)
        assertEquals("RUNNING", runRow(crashed)["status"])
        assertEquals(3, frontier(crashed))
        assertEquals(HumanMoveCorpusRunStatus.RUNNING, checkpoint(crashed, 3).runStatus)
        assertFailsWith<IllegalArgumentException> { checkpoint(crashed, 4) }
        assertEquals(HumanMoveCorpusRunStatus.RUNNING, corpusService.getRun(crashed).status)
        assertTrue(corpusService.listRuns().map { it.runId }.containsAll(listOf(crashed, restarted.runId)))
    }

    // ── Concurrency ─────────────────────────────────────────────────────────

    @Test
    fun `concurrent same-run commits yield unique contiguous ordinals with complete contributions, and runs stay isolated`() {
        val runA = newRun()
        val runB = newRun("1400-1600")
        val gate = 423_423L
        // Holds the first run-A writer inside its open game transaction until the
        // test observes other same-run writers blocked behind it.
        installTrigger(
            "human_move_corpus_game",
            "corpus_test_fail_game",
            "IF NEW.run_id = '$runA' AND NEW.provider_game_id = 'http://game-0' THEN " +
                "PERFORM pg_advisory_xact_lock($gate); END IF;",
        )
        val threads = 8
        val perThread = 5
        val ratingFor = mapOf(runA to 1100, runB to 1500)
        val pool = Executors.newFixedThreadPool(threads * 2)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        fun submitWorkers(run: UUID) =
            (0 until threads).map { t ->
                pool.submit {
                    try {
                        for (i in 0 until perThread) {
                            gameWriter.commitGame(run, syntheticCandidate(t * perThread + i, ratingFor.getValue(run)))
                        }
                    } catch (e: Throwable) {
                        errors += e
                    }
                }
            }

        try {
            dataSource.connection.use { holder ->
                holder.createStatement().use { it.execute("SELECT pg_advisory_lock($gate)") }
                try {
                    // Only run-A workers exist in this phase, so any backend blocked by the
                    // gated game-0 transaction is a second, overlapping run-A writer.
                    submitWorkers(runA)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                    var overlapped = false
                    while (!overlapped && System.nanoTime() < deadline) {
                        // Poll on the holder connection so a saturated pool cannot starve the observer.
                        holder.createStatement().use { statement ->
                            statement
                                .executeQuery(
                                    "SELECT EXISTS (SELECT 1 FROM pg_stat_activity gated " +
                                        "JOIN pg_stat_activity waiter ON gated.pid = ANY(pg_blocking_pids(waiter.pid)) " +
                                        "WHERE gated.wait_event = 'advisory' AND gated.pid <> pg_backend_pid() " +
                                        "AND waiter.pid <> pg_backend_pid())",
                                ).use { rs -> overlapped = rs.next() && rs.getBoolean(1) }
                        }
                        if (!overlapped) Thread.sleep(20)
                    }
                    assertTrue(overlapped, "a second run-A writer must block behind the in-flight run-A game")
                    // Run B must make complete progress while run A is still held mid-transaction.
                    submitWorkers(runB).forEach { it.get(60, TimeUnit.SECONDS) }
                    assertEquals(threads * perThread, frontier(runB), "run B is not serialized behind run A")
                } finally {
                    holder.createStatement().use { it.execute("SELECT pg_advisory_unlock($gate)") }
                }
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        }

        assertTrue(errors.isEmpty(), "unexpected errors: $errors")
        val total = threads * perThread
        listOf(runA, runB).forEach { run ->
            val all = (0 until total).map { syntheticCandidate(it, ratingFor.getValue(run)) }
            assertEquals((1..total).toList(), ordinals(run))
            assertEquals(total, frontier(run))
            assertEquals(all.associate { it.providerGameId to contributionOf(it) }, persistedContributions(run))
            assertEquals(expectedRows(all, 1), rowsOf(checkpoint(run, total)), "per-game contributions complete")
        }
    }

    // ── Persisted qualification and source provenance ───────────────────────

    @Test
    fun `persisted game rows carry exact qualification and source provenance for both opponent colours`() {
        val archive2 = "https://api.chess.com/pub/player/p1/games/2024/02"
        val blackPgn = "[Event \"Live Chess\"]\n[Result \"0-1\"]\n\n1. d4 d5 2. c4 e6 0-1"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archive, archive2))
        whenever(chessComClient.fetchMonthlyGames(archive2)).thenReturn(listOf(rapid("http://white-p1", "p1", "q1")))
        whenever(chessComClient.fetchMonthlyGames(archive)).thenReturn(
            listOf(
                mapOf(
                    "url" to "http://black-p1",
                    "rules" to "chess",
                    "time_class" to "rapid",
                    "white" to mapOf("username" to "q2", "rating" to 1150),
                    "black" to mapOf("username" to "p1", "rating" to 1700),
                    "pgn" to blackPgn,
                ),
            ),
        )
        whenever(chessComClient.fetchArchiveUrls("q1")).thenReturn(emptyList())
        whenever(chessComClient.fetchArchiveUrls("q2")).thenReturn(emptyList())

        val response = corpusService.runCorpus(runRequest(maxQualifyingGames = null, maxDepth = 1))

        assertEquals(HumanMoveCorpusRunStatus.COMPLETED, response.status)
        val rows =
            jdbcTemplate.queryForList(
                "SELECT * FROM human_move_corpus_game WHERE run_id = ? ORDER BY qualifying_ordinal",
                response.runId,
            )
        assertEquals(listOf("http://white-p1", "http://black-p1"), rows.map { it["provider_game_id"] })
        val (asWhite, asBlack) = rows
        listOf(
            Triple(asWhite, listOf("q1", "BLACK", 1100), pgn),
            Triple(asBlack, listOf("q2", "WHITE", 1150), blackPgn),
        ).forEach { (row, expected, expectedPgn) ->
            assertEquals("p1", row["traversed_player"])
            assertEquals(expected[0], row["opponent"])
            assertEquals(expected[1], row["opponent_side"])
            assertEquals(expected[2], row["opponent_rating"])
            assertEquals("chess", row["rules"])
            assertEquals("rapid", row["time_class"])
            assertEquals(0, row["bfs_depth"])
            assertEquals(expectedPgn, row["pgn"])
            assertEquals(sha256(row["pgn"].toString()), row["pgn_sha256"])
        }
        val contributions = persistedContributions(response.runId)
        assertEquals(setOf("e5", "Nc6"), contributions.getValue("http://white-p1").map { it.second }.toSet())
        assertEquals(setOf("d4", "c4"), contributions.getValue("http://black-p1").map { it.second }.toSet())
        rows.forEach { row ->
            val children = contributions.getValue(row["provider_game_id"].toString())
            assertEquals(children.sumOf { it.third }, (row["observation_total"] as Number).toInt())
            assertEquals(children.size, (row["distinct_move_count"] as Number).toInt())
        }
    }

    // ── Checkpoints ─────────────────────────────────────────────────────────

    @Test
    fun `nested prefixes contain exactly ordinals 1 to N, including non-round N`() {
        val runId = newRun()
        val candidates = (1..10).map(::syntheticCandidate)
        commitAll(runId, candidates)

        listOf(1, 2, 3, 5, 7, 10).forEach { n ->
            listOf(1, 2, 4).forEach { min ->
                val response = checkpoint(runId, n, min)
                val expected = expectedRows(candidates.take(n), min)
                val evaluated = candidates.take(n).flatMap { it.observations }.map { it.positionHash }.distinct().size
                assertEquals(expected, rowsOf(response), "prefix $n min $min")
                assertEquals(n, response.requestedN)
                assertEquals(10, response.committedFrontier)
                assertEquals(min, response.minObservations)
                assertEquals(expected.size, response.rowsRetained)
                assertEquals(expected.sumOf { it.third }, response.observationsRetained)
                assertEquals(evaluated, response.positionsEvaluated)
                assertEquals(expected.map { it.first }.distinct().size, response.positionsRetained)
                assertEquals(evaluated - response.positionsRetained, response.positionsRemoved)
                assertEquals("1000-1200", response.ratingBand)
                assertEquals(HumanMoveCorpusRunStatus.RUNNING, response.runStatus)
                assertEquals(64, response.distributionSha256.length)
            }
        }
        assertFailsWith<IllegalArgumentException> { checkpoint(runId, 11) }
        assertFailsWith<IllegalArgumentException> { checkpoint(runId, 0) }
        assertFailsWith<IllegalArgumentException> { checkpoint(runId, 5, min = 0) }
        assertFailsWith<NoSuchElementException> { checkpoint(UUID.randomUUID(), 1) }
    }

    @Test
    fun `earlier prefixes stay deterministic-equal while the running corpus is extended`() {
        val runId = newRun()
        commitAll(runId, (1..5).map(::syntheticCandidate))
        val before2 = checkpoint(runId, 2, 2)
        val before5 = checkpoint(runId, 5, 2)
        assertEquals(rowsOf(before5), rowsOf(checkpoint(runId, 5, 2)))
        assertEquals(before5.distributionSha256, checkpoint(runId, 5, 2).distributionSha256)
        assertFailsWith<IllegalArgumentException> { checkpoint(runId, 6) }

        commitAll(runId, (6..9).map(::syntheticCandidate))

        listOf(before2 to checkpoint(runId, 2, 2), before5 to checkpoint(runId, 5, 2)).forEach { (old, new) ->
            assertEquals(rowsOf(old), rowsOf(new))
            assertEquals(old.distributionSha256, new.distributionSha256)
            assertEquals(old.positionsEvaluated, new.positionsEvaluated)
            assertEquals(old.observationsRetained, new.observationsRetained)
            assertEquals(9, new.committedFrontier)
        }
        assertNotEquals(before5.distributionSha256, checkpoint(runId, 9, 2).distributionSha256)
    }

    @Test
    fun `checkpoint calculation is read-only and never invokes destructive finalization`() {
        val runId = newRun()
        commitAll(runId, (1..6).map(::syntheticCandidate))
        val legacyPosition = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO position (id, hash, fen) VALUES (?, 'legacy-pos', 'legacy-fen')", legacyPosition)
        jdbcTemplate.update(
            "INSERT INTO human_move_distribution (id, position_id, rating_band, move_played, observation_count) " +
                "VALUES (?, ?, '1000-1200', 'e4', 1)",
            UUID.randomUUID(),
            legacyPosition,
        )
        jdbcTemplate.update("INSERT INTO human_move_bfs_seen_game (game_url) VALUES ('http://legacy-seen')")
        val before = snapshot()

        repeat(3) { listOf(1, 3, 6).forEach { n -> checkpoint(runId, n, 5) } }

        assertEquals(before, snapshot())
        verify(finalizationService, never()).finalize(any())
    }

    @Test
    fun `retention matches the legacy minObservations finalization rule on the same aggregate`() {
        val runId = newRun()
        val candidates = (1..8).map(::syntheticCandidate)
        commitAll(runId, candidates)
        val min = 3
        val response = checkpoint(runId, 8, min)

        val legacyBand = "2200+"
        val positionIds =
            jdbcTemplate.queryForList("SELECT id, hash FROM position").associate { it["hash"].toString() to it["id"] as UUID }
        expectedRows(candidates, 1).forEach { (hash, move, observationCount) ->
            jdbcTemplate.update(
                "INSERT INTO human_move_distribution (id, position_id, rating_band, move_played, observation_count) " +
                    "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                positionIds.getValue(hash),
                legacyBand,
                move,
                observationCount,
            )
        }
        val finalized = finalizationService.finalize(HumanMoveFinalizeRequest(ratingBand = legacyBand, minObservations = min))
        val legacyRetained =
            jdbcTemplate.queryForList(
                "SELECT p.hash, d.move_played, d.observation_count FROM human_move_distribution d " +
                    "JOIN position p ON p.id = d.position_id WHERE d.rating_band = ? ORDER BY p.hash, d.move_played",
                legacyBand,
            ).map { Triple(it["hash"].toString(), it["move_played"].toString(), (it["observation_count"] as Number).toInt()) }

        assertEquals(legacyRetained, rowsOf(response))
        assertEquals(finalized.positionsEvaluated, response.positionsEvaluated)
        assertEquals(finalized.positionsRemoved, response.positionsRemoved)
        assertEquals(finalized.positionsRetained, response.positionsRetained)
    }

    @Test
    fun `cross-run and cross-band contributions never contaminate each other`() {
        val runA = newRun("1000-1200")
        val runB = newRun("1400-1600")
        gameWriter.commitGame(runA, candidate("http://shared-url", Triple("pos-common", "e4", 2)))
        gameWriter.commitGame(runB, candidate("http://shared-url", Triple("pos-common", "e4", 5), opponentRating = 1500))
        gameWriter.commitGame(runB, candidate("http://only-b", Triple("pos-common", "d4", 1), opponentRating = 1500))

        val a = checkpoint(runA, 1)
        val b = checkpoint(runB, 2)

        assertEquals(listOf(Triple("pos-common", "e4", 2)), rowsOf(a))
        assertEquals("1000-1200", a.ratingBand)
        assertEquals(listOf(Triple("pos-common", "d4", 1), Triple("pos-common", "e4", 5)), rowsOf(b))
        assertEquals("1400-1600", b.ratingBand)
        assertFailsWith<IllegalArgumentException> { checkpoint(runA, 2) }
    }

    // ── Fail-closed integrity validation (corruption injected with guards bypassed) ─

    @Test
    fun `checkpoint fails closed on an ordinal gap spanning the prefix`() {
        val runId = newRun()
        commitAll(runId, (1..3).map(::syntheticCandidate))
        withTriggersDisabled {
            val game = gameId(runId, 2)
            jdbcTemplate.update("DELETE FROM human_move_corpus_observation WHERE game_id = ?", game)
            jdbcTemplate.update("DELETE FROM human_move_corpus_game WHERE id = ?", game)
        }

        assertEquals(1, checkpoint(runId, 1).requestedN)
        assertFailsWith<HumanMoveCorpusIntegrityException> { checkpoint(runId, 2) }
        assertFailsWith<HumanMoveCorpusIntegrityException> { checkpoint(runId, 3) }
    }

    @Test
    fun `checkpoint fails closed on extra, missing, or reduced per-game observations`() {
        val firstObservationOf = "(SELECT id FROM human_move_corpus_observation WHERE game_id = ? ORDER BY id LIMIT 1)"
        val corruptions: List<(UUID, UUID) -> Unit> =
            listOf(
                { game, position -> jdbcTemplate.update(observationInsert, UUID.randomUUID(), game, position) },
                { game, _ -> jdbcTemplate.update("DELETE FROM human_move_corpus_observation WHERE id = $firstObservationOf", game) },
                { game, _ ->
                    jdbcTemplate.update(
                        "UPDATE human_move_corpus_observation SET observation_count = observation_count + 1 " +
                            "WHERE id = $firstObservationOf",
                        game,
                    )
                },
            )
        corruptions.forEachIndexed { index, corrupt ->
            resetDatabase()
            val runId = newRun()
            commitAll(runId, (1..3).map(::syntheticCandidate))
            val position = jdbcTemplate.queryForObject("SELECT id FROM position WHERE hash = 'pos-shared'", UUID::class.java)!!
            withTriggersDisabled { corrupt(gameId(runId, 2), position) }

            assertEquals(1, checkpoint(runId, 1).requestedN, "corruption $index lies outside prefix 1")
            assertFailsWith<HumanMoveCorpusIntegrityException>("corruption $index") { checkpoint(runId, 3) }
        }
    }

    @Test
    fun `checkpoint fails closed when a shared position hash diverges from the committed snapshot`() {
        val runId = newRun()
        commitAll(runId, (1..3).map(::syntheticCandidate))

        jdbcTemplate.update("UPDATE position SET hash = 'mutated' WHERE hash = 'pos-shared'")

        assertFailsWith<HumanMoveCorpusIntegrityException> { checkpoint(runId, 1) }
    }

    // ── Legacy isolation ────────────────────────────────────────────────────

    @Test
    fun `corpus ignores and never writes the legacy global seen-game and distribution tables`() {
        stubThreeGames()
        jdbcTemplate.update("INSERT INTO human_move_bfs_seen_game (game_url) VALUES ('http://b')")

        val corpus = corpusService.runCorpus(runRequest(maxQualifyingGames = null, maxDepth = 0))

        assertEquals(HumanMoveCorpusRunStatus.COMPLETED, corpus.status)
        assertEquals(3, corpus.committedFrontier, "globally seen game is still a corpus member")
        assertEquals(1L, count("SELECT count(*) FROM human_move_bfs_seen_game"))
        assertEquals(0L, count("SELECT count(*) FROM human_move_distribution"))

        val legacy =
            legacyBfsService.runBfs(HumanMoveBfsRequest(ratingBand = "1000-1200", seedPlayers = listOf("p1"), maxDepth = 0))

        assertEquals(2, legacy.qualifyingGames, "legacy still skips its own globally seen game")
        assertEquals(
            setOf("http://a", "http://b", "http://c"),
            jdbcTemplate.queryForList("SELECT game_url FROM human_move_bfs_seen_game", String::class.java).toSet(),
        )
        assertEquals(3, frontier(corpus.runId), "legacy ingestion does not touch the corpus")
    }

    @Test
    fun `committing a game retains every occurrence location while preserving aggregate checkpoint positions`() {
        val run = newRun()
        val initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val initialHash = GameParserService.generateHash(initialFen)
        val game = candidate("http://lossless-game", Triple(initialHash, "e4", 1))

        assertEquals(HumanMoveCorpusCommitOutcome.COMMITTED, gameWriter.commitGame(run, game).outcome)

        val occurrences =
            jdbcTemplate.queryForList(
                """
                SELECT source_run_id, qualifying_ordinal, provider_game_id, pre_move_ply,
                       move_played, position_hash
                FROM human_move_corpus_occurrence
                WHERE source_run_id = ?
                ORDER BY qualifying_ordinal, pre_move_ply, provider_game_id, move_played, position_hash
                """.trimIndent(),
                run,
            )
        assertTrue(occurrences.isNotEmpty(), "lossless occurrence evidence must be retained")
        assertEquals(
            occurrences.map { (it["pre_move_ply"] as Number).toInt() }.distinct().size,
            occurrences.size,
            "one source game must retain one row per location",
        )

        val aggregatePositions = checkpoint(run, 1).rows.map { it.positionHash }.toSet()
        assertEquals(
            aggregatePositions,
            occurrences.map { it["position_hash"].toString() }.toSet(),
            "occurrence evidence and aggregate checkpoint must describe the same positions",
        )

        val aggregateFromOccurrences =
            jdbcTemplate.queryForList(
                """
                SELECT position_hash, move_played, COUNT(*) AS occurrence_count
                FROM human_move_corpus_occurrence
                WHERE source_run_id = ? AND covered_prefix <= ?
                GROUP BY position_hash, move_played
                ORDER BY position_hash, move_played
                """.trimIndent(),
                run,
                1,
            ).map {
                Triple(
                    it["position_hash"].toString(),
                    it["move_played"].toString(),
                    (it["occurrence_count"] as Number).toInt(),
                )
            }
        assertEquals(rowsOf(checkpoint(run, 1)), aggregateFromOccurrences)
    }

    @Test
    fun `same-game retry compares the complete occurrence contribution and leaves conflicting retries atomic`() {
        val run = newRun()
        val original = candidate("http://retry-occurrence", Triple("retry-position", "e4", 2))
        assertEquals(HumanMoveCorpusCommitOutcome.COMMITTED, gameWriter.commitGame(run, original).outcome)

        val beforeGames = count("SELECT count(*) FROM human_move_corpus_game")
        val beforeOccurrences =
            count("SELECT count(*) FROM human_move_corpus_occurrence WHERE source_run_id = '$run'")

        assertEquals(HumanMoveCorpusCommitOutcome.ALREADY_COMMITTED, gameWriter.commitGame(run, original).outcome)
        assertFailsWith<HumanMoveCorpusContributionMismatchException> {
            gameWriter.commitGame(run, candidate("http://retry-occurrence", Triple("retry-position", "d4", 2)))
        }

        assertEquals(beforeGames, count("SELECT count(*) FROM human_move_corpus_game"))
        assertEquals(
            beforeOccurrences,
            count("SELECT count(*) FROM human_move_corpus_occurrence WHERE source_run_id = '$run'"),
            "a conflicting retry must not partially replace occurrence evidence",
        )
    }

    @Test
    fun `repeated same-position and SAN occurrences at different plies remain distinct lossless evidence`() {
        val run = newRun()
        gameWriter.commitGame(run, candidate("http://same-san", Triple("same-position", "e4", 2)))

        val rows =
            jdbcTemplate.queryForList(
                """
                SELECT pre_move_ply, position_hash, move_played
                FROM human_move_corpus_occurrence
                WHERE source_run_id = ? AND provider_game_id = ?
                ORDER BY pre_move_ply
                """.trimIndent(),
                run,
                "http://same-san",
            )
        assertTrue(rows.size >= 2, "the same position/SAN must be retained at each source ply")
        assertEquals(rows.size, rows.map { it["pre_move_ply"] }.toSet().size)
        assertEquals(setOf("same-position"), rows.map { it["position_hash"].toString() }.toSet())
        assertEquals(setOf("e4"), rows.map { it["move_played"].toString() }.toSet())
    }

    @Test
    fun `finalized issue 426 artifact binds one immutable occurrence prefix`() {
        val population = finalizedAndPurgedPopulation("binding")
        val binding = occurrenceBinding(population.runId)

        assertEquals(population.runId, binding["source_run_id"])
        assertEquals(population.contentDigest, binding["content_digest"])
        assertEquals(2, binding["covered_prefix"])
        assertTrue(binding["occurrence_digest"].toString().matches(Regex("[0-9a-f]{64}")))
        assertTrue((binding["occurrence_count"] as Number).toInt() > 0)
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

        val second = finalizedAndPurgedPopulation("recovery-substitute")
        purgeService.purge(first.runId, first.contentDigest)
        val otherPopulationRow =
            jdbcTemplate.queryForMap(
                """
                SELECT qualifying_ordinal, provider_game_id, pre_move_ply, position_hash, move_played,
                       content_digest, covered_prefix
                FROM human_move_corpus_occurrence
                WHERE source_run_id = ?
                ORDER BY qualifying_ordinal, provider_game_id, pre_move_ply
                LIMIT 1
                """.trimIndent(),
                second.runId,
            )
        val corruptions: List<Pair<String, (FinalizedPopulation) -> Unit>> =
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

    @Test
    fun `post-binding occurrence rows reject tampering and rebinding`() {
        val population = finalizedAndPurgedPopulation("immutable-binding")
        val alternate = finalizedAndPurgedPopulation("alternate-binding")
        val originalRows = occurrenceRows(population.runId)
        val originalBinding = occurrenceBinding(population.runId)
        val occurrenceId = originalRows.first()["id"]
        val rejectedMutations =
            listOf(
                "UPDATE human_move_corpus_occurrence SET source_run_id = ? WHERE id = ?" to
                    arrayOf<Any?>(alternate.runId, occurrenceId),
                "UPDATE human_move_corpus_occurrence SET qualifying_ordinal = qualifying_ordinal + 10 WHERE id = ?" to
                    arrayOf<Any?>(occurrenceId),
                "UPDATE human_move_corpus_occurrence SET provider_game_id = 'substituted' WHERE id = ?" to
                    arrayOf<Any?>(occurrenceId),
                "UPDATE human_move_corpus_occurrence SET pre_move_ply = pre_move_ply + 100 WHERE id = ?" to
                    arrayOf<Any?>(occurrenceId),
                "UPDATE human_move_corpus_occurrence SET move_played = 'd4' WHERE id = ?" to
                    arrayOf<Any?>(occurrenceId),
                "UPDATE human_move_corpus_occurrence SET position_hash = 'substituted' WHERE id = ?" to
                    arrayOf<Any?>(occurrenceId),
                "UPDATE human_move_corpus_occurrence SET content_digest = ? WHERE id = ?" to
                    arrayOf<Any?>("f".repeat(64), occurrenceId),
                "UPDATE human_move_corpus_occurrence SET covered_prefix = covered_prefix + 1 WHERE id = ?" to
                    arrayOf<Any?>(occurrenceId),
            )
        rejectedMutations.forEach { (sql, arguments) ->
            assertFailsWith<DataAccessException>("post-binding mutation must fail: $sql") {
                jdbcTemplate.update(sql, *arguments)
            }
            assertEquals(originalRows, occurrenceRows(population.runId), "rejected mutation must preserve exact original evidence")
            assertEquals(originalBinding, occurrenceBinding(population.runId), "rejected mutation must preserve the database binding")
        }

        assertFailsWith<DataAccessException>("a finalized occurrence must not be deleted for replacement") {
            jdbcTemplate.update("DELETE FROM human_move_corpus_occurrence WHERE id = ?", occurrenceId)
        }
        val row = originalRows.first()
        assertFailsWith<DataAccessException>("reinserting a finalized occurrence must not replace its existing row") {
            jdbcTemplate.update(
                """
                INSERT INTO human_move_corpus_occurrence
                    (id, source_run_id, qualifying_ordinal, provider_game_id, pre_move_ply, move_played,
                     position_hash, content_digest, covered_prefix)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                occurrenceId,
                row["source_run_id"],
                row["qualifying_ordinal"],
                row["provider_game_id"],
                row["pre_move_ply"],
                row["move_played"],
                row["position_hash"],
                row["content_digest"],
                row["covered_prefix"],
            )
        }
        assertEquals(originalRows, occurrenceRows(population.runId), "delete/reinsert attempts must leave exact rows unchanged")
        assertEquals(originalBinding, occurrenceBinding(population.runId), "delete/reinsert attempts must preserve its binding")

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "UPDATE human_move_corpus_occurrence_binding SET content_digest = ? WHERE source_run_id = ?",
                alternate.contentDigest,
                population.runId,
            )
        }
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "UPDATE human_move_corpus_occurrence_binding SET source_run_id = ? WHERE source_run_id = ?",
                alternate.runId,
                population.runId,
            )
        }
        assertEquals(originalRows, occurrenceRows(population.runId), "failed rebind must leave original rows intact")
        assertEquals(originalBinding, occurrenceBinding(population.runId), "failed rebind must preserve the database binding")
        assertEquals(alternate.runId, occurrenceBinding(alternate.runId)["source_run_id"])
    }

    companion object {
        private val archiveRoot = Files.createTempDirectory("chessecho-corpus-431-")

        @Container
        @JvmField
        val postgres: PostgreSQLContainer<Nothing> = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("chessecho.corpus.archive-root", archiveRoot::toString)
            // Headroom for 16 concurrent writers plus the lock holder in the concurrency test.
            registry.add("spring.datasource.hikari.maximum-pool-size") { "24" }
        }

        @AfterAll
        @JvmStatic
        fun removeArchives() {
            Files.walk(archiveRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
