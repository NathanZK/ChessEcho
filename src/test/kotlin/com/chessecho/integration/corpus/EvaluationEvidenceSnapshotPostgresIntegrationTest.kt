package com.chessecho.integration.corpus

import com.chessecho.domain.HumanMoveCorpusRunStatus
import com.chessecho.dto.HumanMoveCorpusRunRequest
import com.chessecho.humanmove.artifact.HumanMoveCorpusArtifactService
import com.chessecho.humanmove.artifact.HumanMoveCorpusExportRequest
import com.chessecho.service.EvaluationEvidenceConfiguration
import com.chessecho.service.EvaluationEvidenceIntegrityException
import com.chessecho.service.EvaluationEvidencePlayer
import com.chessecho.service.EvaluationEvidenceRow
import com.chessecho.service.EvaluationEvidenceSnapshot
import com.chessecho.service.EvaluationEvidenceSnapshotService
import com.chessecho.service.EvaluationReferencePopulation
import com.chessecho.service.GameParserService
import com.chessecho.service.HumanMoveCorpusCandidate
import com.chessecho.service.HumanMoveCorpusGameWriter
import com.chessecho.service.HumanMoveCorpusImportService
import com.chessecho.service.HumanMoveCorpusMaterializationService
import com.chessecho.service.HumanMoveCorpusMaterializeRequest
import com.chessecho.service.HumanMoveCorpusObservedMove
import com.chessecho.service.HumanMoveCorpusOccurrence
import com.chessecho.service.HumanMoveCorpusProjectionFinalizationService
import com.chessecho.service.HumanMoveCorpusRunOutcome
import com.chessecho.service.HumanMoveCorpusSide
import com.chessecho.service.ObjectiveOutcome
import com.chessecho.service.ObservedGameOutcome
import com.chessecho.service.PositionCoverage
import com.chessecho.service.ReferenceCoverageAnalysisService
import com.chessecho.service.RetainedEvaluationEvidenceService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Issue #430 PostgreSQL evidence: occurrence linkage against a real finalized
 * #431 binding, exact #426 identity mismatch rejection, deterministic
 * duplicate/conflict handling, retry idempotency, DB-enforced immutability,
 * and post-purge reconstruction using only retained #430 evidence plus its
 * verified #426/#431 bindings.
 */
@SpringBootTest
@Testcontainers
class EvaluationEvidenceSnapshotPostgresIntegrationTest {
    @Autowired
    private lateinit var gameWriter: HumanMoveCorpusGameWriter

    @Autowired
    private lateinit var artifactService: HumanMoveCorpusArtifactService

    @Autowired
    private lateinit var importService: HumanMoveCorpusImportService

    @Autowired
    private lateinit var materializationService: HumanMoveCorpusMaterializationService

    @Autowired
    private lateinit var projectionFinalizationService: HumanMoveCorpusProjectionFinalizationService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var evaluationEvidenceSnapshotService: EvaluationEvidenceSnapshotService

    @Autowired
    private lateinit var retainedEvaluationEvidenceService: RetainedEvaluationEvidenceService

    @BeforeEach
    fun setUp() = resetDatabase()

    @AfterEach
    fun tearDown() = resetDatabase()

    private fun resetDatabase() =
        jdbcTemplate.execute(
            "TRUNCATE evaluation_evidence_row, evaluation_evidence_snapshot, human_move_corpus_import CASCADE",
        ).also {
            jdbcTemplate.execute(
                "TRUNCATE human_move_corpus_observation, human_move_corpus_game, human_move_corpus_run, " +
                    "human_move_distribution, human_move_bfs_seen_game, position CASCADE",
            )
        }

    private fun newRun(): UUID =
        gameWriter.createRun(
            HumanMoveCorpusRunRequest(
                ratingBand = "1000-1200",
                seedPlayers = listOf("seed"),
                maxQualifyingGames = 10,
                sourceRevision = "source-430-test",
            ),
        )

    /** Commits one game with two distinct occurrence locations and finalizes/binds #431 evidence for it. */
    private fun finalizedRunWithOccurrences(): UUID {
        val runId = newRun()
        val firstFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val secondFen = "rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b KQkq - 1 1"
        val firstHash = GameParserService.generateHash(firstFen)
        val secondHash = GameParserService.generateHash(secondFen)
        gameWriter.commitGame(
            runId,
            HumanMoveCorpusCandidate(
                providerGameId = "http://game-430",
                traversedPlayer = "seed",
                opponent = "opponent",
                opponentSide = HumanMoveCorpusSide.BLACK,
                opponentRating = 1100,
                rules = "chess",
                timeClass = "rapid",
                bfsDepth = 0,
                pgn = "[Event \"issue-430\"]\n\n1. Nf3 *",
                observations =
                    listOf(
                        HumanMoveCorpusObservedMove(firstHash, firstFen, "Nf3", 1),
                        HumanMoveCorpusObservedMove(secondHash, secondFen, "Nf6", 1),
                    ),
            ),
        )
        gameWriter.finishRun(
            runId,
            HumanMoveCorpusRunOutcome(
                status = HumanMoveCorpusRunStatus.COMPLETED,
                stopReason = "MAX_QUALIFYING_GAMES",
                rejectedGameCount = 0,
                archiveFetchFailureCount = 0,
                failureDetails = null,
            ),
        )
        val exported = artifactService.export(HumanMoveCorpusExportRequest(runId, 1))
        val bytes = Files.readAllBytes(artifactService.archivePath(exported.contentDigest))
        importService.import(bytes, exported.contentDigest)
        val projection =
            materializationService.materialize(
                HumanMoveCorpusMaterializeRequest(contentDigest = exported.contentDigest, prefixN = 1, minObservations = 1),
            )
        projectionFinalizationService.finalize(projection.projectionId)
        return runId
    }

    private fun finalizedRunWithSamePlyOccurrences(): UUID {
        val runId = newRun()
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val positionHash = GameParserService.generateHash(fen)
        listOf("http://game-430-a", "http://game-430-b").forEach { providerGameId ->
            gameWriter.commitGame(
                runId,
                HumanMoveCorpusCandidate(
                    providerGameId = providerGameId,
                    traversedPlayer = "seed",
                    opponent = "opponent",
                    opponentSide = HumanMoveCorpusSide.BLACK,
                    opponentRating = 1100,
                    rules = "chess",
                    timeClass = "rapid",
                    bfsDepth = 0,
                    pgn = "[Event \"issue-430-same-ply\"]\n\n1. e4 *",
                    observations = listOf(HumanMoveCorpusObservedMove(positionHash, fen, "e4", 1)),
                    occurrences = listOf(HumanMoveCorpusOccurrence(1, positionHash, "e4")),
                ),
            )
        }
        gameWriter.finishRun(
            runId,
            HumanMoveCorpusRunOutcome(
                status = HumanMoveCorpusRunStatus.COMPLETED,
                stopReason = "MAX_QUALIFYING_GAMES",
                rejectedGameCount = 0,
                archiveFetchFailureCount = 0,
                failureDetails = null,
            ),
        )
        val exported = artifactService.export(HumanMoveCorpusExportRequest(runId, 2))
        val bytes = Files.readAllBytes(artifactService.archivePath(exported.contentDigest))
        importService.import(bytes, exported.contentDigest)
        val projection =
            materializationService.materialize(
                HumanMoveCorpusMaterializeRequest(contentDigest = exported.contentDigest, prefixN = 2, minObservations = 1),
            )
        projectionFinalizationService.finalize(projection.projectionId)
        return runId
    }

    private data class Occurrence(
        val id: UUID,
        val qualifyingOrdinal: Int,
        val positionHash: String,
        val preMovePly: Int,
        val contentDigest: String,
        val coveredPrefix: Int,
    )

    private data class PersistedEvidenceIdentity(
        val playerId: UUID,
        val gameId: UUID,
        val occurrenceId: UUID,
        val positionIdentity: String,
        val preMovePly: Int,
    )

    private fun occurrencesOf(runId: UUID): List<Occurrence> =
        jdbcTemplate.queryForList(
            "SELECT id, qualifying_ordinal, position_hash, pre_move_ply, content_digest, covered_prefix " +
                "FROM human_move_corpus_occurrence WHERE source_run_id = ? ORDER BY qualifying_ordinal, pre_move_ply",
            runId,
        ).map {
            Occurrence(
                it["id"] as UUID,
                (it["qualifying_ordinal"] as Number).toInt(),
                it["position_hash"].toString(),
                (it["pre_move_ply"] as Number).toInt(),
                it["content_digest"].toString(),
                (it["covered_prefix"] as Number).toInt(),
            )
        }

    private fun referencePopulation(
        runId: UUID,
        occurrence: Occurrence,
    ) = EvaluationReferencePopulation(
        contentDigest = occurrence.contentDigest,
        sourceRunId = runId,
        coveredPrefix = occurrence.coveredPrefix,
        prefixN = occurrence.coveredPrefix,
        ratingBand = "1000-1200",
        minObservations = 1,
        calculationVersion = HumanMoveCorpusMaterializationService.CALCULATION_VERSION,
        distributionSha256 = null,
    )

    private fun configuration() =
        EvaluationEvidenceConfiguration(
            thresholds = EvaluationEvidenceSnapshotService.APPROVED_THRESHOLDS,
            minMistakeCount = 1,
            minTimesReached = 1,
            color = "BOTH",
            platform = "CHESS_COM",
            observationWindowDays = null,
        )

    private fun snapshotFor(
        runId: UUID,
        occurrences: List<Occurrence>,
        player: UUID = UUID.randomUUID(),
        game: UUID = UUID.randomUUID(),
    ) = EvaluationEvidenceSnapshot(
        id = UUID.randomUUID(),
        referencePopulation = referencePopulation(runId, occurrences.first()),
        occurrenceEvidenceId = null,
        players = setOf(EvaluationEvidencePlayer(player, "player")),
        configuration = configuration(),
        sourceRevision = "source-430-test",
        engineIdentity = "stockfish-16-depth-18",
        parserIdentity = "pgn-normalizer-v1",
        rows =
            occurrences.map { occurrence ->
                EvaluationEvidenceRow(
                    playerId = player,
                    gameId = game,
                    occurrenceId = occurrence.id,
                    positionIdentity = occurrence.positionHash,
                    preMovePly = occurrence.preMovePly,
                    move = "Nf3",
                    playerColor = "WHITE",
                    loss = 0.55,
                    engineDepth = 18,
                    observedOutcome = ObservedGameOutcome.WIN,
                    objectiveOutcome = ObjectiveOutcome.WEAK,
                    practicalCandidate = true,
                    practicalEligible = true,
                    practicalWins = 1,
                    practicalDraws = 0,
                    practicalLosses = 0,
                )
            },
    )

    @Test
    fun `persists a valid snapshot bound to a verified occurrence and reconstructs it`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot = snapshotFor(runId, occurrences)

        evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val result = evaluationEvidenceSnapshotService.reconstructPersisted(runId)

        assertEquals(2, result.objectiveWeakness.getValue(snapshot.players.first().id).getValue(0.30))
    }

    @Test
    fun `persists and analyzes distinct retained occurrences at the same operational game ply`() {
        val runId = finalizedRunWithSamePlyOccurrences()
        val occurrences = occurrencesOf(runId)
        assertEquals(2, occurrences.size)
        assertEquals(listOf(1, 2), occurrences.map { it.qualifyingOrdinal })
        assertEquals(setOf(1), occurrences.map { it.preMovePly }.toSet())
        assertEquals(1, occurrences.map { it.positionHash }.toSet().size)
        assertEquals(2, occurrences.map { it.id }.toSet().size)
        val gameId = UUID.randomUUID()
        val snapshot = snapshotFor(runId, occurrences, game = gameId)

        val snapshotId = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val retainedRows =
            jdbcTemplate.query(
                "SELECT player_id, game_id, occurrence_id, position_identity, pre_move_ply " +
                    "FROM evaluation_evidence_row WHERE snapshot_id = ? ORDER BY occurrence_id",
                { rs, _ ->
                    PersistedEvidenceIdentity(
                        playerId = rs.getObject("player_id", UUID::class.java),
                        gameId = rs.getObject("game_id", UUID::class.java),
                        occurrenceId = rs.getObject("occurrence_id", UUID::class.java),
                        positionIdentity = rs.getString("position_identity"),
                        preMovePly = rs.getInt("pre_move_ply"),
                    )
                },
                snapshotId,
            )
        assertEquals(2, retainedRows.size)
        assertEquals(setOf(snapshot.players.single().id), retainedRows.map { it.playerId }.toSet())
        assertEquals(setOf(gameId), retainedRows.map { it.gameId }.toSet())
        assertEquals(occurrences.map { it.id }.toSet(), retainedRows.map { it.occurrenceId }.toSet())
        assertEquals(1, retainedRows.map { it.positionIdentity }.toSet().size)
        assertEquals(setOf(1), retainedRows.map { it.preMovePly }.toSet())

        val projectionId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM human_move_corpus_projection WHERE source_run_id = ? AND finalized AND verified",
                UUID::class.java,
                runId,
            )!!
        val admitted = retainedEvaluationEvidenceService.loadAndAdmit(runId, projectionId, "e6-v1")
        assertEquals(occurrences.map { it.id }.toSet(), admitted.resolvedOccurrences.keys)
        assertEquals(
            occurrences.map { it.qualifyingOrdinal }.toSet(),
            occurrences.map { admitted.resolvedOccurrences.getValue(it.id).qualifyingOrdinal }.toSet(),
        )
        val locations =
            ReferenceCoverageAnalysisService().analyze(admitted.toAnalysisInput())
                .locations.getValue(snapshot.players.single().identity).at(0.50)
        assertEquals(2, locations.occurrenceCount)
        assertEquals(1, locations.distinctPositionCount)
        assertEquals(2, locations.uniqueGameCount)
    }

    @Test
    fun `rejects a row whose occurrence does not exist`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot =
            snapshotFor(runId, occurrences).let { snap ->
                snap.copy(rows = snap.rows.map { it.copy(occurrenceId = UUID.randomUUID()) })
            }

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        }
    }

    @Test
    fun `rejects a row whose occurrence is bound to a different run`() {
        val runId = finalizedRunWithOccurrences()
        val otherRunId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val otherOccurrences = occurrencesOf(otherRunId)
        val snapshot =
            snapshotFor(runId, occurrences).let { snap ->
                snap.copy(rows = listOf(snap.rows.first().copy(occurrenceId = otherOccurrences.first().id)))
            }

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        }
    }

    @Test
    fun `rejects a row whose position identity disagrees with the resolved occurrence`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot =
            snapshotFor(runId, occurrences).let { snap ->
                snap.copy(rows = snap.rows.map { it.copy(positionIdentity = "mismatched-position") })
            }

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        }
    }

    @Test
    fun `retrying identical evidence is idempotent and rejects a conflicting replacement`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot = snapshotFor(runId, occurrences)

        val firstId = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val secondId = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        assertEquals(firstId, secondId)
        assertEquals(
            occurrences.size,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evaluation_evidence_row WHERE snapshot_id = ?",
                Int::class.java,
                firstId,
            ),
        )

        val conflicting = snapshot.copy(rows = snapshot.rows.map { it.copy(loss = it.loss + 0.1) })
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.persist(conflicting, conflicting.referencePopulation)
        }
    }

    @Test
    fun `retained read returns exact metadata roster and every persisted row`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val zeroRowPlayer = UUID.randomUUID()
        val player = UUID.randomUUID()
        val snapshot =
            snapshotFor(runId, occurrences, player = player).copy(
                players =
                    setOf(
                        EvaluationEvidencePlayer(player, "player"),
                        EvaluationEvidencePlayer(zeroRowPlayer, "zero-row-player"),
                    ),
            )
        val persistedId = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)

        val retained = evaluationEvidenceSnapshotService.readVerifiedPersisted(runId)

        assertEquals(persistedId, retained.id)
        assertEquals(snapshot.referencePopulation, retained.referencePopulation)
        assertEquals(snapshot.players, retained.players)
        assertEquals(snapshot.configuration, retained.configuration)
        assertEquals(snapshot.sourceRevision, retained.sourceRevision)
        assertEquals(snapshot.engineIdentity, retained.engineIdentity)
        assertEquals(snapshot.parserIdentity, retained.parserIdentity)
        assertEquals(snapshot.rows, retained.rows)
        assertEquals(
            setOf("reference-population", "canonical-retained-rows-including-occurrence-id"),
            retained.digestCoverage,
        )
    }

    @Test
    fun `retained read fails closed when a retained row is inserted after admission`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot = snapshotFor(runId, occurrences).let { it.copy(rows = it.rows.take(1)) }
        val snapshotId = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val extra = snapshot.rows.first()

        jdbcTemplate.update(
            "INSERT INTO evaluation_evidence_row " +
                "(id, snapshot_id, player_id, game_id, occurrence_id, position_identity, pre_move_ply, move_played, " +
                "player_color, loss, engine_depth, observed_outcome, objective_outcome, practical_candidate, " +
                "practical_eligible, practical_wins, practical_draws, practical_losses) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(),
            snapshotId,
            extra.playerId,
            UUID.randomUUID(),
            occurrences.last().id,
            occurrences.last().positionHash,
            occurrences.last().preMovePly,
            extra.move,
            extra.playerColor,
            extra.loss,
            extra.engineDepth,
            extra.observedOutcome.name,
            extra.objectiveOutcome.name,
            extra.practicalCandidate,
            extra.practicalEligible,
            extra.practicalWins,
            extra.practicalDraws,
            extra.practicalLosses,
        )

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.readVerifiedPersisted(runId)
        }
    }

    @Test
    fun `identical retry compares roster and retained metadata rather than digest alone`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot = snapshotFor(runId, occurrences)
        evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)

        val conflictingRoster = snapshot.copy(players = snapshot.players + EvaluationEvidencePlayer(UUID.randomUUID(), "new-player"))

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.persist(conflictingRoster, conflictingRoster.referencePopulation)
        }.also { error ->
            assertTrue(error.message!!.contains("roster") || error.message!!.contains("metadata"))
        }
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.persist(snapshot.copy(engineIdentity = "different-engine"), snapshot.referencePopulation)
        }
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceSnapshotService.persist(
                snapshot.copy(configuration = snapshot.configuration.copy(minTimesReached = 2)),
                snapshot.referencePopulation,
            )
        }
    }

    @Test
    fun `retained snapshot and row tables are immutable`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot = snapshotFor(runId, occurrences)
        val id = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)

        assertFailsWith<Exception> {
            jdbcTemplate.update("UPDATE evaluation_evidence_snapshot SET evidence_digest = evidence_digest WHERE id = ?", id)
        }
        assertFailsWith<Exception> {
            jdbcTemplate.update("DELETE FROM evaluation_evidence_snapshot WHERE id = ?", id)
        }
        val rowId =
            jdbcTemplate.queryForObject("SELECT id FROM evaluation_evidence_row WHERE snapshot_id = ? LIMIT 1", UUID::class.java, id)
        assertFailsWith<Exception> {
            jdbcTemplate.update("DELETE FROM evaluation_evidence_row WHERE id = ?", rowId)
        }
    }

    @Test
    fun `post-purge reconstruction uses only retained evidence and its verified bindings`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val snapshot = snapshotFor(runId, occurrences)
        evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)

        // Purge the mutable operational rows the post-purge path must not depend on.
        jdbcTemplate.execute("TRUNCATE human_move_corpus_imported_observation, human_move_corpus_imported_game CASCADE")
        jdbcTemplate.execute("TRUNCATE human_move_corpus_observation CASCADE")

        val result = evaluationEvidenceSnapshotService.reconstructPersisted(runId)

        assertTrue(result.objectiveWeakness.getValue(snapshot.players.first().id).getValue(0.30) == 2)
    }

    @Test
    fun `verified retained read survives purge and retains zero-row roster`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val zeroRowPlayer = UUID.randomUUID()
        val player = UUID.randomUUID()
        val snapshot =
            snapshotFor(runId, occurrences, player = player).copy(
                players =
                    setOf(
                        EvaluationEvidencePlayer(player, "player"),
                        EvaluationEvidencePlayer(zeroRowPlayer, "zero-row-player"),
                    ),
            )
        evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)

        jdbcTemplate.execute("TRUNCATE human_move_corpus_imported_observation, human_move_corpus_imported_game CASCADE")
        jdbcTemplate.execute("TRUNCATE human_move_corpus_observation CASCADE")

        val retained = evaluationEvidenceSnapshotService.readVerifiedPersisted(runId)

        assertEquals(snapshot.players, retained.players)
        assertEquals(snapshot.referencePopulation, retained.referencePopulation)
        assertEquals(snapshot.rows, retained.rows)
    }

    @Test
    fun `retained only analysis reconstructs reference positions and source locations after purge`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val playerId = UUID.randomUUID()
        val zeroRowPlayerId = UUID.randomUUID()
        val snapshot =
            snapshotFor(runId, occurrences, player = playerId).copy(
                players =
                    setOf(
                        EvaluationEvidencePlayer(playerId, "evaluated"),
                        EvaluationEvidencePlayer(zeroRowPlayerId, "zero-row"),
                    ),
            )
        evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val projectionId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM human_move_corpus_projection WHERE source_run_id = ? AND finalized AND verified",
                UUID::class.java,
                runId,
            )!!

        jdbcTemplate.execute("TRUNCATE human_move_corpus_imported_observation, human_move_corpus_imported_game CASCADE")

        val admitted = retainedEvaluationEvidenceService.loadAndAdmit(runId, projectionId, "e6-v1")
        val result = ReferenceCoverageAnalysisService().analyze(admitted.toAnalysisInput())

        assertEquals(occurrences.map { it.positionHash }.toSet(), admitted.referencePositions)
        assertEquals(occurrences.map { it.id }.toSet(), admitted.resolvedOccurrences.keys)
        assertEquals(PositionCoverage(2, 2, 1.0), result.byPlayer["evaluated"]!!.at(0.50))
        assertEquals(PositionCoverage(0, 0, null), result.byPlayer["zero-row"]!!.at(0.50))
        assertEquals(2, result.locations["evaluated"]!!.at(0.50).occurrenceCount)
        assertEquals(1, result.locations["evaluated"]!!.at(0.50).uniqueGameCount)
    }

    companion object {
        private val archiveRoot = Files.createTempDirectory("chessecho-corpus-430-artifact-")

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
    }
}
