package com.chessecho.integration.corpus

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.HumanMoveCorpusRunStatus
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.PositionOccurrence
import com.chessecho.dto.HumanMoveCorpusRunRequest
import com.chessecho.humanmove.artifact.HumanMoveCorpusArtifactService
import com.chessecho.humanmove.artifact.HumanMoveCorpusExportRequest
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.service.EvaluationEvidenceConfiguration
import com.chessecho.service.EvaluationEvidenceIntegrityException
import com.chessecho.service.EvaluationEvidencePlayer
import com.chessecho.service.EvaluationEvidenceProducerInput
import com.chessecho.service.EvaluationEvidenceProducerService
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
import com.chessecho.service.LocationGroupCount
import com.chessecho.service.ObjectiveOutcome
import com.chessecho.service.ObservedGameOutcome
import com.chessecho.service.PositionCoverage
import com.chessecho.service.ReferenceCoverageAnalysisService
import com.chessecho.service.RetainedEvaluationEvidenceIntegrityException
import com.chessecho.service.RetainedEvaluationEvidenceService
import com.chessecho.service.SelectedPopulationVerificationService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
    private lateinit var evaluationEvidenceProducerService: EvaluationEvidenceProducerService

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    private lateinit var engineAnalysisRepository: EngineAnalysisRepository

    @SpyBean
    private lateinit var selectedPopulationVerificationService: SelectedPopulationVerificationService

    @SpyBean
    private lateinit var evaluationEvidenceSnapshotServiceSpy: EvaluationEvidenceSnapshotService

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

    private fun producerPopulation(
        runId: UUID,
        prefixN: Int,
    ): EvaluationReferencePopulation =
        jdbcTemplate.query(
            """
            SELECT p.content_digest, p.source_run_id, b.covered_prefix, p.prefix_n, p.rating_band,
                   p.min_observations, p.calculation_version, p.distribution_sha256
            FROM human_move_corpus_projection p
            JOIN human_move_corpus_occurrence_binding b ON b.source_run_id = p.source_run_id
            WHERE p.source_run_id = ? AND p.prefix_n = ? AND p.finalized AND p.verified
            """.trimIndent(),
            { rs, _ ->
                EvaluationReferencePopulation(
                    contentDigest = rs.getString("content_digest"),
                    sourceRunId = rs.getObject("source_run_id", UUID::class.java),
                    coveredPrefix = rs.getInt("covered_prefix"),
                    prefixN = rs.getInt("prefix_n"),
                    ratingBand = rs.getString("rating_band"),
                    minObservations = rs.getInt("min_observations"),
                    calculationVersion = rs.getString("calculation_version"),
                    distributionSha256 = rs.getString("distribution_sha256"),
                )
            },
            runId,
            prefixN,
        ).single()

    private fun producerConfiguration() =
        EvaluationEvidenceConfiguration(
            thresholds = setOf(0.30, 0.50, 0.80),
            minMistakeCount = 9,
            minTimesReached = 99,
            color = "BOTH",
            platform = "CHESS_COM",
            observationWindowDays = null,
        )

    private data class OperationalProducerFixture(
        val input: EvaluationEvidenceProducerInput,
        val decisions: List<PositionOccurrence>,
        val zeroRowPlayer: EvaluationEvidencePlayer,
    )

    private fun operationalProducerFixture(
        population: EvaluationReferencePopulation,
        selectedGameCount: Int = 2,
        unselectedGameCount: Int = 1,
        selectedOrder: List<Int> = (0 until selectedGameCount).toList(),
    ): OperationalProducerFixture {
        val account =
            chessAccountRepository.saveAndFlush(
                ChessAccount(
                    platform = "CHESS_COM",
                    username = "producer-${UUID.randomUUID()}",
                ),
            )
        val position =
            positionRepository.findByHash(occurrencesOf(population.sourceRunId).first().positionHash)
                ?: error("expected retained corpus to have a matching canonical position")
        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = 100,
                bestMove = "Nf3",
                bestMoveEvalCp = 100,
            ).also {
                it.moveEvaluations.add(
                    MoveEvaluation(
                        engineAnalysis = it,
                        move = "e4",
                        evalCp = 45,
                        evalLossFromBest = 0.55,
                    ),
                )
            }
        engineAnalysisRepository.saveAndFlush(analysis)

        val decisions =
            (0 until selectedGameCount + unselectedGameCount).map { index ->
                val game =
                    gameRepository.saveAndFlush(
                        Game(
                            chessAccount = account,
                            platformGameId = "producer-${UUID.randomUUID()}",
                            pgn = "[Event \"producer\"]\n[White \"${account.username}\"]\n[Black \"opponent\"]\n\n1. e4 *",
                            result = "win",
                            whiteUsername = account.username,
                            blackUsername = "opponent",
                        ),
                    )
                positionOccurrenceRepository.saveAndFlush(
                    PositionOccurrence(
                        game = game,
                        position = position,
                        chessAccount = account,
                        plyNumber = 1,
                        movePlayed = "e4",
                        playerColor = "WHITE",
                    ),
                )
            }
        val zeroRowPlayer = EvaluationEvidencePlayer(UUID.randomUUID(), "zero-row-player")
        val declaredPlayer = EvaluationEvidencePlayer(account.id, account.username)
        val input =
            EvaluationEvidenceProducerInput(
                roster = listOf(declaredPlayer, zeroRowPlayer),
                operationalOccurrenceIds = selectedOrder.map { decisions[it].id },
                referencePopulation = population,
                configuration = producerConfiguration(),
            )
        return OperationalProducerFixture(input, decisions, zeroRowPlayer)
    }

    private fun assertProducerTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertTrue(!TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        assertEquals(
            TransactionDefinition.ISOLATION_REPEATABLE_READ,
            TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
        )
    }

    @Test
    fun `producer preserves explicit operational links and remains admissible after source rows are unavailable`() {
        val runId = finalizedRunWithSamePlyOccurrences()
        val population = producerPopulation(runId, prefixN = 2)
        val references = occurrencesOf(runId)
        val fixture = operationalProducerFixture(population)
        val events = mutableListOf<String>()
        doAnswer { invocation ->
            assertProducerTransaction()
            events += "verify"
            invocation.callRealMethod()
        }.whenever(selectedPopulationVerificationService).verify(any(), any())
        doAnswer { invocation ->
            assertProducerTransaction()
            events += "persist"
            invocation.callRealMethod()
        }.whenever(evaluationEvidenceSnapshotServiceSpy).persist(any(), any())

        val snapshotId = evaluationEvidenceProducerService.produce(fixture.input)

        assertEquals(listOf("verify", "persist"), events)
        val snapshot = evaluationEvidenceSnapshotService.readVerifiedPersisted(runId)
        assertEquals(snapshotId, snapshot.id)
        assertEquals(fixture.input.roster, snapshot.players)
        assertEquals(4, snapshot.rows.size)
        assertEquals(
            references.map { it.id }.toSet(),
            snapshot.rows.map { it.occurrenceId }.toSet(),
        )
        assertEquals(
            fixture.decisions.take(2).map { it.game.id }.toSet(),
            snapshot.rows.map { it.gameId }.toSet(),
        )
        val evaluatedPlayer = fixture.input.roster.single { it.id != fixture.zeroRowPlayer.id }
        assertTrue(snapshot.rows.all { it.playerId == evaluatedPlayer.id })
        assertTrue(snapshot.rows.all { it.positionIdentity == references.first().positionHash })
        assertTrue(snapshot.rows.all { it.preMovePly == references.first().preMovePly })
        assertTrue(snapshot.rows.all { it.move == "e4" && it.playerColor == "WHITE" })
        assertTrue(snapshot.rows.all { it.loss == 0.55 && it.engineDepth == 16 })
        assertTrue(snapshot.rows.all { it.observedOutcome == ObservedGameOutcome.WIN })
        assertTrue(
            snapshot.rows.all {
                it.objectiveOutcome == null && it.practicalCandidate == null && it.practicalEligible == null &&
                    it.practicalWins == null && it.practicalDraws == null && it.practicalLosses == null
            },
        )
        assertEquals(fixture.input.configuration, snapshot.configuration)
        assertNull(snapshot.occurrenceEvidenceId)
        assertNull(snapshot.sourceRevision)
        assertNull(snapshot.engineIdentity)
        assertNull(snapshot.parserIdentity)
        val practicalEvidence = evaluationEvidenceSnapshotService.reconstructPersisted(runId).practicalEvidence
        assertNull(practicalEvidence.getValue(evaluatedPlayer.id))
        assertNull(practicalEvidence.getValue(fixture.zeroRowPlayer.id))

        val evidenceDigest =
            jdbcTemplate.queryForObject(
                "SELECT evidence_digest FROM evaluation_evidence_snapshot WHERE id = ?",
                String::class.java,
                snapshotId,
            )
        val reversedInput =
            EvaluationEvidenceProducerInput(
                roster = fixture.input.roster,
                operationalOccurrenceIds = fixture.input.operationalOccurrenceIds.reversed(),
                referencePopulation = population,
                configuration = fixture.input.configuration,
            )
        assertEquals(snapshotId, evaluationEvidenceProducerService.produce(reversedInput))
        assertEquals(
            evidenceDigest,
            jdbcTemplate.queryForObject(
                "SELECT evidence_digest FROM evaluation_evidence_snapshot WHERE id = ?",
                String::class.java,
                snapshotId,
            ),
        )
        val changedInput =
            EvaluationEvidenceProducerInput(
                roster = fixture.input.roster,
                operationalOccurrenceIds = listOf(fixture.input.operationalOccurrenceIds.first()),
                referencePopulation = population,
                configuration = fixture.input.configuration,
            )
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            evaluationEvidenceProducerService.produce(changedInput)
        }
        assertEquals(4, evaluationEvidenceSnapshotService.readVerifiedPersisted(runId).rows.size)

        val projectionId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM human_move_corpus_projection WHERE source_run_id = ? AND prefix_n = 2 AND finalized AND verified",
                UUID::class.java,
                runId,
            )!!
        jdbcTemplate.execute("TRUNCATE human_move_corpus_imported_observation, human_move_corpus_imported_game CASCADE")

        val admitted = retainedEvaluationEvidenceService.loadAndAdmit(runId, projectionId, "e6-v1")
        val analysisResult =
            ReferenceCoverageAnalysisService().analyze(admitted.toAnalysisInput())
        assertEquals(4, admitted.snapshot.rows.size)
        assertEquals(
            PositionCoverage(1, 1, 1.0),
            analysisResult.byPlayer.getValue(evaluatedPlayer.identity).at(0.50),
        )
        assertEquals(
            PositionCoverage(0, 0, null),
            analysisResult.byPlayer.getValue(fixture.zeroRowPlayer.identity).at(0.50),
        )
        assertEquals(2, analysisResult.locations.getValue(evaluatedPlayer.identity).at(0.50).occurrenceCount)
        assertEquals(2, analysisResult.locations.getValue(evaluatedPlayer.identity).at(0.50).uniqueGameCount)
    }

    @Test
    fun `producer persists an empty operational selection with its declared zero row roster`() {
        val runId = finalizedRunWithSamePlyOccurrences()
        val population = producerPopulation(runId, prefixN = 2)
        val zeroRowPlayer = EvaluationEvidencePlayer(UUID.randomUUID(), "zero-row-player")
        val input =
            EvaluationEvidenceProducerInput(
                roster = listOf(zeroRowPlayer),
                operationalOccurrenceIds = emptyList(),
                referencePopulation = population,
                configuration = producerConfiguration(),
            )

        val snapshotId = evaluationEvidenceProducerService.produce(input)
        val snapshot = evaluationEvidenceSnapshotService.readVerifiedPersisted(runId)

        assertEquals(snapshotId, snapshot.id)
        assertEquals(setOf(zeroRowPlayer), snapshot.players)
        assertTrue(snapshot.rows.isEmpty())
    }

    @Test
    fun `verification failure leaves no producer snapshot or evidence rows`() {
        val runId = finalizedRunWithSamePlyOccurrences()
        val population = producerPopulation(runId, prefixN = 2)
        val fixture = operationalProducerFixture(population)
        val invalidPopulation = population.copy(distributionSha256 = "f".repeat(64))
        val input =
            EvaluationEvidenceProducerInput(
                roster = fixture.input.roster,
                operationalOccurrenceIds = fixture.input.operationalOccurrenceIds,
                referencePopulation = invalidPopulation,
                configuration = fixture.input.configuration,
            )

        assertFailsWith<RetainedEvaluationEvidenceIntegrityException> {
            evaluationEvidenceProducerService.produce(input)
        }

        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evaluation_evidence_snapshot WHERE source_run_id = ?",
                Int::class.java,
                runId,
            ),
        )
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM evaluation_evidence_row e
                JOIN evaluation_evidence_snapshot s ON s.id = e.snapshot_id
                WHERE s.source_run_id = ?
                """.trimIndent(),
                Int::class.java,
                runId,
            ),
        )
    }

    @Test
    fun `persistence failure after snapshot insertion rolls back the complete producer write`() {
        val runId = finalizedRunWithSamePlyOccurrences()
        val population = producerPopulation(runId, prefixN = 2)
        val fixture = operationalProducerFixture(population)
        jdbcTemplate.execute(
            """
            CREATE OR REPLACE FUNCTION fail_producer_evidence_row_insert() RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'forced producer row failure';
            END;
            $$ LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER fail_producer_evidence_row_insert
            BEFORE INSERT ON evaluation_evidence_row
            FOR EACH ROW EXECUTE FUNCTION fail_producer_evidence_row_insert()
            """.trimIndent(),
        )

        try {
            assertFailsWith<Exception> {
                evaluationEvidenceProducerService.produce(fixture.input)
            }
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_producer_evidence_row_insert ON evaluation_evidence_row")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_producer_evidence_row_insert()")
        }

        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evaluation_evidence_snapshot WHERE source_run_id = ?",
                Int::class.java,
                runId,
            ),
        )
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM evaluation_evidence_row e
                JOIN evaluation_evidence_snapshot s ON s.id = e.snapshot_id
                WHERE s.source_run_id = ?
                """.trimIndent(),
                Int::class.java,
                runId,
            ),
        )
    }

    private fun withoutNonCoreEvidence(snapshot: EvaluationEvidenceSnapshot) =
        snapshot.copy(
            sourceRevision = null,
            engineIdentity = null,
            parserIdentity = null,
            rows =
                snapshot.rows.map {
                    it.copy(
                        objectiveOutcome = null,
                        practicalCandidate = null,
                        practicalEligible = null,
                        practicalWins = null,
                        practicalDraws = null,
                        practicalLosses = null,
                    )
                },
        )

    @Test
    fun `unavailable non-core evidence round trips and identical retry remains idempotent`() {
        val runId = finalizedRunWithOccurrences()
        val snapshot = withoutNonCoreEvidence(snapshotFor(runId, occurrencesOf(runId)))

        val id = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        assertEquals(
            id,
            evaluationEvidenceSnapshotService.persist(snapshot.copy(id = UUID.randomUUID()), snapshot.referencePopulation),
        )
        val retained = evaluationEvidenceSnapshotService.readVerifiedPersisted(runId)
        assertEquals(snapshot.rows, retained.rows)
        assertNull(retained.sourceRevision)
        assertNull(retained.engineIdentity)
        assertNull(retained.parserIdentity)
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evaluation_evidence_snapshot WHERE source_run_id = ?",
                Int::class.java,
                runId,
            ),
        )
        assertEquals(
            snapshot.rows.size,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM evaluation_evidence_row WHERE snapshot_id = ?",
                Int::class.java,
                id,
            ),
        )
        assertNull(evaluationEvidenceSnapshotService.reconstruct(snapshot).practicalEvidence.getValue(snapshot.players.single().id))
        assertEquals(
            evaluationEvidenceSnapshotService.reconstruct(snapshot),
            evaluationEvidenceSnapshotService.reconstructPersisted(runId),
        )
    }

    @Test
    fun `absent versus supplied row and metadata evidence conflicts in both directions`() {
        val runId = finalizedRunWithOccurrences()
        val snapshot = snapshotFor(runId, occurrencesOf(runId))
        val absent = withoutNonCoreEvidence(snapshot)
        evaluationEvidenceSnapshotService.persist(absent, absent.referencePopulation)
        listOf(
            absent.copy(sourceRevision = snapshot.sourceRevision),
            absent.copy(engineIdentity = snapshot.engineIdentity),
            absent.copy(parserIdentity = snapshot.parserIdentity),
            absent.copy(rows = absent.rows.map { it.copy(objectiveOutcome = ObjectiveOutcome.WEAK) }),
            absent.copy(rows = snapshot.rows),
        ).forEach { supplied ->
            assertFailsWith<EvaluationEvidenceIntegrityException> {
                evaluationEvidenceSnapshotService.persist(supplied, supplied.referencePopulation)
            }
        }
        val otherRunId = finalizedRunWithOccurrences()
        val complete = snapshotFor(otherRunId, occurrencesOf(otherRunId))
        evaluationEvidenceSnapshotService.persist(complete, complete.referencePopulation)
        listOf(
            complete.copy(sourceRevision = null),
            complete.copy(engineIdentity = null),
            complete.copy(parserIdentity = null),
            complete.copy(rows = complete.rows.map { it.copy(objectiveOutcome = null) }),
            complete.copy(rows = withoutNonCoreEvidence(complete).rows),
        ).forEach { missing ->
            assertFailsWith<EvaluationEvidenceIntegrityException> {
                evaluationEvidenceSnapshotService.persist(missing, missing.referencePopulation)
            }
        }
    }

    @Test
    fun `database rejects partial practical evidence without the application validator`() {
        val runId = finalizedRunWithOccurrences()
        val snapshot = withoutNonCoreEvidence(snapshotFor(runId, occurrencesOf(runId)))
        val id = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val row = snapshot.rows.first()

        val error =
            assertFailsWith<DataIntegrityViolationException> {
                jdbcTemplate.update(
                    "INSERT INTO evaluation_evidence_row " +
                        "(id, snapshot_id, player_id, game_id, occurrence_id, position_identity, pre_move_ply, move_played, " +
                        "player_color, loss, engine_depth, observed_outcome, objective_outcome, practical_candidate, " +
                        "practical_eligible, practical_wins, practical_draws, practical_losses) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    id,
                    UUID.randomUUID(),
                    row.gameId,
                    row.occurrenceId,
                    row.positionIdentity,
                    row.preMovePly,
                    row.move,
                    row.playerColor,
                    row.loss,
                    row.engineDepth,
                    row.observedOutcome.name,
                    null,
                    true,
                    null,
                    null,
                    null,
                    null,
                )
            }
        assertTrue(error.message!!.contains("ck_evaluation_evidence_row_practical_complete"))
    }

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
    fun `persists independent operational games linked to one occurrence with immutable retry semantics`() {
        val runId = finalizedRunWithOccurrences()
        val occurrence = occurrencesOf(runId).first()
        val base = snapshotFor(runId, listOf(occurrence))
        val first = base.rows.single()
        val second = first.copy(gameId = UUID.randomUUID(), loss = 0.35)
        val snapshot = base.copy(rows = listOf(first, second))

        val id = evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)

        val retained = evaluationEvidenceSnapshotService.readVerifiedPersisted(runId)
        assertEquals(2, retained.rows.size)
        assertEquals(setOf(first.gameId, second.gameId), retained.rows.map { it.gameId }.toSet())
        assertEquals(setOf(occurrence.id), retained.rows.map { it.occurrenceId }.toSet())
        assertEquals(
            id,
            evaluationEvidenceSnapshotService.persist(
                snapshot.copy(rows = snapshot.rows.reversed()),
                snapshot.referencePopulation,
            ),
        )
        val reconstruction = evaluationEvidenceSnapshotService.reconstructPersisted(runId)
        assertEquals(2, reconstruction.objectiveWeakness.getValue(first.playerId).getValue(0.30))
        assertEquals(1, reconstruction.objectiveWeakness.getValue(first.playerId).getValue(0.50))

        listOf(
            snapshot.copy(rows = listOf(first.copy(loss = 0.60), second)),
            snapshot.copy(rows = listOf(first)),
            snapshot.copy(rows = listOf(first, second, first.copy(gameId = UUID.randomUUID(), loss = 0.25))),
        ).forEach { conflicting ->
            assertFailsWith<EvaluationEvidenceIntegrityException> {
                evaluationEvidenceSnapshotService.persist(conflicting, conflicting.referencePopulation)
            }
        }
        assertEquals(2, evaluationEvidenceSnapshotService.readVerifiedPersisted(runId).rows.size)
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
            extra.objectiveOutcome?.name,
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
    fun `retained only analysis admits absent non-core evidence and preserves locations after purge`() {
        val runId = finalizedRunWithOccurrences()
        val occurrences = occurrencesOf(runId)
        val playerId = UUID.randomUUID()
        val zeroRowPlayerId = UUID.randomUUID()
        val snapshot =
            withoutNonCoreEvidence(snapshotFor(runId, occurrences, player = playerId)).copy(
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

        assertEquals(snapshot.rows, admitted.snapshot.rows)
        assertNull(evaluationEvidenceSnapshotService.reconstructPersisted(runId).practicalEvidence.getValue(playerId))
        assertNull(evaluationEvidenceSnapshotService.reconstructPersisted(runId).practicalEvidence.getValue(zeroRowPlayerId))
        assertEquals(occurrences.map { it.positionHash }.toSet(), admitted.referencePositions)
        assertEquals(occurrences.map { it.id }.toSet(), admitted.resolvedOccurrences.keys)
        assertEquals(PositionCoverage(2, 2, 1.0), result.byPlayer["evaluated"]!!.at(0.50))
        assertEquals(PositionCoverage(0, 0, null), result.byPlayer["zero-row"]!!.at(0.50))
        assertEquals(2, result.locations["evaluated"]!!.at(0.50).occurrenceCount)
        assertEquals(1, result.locations["evaluated"]!!.at(0.50).uniqueGameCount)
    }

    @Test
    fun `retained analysis counts repeated operational links as one reference location`() {
        val runId = finalizedRunWithOccurrences()
        val occurrence = occurrencesOf(runId).first()
        val base = snapshotFor(runId, listOf(occurrence))
        val first = base.rows.single()
        val second = first.copy(gameId = UUID.randomUUID(), loss = 0.35)
        val snapshot = base.copy(rows = listOf(first, second))
        evaluationEvidenceSnapshotService.persist(snapshot, snapshot.referencePopulation)
        val projectionId =
            jdbcTemplate.queryForObject(
                "SELECT id FROM human_move_corpus_projection WHERE source_run_id = ? AND finalized AND verified",
                UUID::class.java,
                runId,
            )!!

        val admitted = retainedEvaluationEvidenceService.loadAndAdmit(runId, projectionId, "e6-v1")
        val result = ReferenceCoverageAnalysisService().analyze(admitted.toAnalysisInput())

        assertEquals(2, admitted.snapshot.rows.size)
        val reconstruction = evaluationEvidenceSnapshotService.reconstructPersisted(runId)
        assertEquals(2, reconstruction.objectiveWeakness.getValue(first.playerId).getValue(0.30))
        assertEquals(1, reconstruction.objectiveWeakness.getValue(first.playerId).getValue(0.50))
        assertEquals(PositionCoverage(1, 1, 1.0), result.byPlayer.getValue("player").at(0.30))
        assertEquals(PositionCoverage(1, 1, 1.0), result.byPlayer.getValue("player").at(0.50))

        listOf(0.30, 0.50).forEach { threshold ->
            val location = result.locations.getValue("player").at(threshold)
            assertEquals(1, location.occurrenceCount)
            assertEquals(1, location.denominator)
            assertEquals(1, location.uniqueGameCount)
            assertEquals(LocationGroupCount(1, 1), location.groupsByPreMovePly.getValue(occurrence.preMovePly))
        }
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
