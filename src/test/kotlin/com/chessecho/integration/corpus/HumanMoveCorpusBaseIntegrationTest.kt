package com.chessecho.integration.corpus

import com.chessecho.dto.HumanMoveCorpusCheckpointRequest
import com.chessecho.dto.HumanMoveCorpusCheckpointResponse
import com.chessecho.dto.HumanMoveCorpusRunRequest
import com.chessecho.humanmove.artifact.HumanMoveCorpusArtifactService
import com.chessecho.humanmove.artifact.HumanMoveCorpusExportRequest
import com.chessecho.service.GameParserService
import com.chessecho.service.HumanMoveBfsService
import com.chessecho.service.HumanMoveCorpusCandidate
import com.chessecho.service.HumanMoveCorpusCheckpointService
import com.chessecho.service.HumanMoveCorpusCommitOutcome
import com.chessecho.service.HumanMoveCorpusGameWriter
import com.chessecho.service.HumanMoveCorpusImportService
import com.chessecho.service.HumanMoveCorpusMaterializationService
import com.chessecho.service.HumanMoveCorpusMaterializeRequest
import com.chessecho.service.HumanMoveCorpusObservedMove
import com.chessecho.service.HumanMoveCorpusOccurrenceService
import com.chessecho.service.HumanMoveCorpusProjectionFinalizationService
import com.chessecho.service.HumanMoveCorpusPurgeService
import com.chessecho.service.HumanMoveCorpusService
import com.chessecho.service.HumanMoveCorpusSide
import com.chessecho.service.HumanMoveDistributionFinalizationService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * Shared infrastructure for all HumanMoveCorpus partitioned integration tests.
 * Provides Testcontainers Postgres, field injection, lifecycle hooks, and helpers
 * extracted from the original HumanMoveCorpusPostgresIntegrationTest (issue #423).
 */
@SpringBootTest
abstract class HumanMoveCorpusBaseIntegrationTest {
    @Autowired
    protected lateinit var corpusService: HumanMoveCorpusService

    @Autowired
    protected lateinit var gameWriter: HumanMoveCorpusGameWriter

    @Autowired
    protected lateinit var checkpointService: HumanMoveCorpusCheckpointService

    @Autowired
    protected lateinit var artifactService: HumanMoveCorpusArtifactService

    @Autowired
    protected lateinit var importService: HumanMoveCorpusImportService

    @Autowired
    protected lateinit var materializationService: HumanMoveCorpusMaterializationService

    @Autowired
    protected lateinit var purgeService: HumanMoveCorpusPurgeService

    @Autowired
    protected lateinit var projectionFinalizationService: HumanMoveCorpusProjectionFinalizationService

    @Autowired
    protected lateinit var occurrenceService: HumanMoveCorpusOccurrenceService

    @Autowired
    protected lateinit var legacyBfsService: HumanMoveBfsService

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    protected lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    protected lateinit var dataSource: DataSource

    @MockBean
    protected lateinit var chessComClient: com.chessecho.service.ChessComClient

    @SpyBean
    protected lateinit var finalizationService: HumanMoveDistributionFinalizationService

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

    protected fun resetDatabase() =
        withTriggersDisabled {
            jdbcTemplate.execute(
                "TRUNCATE human_move_corpus_observation, human_move_corpus_game, human_move_corpus_run, " +
                    "human_move_distribution, human_move_bfs_seen_game, position CASCADE",
            )
        }

    /** Bypasses user and FK triggers for one transaction only, to inject corruption. */
    protected fun withTriggersDisabled(block: () -> Unit) {
        TransactionTemplate(transactionManager).executeWithoutResult {
            jdbcTemplate.execute("SET LOCAL session_replication_role = replica")
            block()
        }
    }

    protected fun runRequest(
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

    protected fun newRun(band: String = "1000-1200"): UUID = gameWriter.createRun(runRequest(band = band))

    protected fun candidate(
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
    protected fun syntheticCandidate(
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
    protected fun persistedContributions(runId: UUID): Map<String, Set<Triple<String, String, Int>>> =
        jdbcTemplate.queryForList(
            "SELECT g.provider_game_id, o.position_hash, o.move_played, o.observation_count " +
                "FROM human_move_corpus_game g JOIN human_move_corpus_observation o ON o.game_id = g.id " +
                "WHERE g.run_id = ?",
            runId,
        ).groupBy({ it["provider_game_id"].toString() }) {
            Triple(
                it["position_hash"].toString(),
                it["move_played"].toString(),
                ((it["observation_count"] ?: error("missing observation_count")) as Number).toInt(),
            )
        }.mapValues { it.value.toSet() }

    protected fun contributionOf(candidate: HumanMoveCorpusCandidate) =
        candidate.observations.map { Triple(it.positionHash, it.movePlayed, it.observationCount) }.toSet()

    protected fun commitAll(
        runId: UUID,
        candidates: List<HumanMoveCorpusCandidate>,
    ) = candidates.forEach {
        assertEquals(HumanMoveCorpusCommitOutcome.COMMITTED, gameWriter.commitGame(runId, it).outcome)
    }

    protected fun checkpoint(
        runId: UUID,
        n: Int,
        min: Int = 1,
    ): HumanMoveCorpusCheckpointResponse =
        checkpointService.calculate(runId, HumanMoveCorpusCheckpointRequest(qualifyingGames = n, minObservations = min))

    protected fun expectedRows(
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

    protected fun rowsOf(response: HumanMoveCorpusCheckpointResponse) =
        response.rows.map { Triple(it.positionHash, it.movePlayed, it.observationCount) }

    protected fun runRow(runId: UUID): Map<String, Any?> =
        jdbcTemplate.queryForMap(
            "SELECT * FROM human_move_corpus_run WHERE id = ?",
            runId,
        )

    protected fun frontier(runId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT committed_frontier FROM human_move_corpus_run WHERE id = ?",
            Int::class.java,
            runId,
        )!!

    protected fun ordinals(runId: UUID): List<Int> =
        jdbcTemplate.queryForList(
            "SELECT qualifying_ordinal FROM human_move_corpus_game WHERE run_id = ? ORDER BY qualifying_ordinal",
            Int::class.java,
            runId,
        )

    protected fun count(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    protected fun snapshot(): Map<String, List<Map<String, Any?>>> =
        listOf(
            "human_move_corpus_run",
            "human_move_corpus_game",
            "human_move_corpus_observation",
            "human_move_distribution",
            "human_move_bfs_seen_game",
            "position",
        ).associateWith { jdbcTemplate.queryForList("SELECT * FROM $it ORDER BY 1") }

    protected fun gameId(
        runId: UUID,
        ordinal: Int,
    ): UUID =
        jdbcTemplate.queryForObject(
            "SELECT id FROM human_move_corpus_game WHERE run_id = ? AND qualifying_ordinal = ?",
            UUID::class.java,
            runId,
            ordinal,
        )!!

    protected fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    protected data class FinalizedPopulation(
        val runId: UUID,
        val contentDigest: String,
        val artifactBytes: ByteArray,
        val projectionId: UUID,
        val canonicalLocationAnalysis: List<Map<String, Any?>>,
    )

    protected fun finalizedAndPurgedPopulation(label: String): FinalizedPopulation {
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
        assertCanonicalLocationAnalysisBeforePurge(canonicalAnalysis, candidates, runId)
        purgeService.purge(runId, exported.contentDigest)
        assertEquals(
            0L,
            count("SELECT COUNT(*) FROM human_move_corpus_imported_game WHERE source_run_id = '$runId'"),
            "the raw #426 rows must actually be purged before recovery is exercised",
        )
        return FinalizedPopulation(runId, exported.contentDigest, bytes, projection.projectionId, canonicalAnalysis)
    }

    private fun assertCanonicalLocationAnalysisBeforePurge(
        canonicalAnalysis: List<Map<String, Any?>>,
        candidates: List<HumanMoveCorpusCandidate>,
        runId: UUID,
    ) {
        kotlin.test.assertTrue(canonicalAnalysis.isNotEmpty(), "finalized population must expose location analysis before purge")
        assertEquals(14, canonicalAnalysis.size, "the two source games must retain all one-based pre-move occurrences")
        val firstGameId = candidates[0].providerGameId
        val secondGameId = candidates[1].providerGameId
        kotlin.test.assertNotEquals(firstGameId, secondGameId, "the fixture must use distinct provider game identities")
        assertEquals(
            setOf(firstGameId, secondGameId),
            canonicalAnalysis.map { it["provider_game_id"].toString() }.toSet(),
            "the location analysis must retain both source games",
        )
        assertEquals(
            (1..9).toList(),
            canonicalAnalysis.filter { it["provider_game_id"] == firstGameId }
                .map { ((it["pre_move_ply"] ?: error("missing ply")) as Number).toInt() },
            "the first game's locations must be canonically ordered by one-based pre-move ply",
        )
        assertEquals(
            (1..5).toList(),
            canonicalAnalysis.filter { it["provider_game_id"] == secondGameId }
                .map { ((it["pre_move_ply"] ?: error("missing ply")) as Number).toInt() },
            "the second game's locations must be canonically ordered by one-based pre-move ply",
        )
        assertPositionTranspositionAndCoverage(canonicalAnalysis, firstGameId, secondGameId, runId)
    }

    private fun assertPositionTranspositionAndCoverage(
        canonicalAnalysis: List<Map<String, Any?>>,
        firstGameId: String,
        secondGameId: String,
        runId: UUID,
    ) {
        val initialPositionHash =
            GameParserService.generateHash("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        assertEquals(
            listOf(1, 5),
            canonicalAnalysis.filter {
                it["provider_game_id"] == firstGameId &&
                    it["position_hash"] == initialPositionHash &&
                    it["move_played"] == "Nf3"
            }.map { ((it["pre_move_ply"] ?: error("missing ply")) as Number).toInt() },
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
                .mapValues { (_, rows) -> rows.map { ((it["pre_move_ply"] ?: error("missing ply")) as Number).toInt() } },
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
    }

    protected fun adversarialLifecycleCandidates(label: String): List<HumanMoveCorpusCandidate> {
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

    protected fun lifecycleObservation(
        fen: String,
        move: String,
        count: Int = 1,
    ): HumanMoveCorpusObservedMove {
        return HumanMoveCorpusObservedMove(GameParserService.generateHash(fen), fen, move, count)
    }

    protected fun lifecycleCandidate(
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

    protected fun canonicalLocationAnalysis(runId: UUID): List<Map<String, Any?>> =
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

    protected fun occurrenceRows(runId: UUID): List<Map<String, Any?>> =
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

    protected fun occurrenceBinding(runId: UUID): Map<String, Any?> =
        jdbcTemplate.queryForMap(
            """
            SELECT source_run_id, content_digest, covered_prefix, occurrence_digest, occurrence_count, finalized_at
            FROM human_move_corpus_occurrence_binding
            WHERE source_run_id = ?
            """.trimIndent(),
            runId,
        )

    protected val pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 2. Nf3 Nc6 1-0"

    protected fun rapid(
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

    protected val archive = "https://api.chess.com/pub/player/p1/games/2024/01"

    /** p1 plays three in-band opponents; newest-first traversal order is a, b, c. */
    protected fun stubThreeGames() {
        org.mockito.kotlin.whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archive))
        org.mockito.kotlin.whenever(chessComClient.fetchMonthlyGames(archive)).thenReturn(
            listOf(rapid("http://c", "p1", "p4"), rapid("http://b", "p1", "p3"), rapid("http://a", "p1", "p2")),
        )
        listOf("p2", "p3", "p4").forEach { org.mockito.kotlin.whenever(chessComClient.fetchArchiveUrls(it)).thenReturn(emptyList()) }
    }

    protected fun installTrigger(
        table: String,
        function: String,
        body: String,
    ) {
        jdbcTemplate.execute(
            "CREATE FUNCTION $function() RETURNS trigger AS \$\$ BEGIN $body RETURN NEW; END; \$\$ LANGUAGE plpgsql",
        )
        jdbcTemplate.execute("CREATE TRIGGER $function AFTER INSERT ON $table FOR EACH ROW EXECUTE FUNCTION $function()")
    }

    protected fun installGameFailure(url: String) =
        installTrigger(
            "human_move_corpus_game",
            "corpus_test_fail_game",
            "IF NEW.provider_game_id = '$url' THEN RAISE EXCEPTION 'injected corpus game failure'; END IF;",
        )

    protected fun installTransientGameFailure(
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

    protected fun installObservationFailure(move: String) =
        installTrigger(
            "human_move_corpus_observation",
            "corpus_test_fail_observation",
            "IF NEW.move_played = '$move' THEN RAISE EXCEPTION 'injected corpus observation failure'; END IF;",
        )

    protected val gameInsert =
        "INSERT INTO human_move_corpus_game (id, run_id, qualifying_ordinal, provider_game_id, traversed_player, " +
            "opponent, opponent_side, opponent_rating, rules, time_class, bfs_depth, pgn, pgn_sha256, " +
            "observation_total, distinct_move_count, committed_at) VALUES (?, ?, ?, ?, 'p1', 'opp', 'BLACK', 1100, " +
            "'chess', 'rapid', 0, 'pgn', ?, 1, 1, now())"

    protected val observationInsert =
        "INSERT INTO human_move_corpus_observation (id, game_id, position_id, position_hash, move_played, " +
            "observation_count) VALUES (?, ?, ?, 'pos-shared', 'Qh5', 1)"

    companion object {
        val archiveRoot: java.nio.file.Path = Files.createTempDirectory("chessecho-corpus-431-")

        @JvmField
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
                start()
            }

        init {
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    runCatching {
                        Files.walk(archiveRoot).use { paths ->
                            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                        }
                    }
                },
            )
        }

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
    }
}
