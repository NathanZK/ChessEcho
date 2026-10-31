package com.chessecho.integration.corpus

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.HumanMoveCorpusRunStatus
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
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
import com.chessecho.service.ObjectiveOutcome
import com.chessecho.service.ObservedGameOutcome
import com.chessecho.service.RetainedEvaluationEvidenceService
import com.chessecho.service.SelectedPopulationVerificationService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
open class EvaluationEvidenceSnapshotBaseIntegrationTest {
    @Autowired
    protected lateinit var gameWriter: HumanMoveCorpusGameWriter

    @Autowired
    protected lateinit var artifactService: HumanMoveCorpusArtifactService

    @Autowired
    protected lateinit var importService: HumanMoveCorpusImportService

    @Autowired
    protected lateinit var materializationService: HumanMoveCorpusMaterializationService

    @Autowired
    protected lateinit var projectionFinalizationService: HumanMoveCorpusProjectionFinalizationService

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    protected lateinit var evaluationEvidenceSnapshotService: EvaluationEvidenceSnapshotService

    @Autowired
    protected lateinit var evaluationEvidenceProducerService: EvaluationEvidenceProducerService

    @Autowired
    protected lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    protected lateinit var gameRepository: GameRepository

    @Autowired
    protected lateinit var positionRepository: PositionRepository

    @Autowired
    protected lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    protected lateinit var engineAnalysisRepository: EngineAnalysisRepository

    @SpyBean
    protected lateinit var selectedPopulationVerificationService: SelectedPopulationVerificationService

    @SpyBean
    protected lateinit var evaluationEvidenceSnapshotServiceSpy: EvaluationEvidenceSnapshotService

    @Autowired
    protected lateinit var retainedEvaluationEvidenceService: RetainedEvaluationEvidenceService

    @BeforeEach
    fun setUp() = resetDatabase()

    @AfterEach
    fun tearDown() = resetDatabase()

    protected fun resetDatabase() =
        jdbcTemplate
            .execute(
                "TRUNCATE evaluation_evidence_row, evaluation_evidence_snapshot, human_move_corpus_import CASCADE",
            ).also {
                jdbcTemplate.execute(
                    "TRUNCATE human_move_corpus_observation, human_move_corpus_game, human_move_corpus_run, " +
                        "human_move_distribution, human_move_bfs_seen_game, position CASCADE",
                )
            }

    // --- Helper data classes -------------------------------------------------
    protected data class Occurrence(
        val id: UUID,
        val qualifyingOrdinal: Int,
        val positionHash: String,
        val preMovePly: Int,
        val contentDigest: String,
        val coveredPrefix: Int,
    )

    protected data class PersistedEvidenceIdentity(
        val playerId: UUID,
        val gameId: UUID,
        val occurrenceId: UUID,
        val positionIdentity: String,
        val preMovePly: Int,
    )

    // --- Helper methods ------------------------------------------------------
    protected fun newRun(): UUID =
        gameWriter.createRun(
            HumanMoveCorpusRunRequest(
                ratingBand = "1000-1200",
                seedPlayers = listOf("seed"),
                maxQualifyingGames = 10,
                sourceRevision = "source-430-test",
            ),
        )

    protected fun finalizedRunWithOccurrences(): UUID {
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

    protected fun finalizedRunWithSamePlyOccurrences(): UUID {
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

    protected fun occurrencesOf(runId: UUID): List<Occurrence> =
        jdbcTemplate
            .queryForList(
                "SELECT id, qualifying_ordinal, position_hash, pre_move_ply, content_digest, covered_prefix" +
                    " FROM human_move_corpus_occurrence WHERE source_run_id = ?" +
                    " ORDER BY qualifying_ordinal, pre_move_ply",
                runId,
            ).map { row ->
                Occurrence(
                    (row["id"] ?: error("missing id")) as UUID,
                    ((row["qualifying_ordinal"] ?: error("missing ordinal")) as Number).toInt(),
                    row["position_hash"].toString(),
                    ((row["pre_move_ply"] ?: error("missing ply")) as Number).toInt(),
                    row["content_digest"].toString(),
                    ((row["covered_prefix"] ?: error("missing prefix")) as Number).toInt(),
                )
            }

    protected fun referencePopulation(
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

    protected fun configuration() =
        EvaluationEvidenceConfiguration(
            thresholds = EvaluationEvidenceSnapshotService.APPROVED_THRESHOLDS,
            minMistakeCount = 1,
            minTimesReached = 1,
            color = "BOTH",
            platform = "CHESS_COM",
            observationWindowDays = null,
        )

    protected fun snapshotFor(
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
            occurrences.map { occ ->
                EvaluationEvidenceRow(
                    playerId = player,
                    gameId = game,
                    occurrenceId = occ.id,
                    positionIdentity = occ.positionHash,
                    preMovePly = occ.preMovePly,
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

    protected fun producerPopulation(
        runId: UUID,
        prefixN: Int,
    ): EvaluationReferencePopulation =
        jdbcTemplate
            .query(
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

    protected fun producerConfiguration() =
        EvaluationEvidenceConfiguration(
            thresholds = setOf(0.30, 0.50, 0.80),
            minMistakeCount = 9,
            minTimesReached = 99,
            color = "BOTH",
            platform = "CHESS_COM",
            observationWindowDays = null,
        )

    protected data class OperationalProducerFixture(
        val input: EvaluationEvidenceProducerInput,
        val decisions: List<PositionOccurrence>,
        val zeroRowPlayer: EvaluationEvidencePlayer,
    )

    protected fun operationalProducerFixture(
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
            ).also { ea ->
                ea.moveEvaluations.add(
                    MoveEvaluation(
                        engineAnalysis = ea,
                        move = "e4",
                        evalCp = 45,
                        evalLossFromBest = 0.55,
                    ),
                )
            }
        engineAnalysisRepository.saveAndFlush(analysis)
        val decisions = createProducerOccurrences(account, position, selectedGameCount + unselectedGameCount)
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

    private fun createProducerOccurrences(
        account: ChessAccount,
        position: Position,
        totalCount: Int,
    ): List<PositionOccurrence> =
        (0 until totalCount).map {
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

    protected fun assertProducerTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
        assertTrue(!TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        assertEquals(
            TransactionDefinition.ISOLATION_REPEATABLE_READ,
            TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
        )
    }

    protected fun withoutNonCoreEvidence(snapshot: EvaluationEvidenceSnapshot) =
        snapshot.copy(
            sourceRevision = null,
            engineIdentity = null,
            parserIdentity = null,
            rows =
                snapshot.rows.map { row ->
                    row.copy(
                        objectiveOutcome = null,
                        practicalCandidate = null,
                        practicalEligible = null,
                        practicalWins = null,
                        practicalDraws = null,
                        practicalLosses = null,
                    )
                },
        )

    companion object {
        private val archiveRoot =
            java.nio.file.Files
                .createTempDirectory("chessecho-corpus-430-artifact-")

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

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("chessecho.corpus.archive-root", archiveRoot::toString)
            registry.add("chessecho.corpus.max-archive-bytes") { "100000" }
        }
    }
}
