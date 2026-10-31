package com.chessecho.service

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.HumanMoveCorpusProjection
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID
import kotlin.test.assertFailsWith

class EvaluationEvidenceProducerDecisionTest {
    @Test
    fun `rejects selected operational IDs absent from the explicit repository lookup`() {
        val operationalId = UUID.randomUUID()
        val account = ChessAccount(platform = "CHESS_COM", username = "player")
        val population = referencePopulation()
        val occurrenceRepository = mock<PositionOccurrenceRepository>()
        whenever(occurrenceRepository.findSelectedWithOperationalFacts(setOf(operationalId))).thenReturn(emptyList())
        val verifier = mock<SelectedPopulationVerificationService>()
        val snapshotService = mock<EvaluationEvidenceSnapshotService>()
        val producer =
            EvaluationEvidenceProducerService(
                positionOccurrenceRepository = occurrenceRepository,
                engineAnalysisRepository = mock(),
                jdbcTemplate = mock(),
                selectedPopulationVerificationService = verifier,
                evaluationEvidenceSnapshotService = snapshotService,
                gameOutcomeNormalizer = mock(),
                engineAnalysisService = mock(),
            )

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            producer.produce(
                EvaluationEvidenceProducerInput(
                    roster = listOf(EvaluationEvidencePlayer(account.id, account.username)),
                    operationalOccurrenceIds = listOf(operationalId),
                    referencePopulation = population,
                    configuration = configuration(),
                ),
            )
        }

        verify(verifier, never()).verify(any(), any())
        verify(snapshotService, never()).persist(any(), any())
    }

    @Test
    fun `rejects selected decisions outside the declared player roster`() {
        val harness = singleDecisionHarness(bestMoveEvalCp = 100, moveEvalCp = 25, persistedLoss = 0.75, declareDifferentPlayer = true)

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            harness.producer.produce(harness.input)
        }

        verify(harness.verifier, never()).verify(any(), any())
        verify(harness.snapshotService, never()).persist(any(), any())
    }

    @Test
    fun `rejects selected decisions without engine analysis or exact move evaluation`() {
        val harnessMissingAnalysis =
            singleDecisionHarness(bestMoveEvalCp = 100, moveEvalCp = 25, persistedLoss = 0.75, includeEngineAnalysis = false)
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            harnessMissingAnalysis.producer.produce(harnessMissingAnalysis.input)
        }

        val harnessMissingEvaluation =
            singleDecisionHarness(bestMoveEvalCp = 100, moveEvalCp = 25, persistedLoss = 0.75, evaluatedMove = "different-move")
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            harnessMissingEvaluation.producer.produce(harnessMissingEvaluation.input)
        }
    }

    @Test
    fun `fails closed when persisted zero loss has a missing best move centipawn input`() {
        val harness = singleDecisionHarness(bestMoveEvalCp = null, moveEvalCp = 25, persistedLoss = 0.0)
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            harness.producer.produce(harness.input)
        }
        verify(harness.verifier, never()).verify(any(), any())
        verify(harness.snapshotService, never()).persist(any(), any())
    }

    @Test
    fun `rejects a returned verification fact from a different selected source binding`() {
        val harness =
            singleDecisionHarness(
                bestMoveEvalCp = 100,
                moveEvalCp = 25,
                persistedLoss = 0.75,
                verifiedSourceRunId = UUID.randomUUID(),
            )

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            harness.producer.produce(harness.input)
        }

        verify(harness.snapshotService, never()).persist(any(), any())
    }

    @Test
    fun `fails closed when persisted loss has a missing played move centipawn input`() {
        val harness = singleDecisionHarness(bestMoveEvalCp = 100, moveEvalCp = null, persistedLoss = 0.4)
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            harness.producer.produce(harness.input)
        }
        verify(harness.verifier, never()).verify(any(), any())
        verify(harness.snapshotService, never()).persist(any(), any())
    }

    @Test
    fun `preserves a genuine zero loss when both centipawn inputs are present`() {
        val harness = singleDecisionHarness(bestMoveEvalCp = 25, moveEvalCp = 25, persistedLoss = 0.0)

        harness.producer.produce(harness.input)

        val captor = argumentCaptor<EvaluationEvidenceSnapshot>()
        verify(harness.snapshotService).persist(captor.capture(), eq(harness.input.referencePopulation))
        val row = captor.firstValue.rows.single()
        assertEquals(0.0, row.loss)
        assertEquals(16, row.engineDepth)
    }

    @Test
    fun `fails closed when loss is absent and a required centipawn input is absent`() {
        val harness = singleDecisionHarness(bestMoveEvalCp = 100, moveEvalCp = null, persistedLoss = null)
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            harness.producer.produce(harness.input)
        }
        verify(harness.engineAnalysisService, never()).calculateEvalLoss(any(), any())
        verify(harness.verifier, never()).verify(any(), any())
        verify(harness.snapshotService, never()).persist(any(), any())
    }

    @Test
    fun `fails closed for negative or non finite persisted losses`() {
        listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY).forEach { invalidLoss ->
            val harness = singleDecisionHarness(bestMoveEvalCp = 100, moveEvalCp = 25, persistedLoss = invalidLoss)
            assertFailsWith<EvaluationEvidenceIntegrityException> {
                harness.producer.produce(harness.input)
            }
            verify(harness.verifier, never()).verify(any(), any())
            verify(harness.snapshotService, never()).persist(any(), any())
        }
    }

    @Test
    fun `derives absent loss through the existing deterministic calculation`() {
        val harness = singleDecisionHarness(bestMoveEvalCp = 100, moveEvalCp = 25, persistedLoss = null, calculatedLoss = 0.75)

        harness.producer.produce(harness.input)

        val captor = argumentCaptor<EvaluationEvidenceSnapshot>()
        verify(harness.snapshotService).persist(captor.capture(), eq(harness.input.referencePopulation))
        assertEquals(
            0.75,
            captor.firstValue.rows
                .single()
                .loss,
        )
        verify(harness.engineAnalysisService).calculateEvalLoss(100, 25)
    }

    @Test
    fun `evaluates forced-mate position without requiring centipawn presence`() {
        val harness =
            singleDecisionHarness(
                bestMoveEvalCp = null,
                moveEvalCp = null,
                persistedLoss = 2.0,
                bestMoveEvalMate = -3,
                moveEvalMate = -5,
            )

        harness.producer.produce(harness.input)

        val captor = argumentCaptor<EvaluationEvidenceSnapshot>()
        verify(harness.snapshotService).persist(captor.capture(), eq(harness.input.referencePopulation))
        assertEquals(
            2.0,
            captor.firstValue.rows
                .single()
                .loss,
        )
    }

    @Test
    fun `rejects a returned verification fact with a mismatched position hash`() {
        val harness =
            singleDecisionHarness(
                bestMoveEvalCp = 100,
                moveEvalCp = 25,
                persistedLoss = 0.75,
                verifiedPositionHash = "mismatched-position-hash",
            )

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            harness.producer.produce(harness.input)
        }

        verify(harness.snapshotService, never()).persist(any(), any())
    }

    @Test
    fun `rejects a returned verification fact with a mismatched pre move ply`() {
        val harness =
            singleDecisionHarness(
                bestMoveEvalCp = 100,
                moveEvalCp = 25,
                persistedLoss = 0.75,
                verifiedPreMovePly = 99,
            )

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            harness.producer.produce(harness.input)
        }

        verify(harness.snapshotService, never()).persist(any(), any())
    }

    @Test
    fun `rejects an unnormalizable outcome and invalid frozen configuration before verification`() {
        val noOutcome =
            singleDecisionHarness(
                bestMoveEvalCp = 100,
                moveEvalCp = 25,
                persistedLoss = 0.4,
                outcome = null,
            )
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            noOutcome.producer.produce(noOutcome.input)
        }
        verify(noOutcome.verifier, never()).verify(any(), any())
        verify(noOutcome.snapshotService, never()).persist(any(), any())

        val nonNullObservationWindow =
            singleDecisionHarness(
                bestMoveEvalCp = 100,
                moveEvalCp = 25,
                persistedLoss = 0.4,
                snapshotConfiguration = configuration().copy(observationWindowDays = 30),
            )
        assertFailsWith<EvaluationEvidenceIntegrityException> {
            nonNullObservationWindow.producer.produce(nonNullObservationWindow.input)
        }
        verify(nonNullObservationWindow.occurrenceRepository, never()).findSelectedWithOperationalFacts(any())
        verify(nonNullObservationWindow.verifier, never()).verify(any(), any())
        verify(nonNullObservationWindow.snapshotService, never()).persist(any(), any())
    }

    @Test
    fun `rejects each invalid frozen configuration field without reading operational evidence`() {
        val invalidConfigurations =
            listOf(
                configuration().copy(thresholds = setOf(0.3, 0.5)),
                configuration().copy(minMistakeCount = -1),
                configuration().copy(minTimesReached = -1),
                configuration().copy(color = "RED"),
                configuration().copy(platform = "LICHESS"),
            )

        invalidConfigurations.forEach { config ->
            val harness =
                singleDecisionHarness(
                    bestMoveEvalCp = 100,
                    moveEvalCp = 25,
                    persistedLoss = 0.75,
                    snapshotConfiguration = config,
                )

            assertFailsWith<EvaluationEvidenceIntegrityException> {
                harness.producer.produce(harness.input)
            }

            verify(harness.occurrenceRepository, never()).findSelectedWithOperationalFacts(any())
            verify(harness.verifier, never()).verify(any(), any())
            verify(harness.snapshotService, never()).persist(any(), any())
        }
    }

    private fun singleDecisionHarness(
        bestMoveEvalCp: Int?,
        moveEvalCp: Int?,
        persistedLoss: Double?,
        outcome: PracticalOutcome? = PracticalOutcome.WIN,
        snapshotConfiguration: EvaluationEvidenceConfiguration = configuration(),
        verifiedPositionHash: String = "single-position-hash",
        verifiedPreMovePly: Int = 5,
        verifiedSourceRunId: UUID? = null,
        includeEngineAnalysis: Boolean = true,
        evaluatedMove: String = "e4",
        declareDifferentPlayer: Boolean = false,
        calculatedLoss: Double = 0.75,
        bestMoveEvalMate: Int? = null,
        moveEvalMate: Int? = null,
    ): SingleDecisionHarness {
        val account = ChessAccount(platform = "CHESS_COM", username = "player")
        val position = Position(hash = "single-position-hash", fen = "position-fen")
        val operational = occurrence(game(account, "single-game"), position, account, 5)
        val analysis = createAnalysis(position, bestMoveEvalCp, bestMoveEvalMate, evaluatedMove, moveEvalCp, moveEvalMate, persistedLoss)
        val population = referencePopulation()
        val referenceId = UUID.randomUUID()
        val jdbcTemplate = setupH2Jdbc(referenceId, population)

        val occurrenceRepository = mock<PositionOccurrenceRepository>()
        whenever(occurrenceRepository.findSelectedWithOperationalFacts(setOf(operational.id))).thenReturn(listOf(operational))
        val analysisRepository = mock<EngineAnalysisRepository>()
        whenever(analysisRepository.findByPositionIdInWithMoveEvaluations(setOf(position.id)))
            .thenReturn(if (includeEngineAnalysis) listOf(analysis) else emptyList())
        val normalizer = mock<GameOutcomeNormalizer>()
        whenever(normalizer.normalize(any(), eq("WHITE"), eq("player"))).thenReturn(NormalizedGameOutcome(outcome = outcome))
        val verifier = mock<SelectedPopulationVerificationService>()
        whenever(verifier.verify(eq(population), any())).thenAnswer {
            verifiedContext(
                population,
                setOf(referenceId),
                verifiedPositionHash,
                verifiedPreMovePly,
                verifiedSourceRunId ?: population.sourceRunId,
            )
        }
        val snapshotService = mock<EvaluationEvidenceSnapshotService>()
        whenever(snapshotService.persist(any(), eq(population))).thenReturn(UUID.randomUUID())
        val engineAnalysisService = mock<EngineAnalysisService>()
        whenever(engineAnalysisService.calculateEvalLoss(100, 25)).thenReturn(calculatedLoss)

        val input =
            EvaluationEvidenceProducerInput(
                roster = listOf(EvaluationEvidencePlayer(if (declareDifferentPlayer) UUID.randomUUID() else account.id, account.username)),
                operationalOccurrenceIds = listOf(operational.id),
                referencePopulation = population,
                configuration = snapshotConfiguration,
            )
        return SingleDecisionHarness(
            producer =
                EvaluationEvidenceProducerService(
                    positionOccurrenceRepository = occurrenceRepository,
                    engineAnalysisRepository = analysisRepository,
                    jdbcTemplate = jdbcTemplate,
                    selectedPopulationVerificationService = verifier,
                    evaluationEvidenceSnapshotService = snapshotService,
                    gameOutcomeNormalizer = normalizer,
                    engineAnalysisService = engineAnalysisService,
                ),
            input = input,
            verifier = verifier,
            snapshotService = snapshotService,
            engineAnalysisService = engineAnalysisService,
            occurrenceRepository = occurrenceRepository,
        )
    }

    private fun createAnalysis(
        position: Position,
        bestCp: Int?,
        bestMate: Int?,
        move: String,
        moveCp: Int?,
        moveMate: Int?,
        loss: Double?,
    ) = EngineAnalysis(
        position = position,
        depth = 16,
        baselineEvalCp = 100,
        bestMove = "d4",
        bestMoveEvalCp = bestCp,
        bestMoveEvalMate = bestMate,
    ).also {
        it.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = it, move = move, evalCp = moveCp, evalMate = moveMate, evalLossFromBest = loss),
        )
    }

    private fun setupH2Jdbc(
        referenceId: UUID,
        population: EvaluationReferencePopulation,
    ): JdbcTemplate {
        val dataSource = DriverManagerDataSource("jdbc:h2:mem:producer-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "")
        val jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.execute(
            """
            CREATE TABLE human_move_corpus_occurrence (
                id UUID PRIMARY KEY, source_run_id UUID NOT NULL,
                position_hash VARCHAR NOT NULL, pre_move_ply INTEGER NOT NULL,
                content_digest VARCHAR NOT NULL, covered_prefix INTEGER NOT NULL,
                qualifying_ordinal INTEGER NOT NULL, provider_game_id VARCHAR NOT NULL
            )
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            INSERT INTO human_move_corpus_occurrence
                (id, source_run_id, position_hash, pre_move_ply, content_digest, covered_prefix, qualifying_ordinal, provider_game_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            referenceId,
            population.sourceRunId,
            "single-position-hash",
            5,
            population.contentDigest,
            population.coveredPrefix,
            1,
            "provider-game-1",
        )
        return jdbcTemplate
    }

    private data class SingleDecisionHarness(
        val producer: EvaluationEvidenceProducerService,
        val input: EvaluationEvidenceProducerInput,
        val verifier: SelectedPopulationVerificationService,
        val snapshotService: EvaluationEvidenceSnapshotService,
        val engineAnalysisService: EngineAnalysisService,
        val occurrenceRepository: PositionOccurrenceRepository,
    )

    private fun referencePopulation() =
        EvaluationReferencePopulation(
            contentDigest = "a".repeat(64),
            sourceRunId = UUID.randomUUID(),
            coveredPrefix = 3,
            prefixN = 2,
            ratingBand = "1000-1200",
            minObservations = 1,
            calculationVersion = "v1",
            distributionSha256 = "b".repeat(64),
        )

    private fun game(
        account: ChessAccount,
        platformGameId: String,
    ) = Game(
        chessAccount = account,
        platformGameId = platformGameId,
        pgn = "[Event \"producer-test\"]\n\n1. e4 *",
        result = "win",
        whiteUsername = account.username,
        blackUsername = "opponent",
    )

    private fun occurrence(
        game: Game,
        position: Position,
        account: ChessAccount,
        plyNumber: Int,
    ) = PositionOccurrence(
        game = game,
        position = position,
        chessAccount = account,
        plyNumber = plyNumber,
        movePlayed = "e4",
        playerColor = "WHITE",
    )

    private fun verifiedContext(
        population: EvaluationReferencePopulation,
        occurrenceIds: Set<UUID>,
        positionHash: String = "canonical-position-hash",
        preMovePly: Int = 1,
        sourceRunId: UUID = population.sourceRunId,
    ): VerifiedSelectedPopulationContext {
        val projectionId = UUID.randomUUID()
        val occurrences =
            occurrenceIds
                .mapIndexed { index, id ->
                    id to
                        RetainedReferenceOccurrence(
                            sourceRunId = sourceRunId,
                            qualifyingOrdinal = index + 1,
                            preMovePly = preMovePly,
                            providerGameId = "provider-$index",
                            positionHash = positionHash,
                            movePlayed = "different-reference-move",
                            contentDigest = population.contentDigest,
                            coveredPrefix = population.coveredPrefix,
                        )
                }.toMap()
        return VerifiedSelectedPopulationContext(
            population = population,
            projection =
                HumanMoveCorpusProjection(
                    id = projectionId,
                    contentDigest = population.contentDigest,
                    sourceRunId = population.sourceRunId,
                    prefixN = population.prefixN,
                    ratingBand = population.ratingBand,
                    minObservations = population.minObservations,
                    calculationVersion = population.calculationVersion,
                    distributionSha256 = population.distributionSha256,
                    finalized = true,
                    verified = true,
                ),
            binding =
                HumanMoveCorpusOccurrenceBinding(
                    sourceRunId = population.sourceRunId,
                    contentDigest = population.contentDigest,
                    coveredPrefix = population.coveredPrefix,
                    occurrenceDigest = "c".repeat(64),
                    occurrenceCount = occurrences.size,
                ),
            occurrences = occurrences,
        )
    }

    private fun configuration() =
        EvaluationEvidenceConfiguration(
            thresholds = EvaluationEvidenceSnapshotService.APPROVED_THRESHOLDS,
            minMistakeCount = 1,
            minTimesReached = 1,
            color = "BOTH",
            platform = "CHESS_COM",
            observationWindowDays = null,
        )
}
