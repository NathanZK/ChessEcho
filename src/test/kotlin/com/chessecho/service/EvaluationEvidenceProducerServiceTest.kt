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
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvaluationEvidenceProducerServiceTest {
    @Test
    fun `producer input defensively copies and freezes its explicit selections`() {
        val player = EvaluationEvidencePlayer(UUID.randomUUID(), "player")
        val occurrenceId = UUID.randomUUID()
        val roster = mutableListOf(player)
        val selectedIds = mutableListOf(occurrenceId)
        val thresholds = mutableSetOf(0.30, 0.50, 0.80)
        val input =
            EvaluationEvidenceProducerInput(
                roster = roster,
                operationalOccurrenceIds = selectedIds,
                referencePopulation = referencePopulation(),
                configuration = configuration().copy(thresholds = thresholds),
            )

        roster.clear()
        selectedIds.clear()
        thresholds.clear()

        assertEquals(setOf(player), input.roster)
        assertEquals(setOf(occurrenceId), input.operationalOccurrenceIds)
        assertEquals(setOf(0.30, 0.50, 0.80), input.configuration.thresholds)
        assertFailsWith<UnsupportedOperationException> {
            (input.roster as MutableSet<*>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (input.operationalOccurrenceIds as MutableSet<*>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (input.configuration.thresholds as MutableSet<*>).clear()
        }
    }

    @Test
    fun `producer input rejects duplicate selected operational decisions`() {
        val occurrenceId = UUID.randomUUID()

        assertFailsWith<IllegalArgumentException> {
            EvaluationEvidenceProducerInput(
                roster = listOf(EvaluationEvidencePlayer(UUID.randomUUID(), "player")),
                operationalOccurrenceIds = listOf(occurrenceId, occurrenceId),
                referencePopulation = referencePopulation(),
                configuration = configuration(),
            )
        }
    }

    @Test
    fun `produces every selected game occurrence link for all matching reference occurrences`() {
        val player = ChessAccount(platform = "CHESS_COM", username = "player")
        val zeroRowPlayer = EvaluationEvidencePlayer(UUID.randomUUID(), "zero-row-player")
        val declaredPlayer = EvaluationEvidencePlayer(player.id, player.username)
        val position =
            Position(
                hash = "canonical-position-hash",
                fen = "position-fen",
            )
        val firstGame = game(player, "game-one")
        val secondGame = game(player, "game-two")
        val firstOperational = occurrence(firstGame, position, player, 1)
        val secondOperational = occurrence(secondGame, position, player, 1)
        val analysis = analysis(position)
        analysis.moveEvaluations.add(
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e4",
                evalCp = 25,
                evalLossFromBest = 0.75,
            ),
        )
        val firstReferenceId = UUID.randomUUID()
        val secondReferenceId = UUID.randomUUID()
        val outsidePrefixId = UUID.randomUUID()
        val otherRunId = UUID.randomUUID()
        val population = referencePopulation(prefixN = 2, coveredPrefix = 3)
        val occurrenceRows = jdbcTemplate()
        insertReferenceOccurrence(occurrenceRows, firstReferenceId, population, 1, "canonical-position-hash", 1)
        insertReferenceOccurrence(occurrenceRows, secondReferenceId, population, 2, "canonical-position-hash", 1)
        insertReferenceOccurrence(occurrenceRows, outsidePrefixId, population, 3, "canonical-position-hash", 1)
        insertReferenceOccurrence(
            occurrenceRows,
            UUID.randomUUID(),
            population.copy(sourceRunId = otherRunId),
            1,
            "canonical-position-hash",
            1,
        )
        insertReferenceOccurrence(occurrenceRows, UUID.randomUUID(), population, 1, "other-position-hash", 1)
        insertReferenceOccurrence(occurrenceRows, UUID.randomUUID(), population, 1, "canonical-position-hash", 2)

        val occurrenceRepository = mock<PositionOccurrenceRepository>()
        whenever(occurrenceRepository.findSelectedWithOperationalFacts(setOf(firstOperational.id, secondOperational.id)))
            .thenReturn(listOf(secondOperational, firstOperational))
        val analysisRepository = mock<EngineAnalysisRepository>()
        whenever(analysisRepository.findByPositionIdInWithMoveEvaluations(setOf(position.id))).thenReturn(listOf(analysis))
        val normalizer = mock<GameOutcomeNormalizer>()
        whenever(normalizer.normalize(any(), eq("WHITE"), eq("player")))
            .thenReturn(NormalizedGameOutcome(outcome = PracticalOutcome.WIN))
        val verifier = mock<SelectedPopulationVerificationService>()
        whenever(verifier.verify(eq(population), any())).thenAnswer { invocation ->
            val ids = invocation.getArgument<Set<UUID>>(1)
            verifiedContext(population, ids)
        }
        val snapshotService = mock<EvaluationEvidenceSnapshotService>()
        val persistedSnapshotId = UUID.randomUUID()
        whenever(snapshotService.persist(any(), eq(population))).thenReturn(persistedSnapshotId)
        val producer =
            EvaluationEvidenceProducerService(
                positionOccurrenceRepository = occurrenceRepository,
                engineAnalysisRepository = analysisRepository,
                jdbcTemplate = occurrenceRows,
                selectedPopulationVerificationService = verifier,
                evaluationEvidenceSnapshotService = snapshotService,
                gameOutcomeNormalizer = normalizer,
                engineAnalysisService = mock(),
            )
        val input =
            EvaluationEvidenceProducerInput(
                roster = listOf(declaredPlayer, zeroRowPlayer),
                operationalOccurrenceIds = listOf(firstOperational.id, secondOperational.id),
                referencePopulation = population,
                configuration = configuration(),
            )

        val actualSnapshotId = producer.produce(input)
        val snapshotCaptor = argumentCaptor<EvaluationEvidenceSnapshot>()
        val inOrder = inOrder(verifier, snapshotService)
        inOrder.verify(verifier).verify(population, setOf(firstReferenceId, secondReferenceId))
        inOrder.verify(snapshotService).persist(snapshotCaptor.capture(), eq(population))
        verify(occurrenceRepository).findSelectedWithOperationalFacts(setOf(firstOperational.id, secondOperational.id))
        val snapshot = snapshotCaptor.firstValue

        assertEquals(persistedSnapshotId, actualSnapshotId)
        assertEquals(setOf(declaredPlayer, zeroRowPlayer), snapshot.players)
        assertEquals(
            setOf(
                firstOperational.game.id to firstReferenceId,
                firstOperational.game.id to secondReferenceId,
                secondOperational.game.id to firstReferenceId,
                secondOperational.game.id to secondReferenceId,
            ),
            snapshot.rows.map { it.gameId to it.occurrenceId }.toSet(),
        )
        assertEquals(4, snapshot.rows.size)
        assertTrue(snapshot.rows.all { it.positionIdentity == "canonical-position-hash" && it.preMovePly == 1 })
        assertTrue(snapshot.rows.all { it.loss == 0.75 && it.observedOutcome == ObservedGameOutcome.WIN })
        assertTrue(snapshot.rows.all { it.objectiveOutcome == null })
        assertTrue(
            snapshot.rows.all {
                it.practicalCandidate == null && it.practicalEligible == null &&
                    it.practicalWins == null && it.practicalDraws == null && it.practicalLosses == null
            },
        )
        assertNull(snapshot.occurrenceEvidenceId)
        assertNull(snapshot.sourceRevision)
        assertNull(snapshot.engineIdentity)
        assertNull(snapshot.parserIdentity)
        assertEquals(configuration(), snapshot.configuration)
    }

    @Test
    fun `rejects selected decisions that have no exact in-population hash and ply match`() {
        val player = ChessAccount(platform = "CHESS_COM", username = "player")
        val position =
            Position(
                hash = "selected-position-hash",
                fen = "position-fen",
            )
        val game = game(player, "selected-game")
        val operational = occurrence(game, position, player, 5)
        val analysis = analysis(position)
        analysis.moveEvaluations.add(
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e4",
                evalCp = 25,
                evalLossFromBest = 0.75,
            ),
        )
        val population = referencePopulation(prefixN = 2, coveredPrefix = 3)
        val jdbcTemplate = jdbcTemplate()
        insertReferenceOccurrence(
            jdbcTemplate,
            UUID.randomUUID(),
            population,
            qualifyingOrdinal = 1,
            positionHash = "selected-position-hash",
            preMovePly = 4,
        )
        val occurrenceRepository = mock<PositionOccurrenceRepository>()
        whenever(occurrenceRepository.findSelectedWithOperationalFacts(setOf(operational.id)))
            .thenReturn(listOf(operational))
        val analysisRepository = mock<EngineAnalysisRepository>()
        whenever(analysisRepository.findByPositionIdInWithMoveEvaluations(setOf(position.id))).thenReturn(listOf(analysis))
        val verifier = mock<SelectedPopulationVerificationService>()
        val snapshotService = mock<EvaluationEvidenceSnapshotService>()
        val producer =
            EvaluationEvidenceProducerService(
                positionOccurrenceRepository = occurrenceRepository,
                engineAnalysisRepository = analysisRepository,
                jdbcTemplate = jdbcTemplate,
                selectedPopulationVerificationService = verifier,
                evaluationEvidenceSnapshotService = snapshotService,
                gameOutcomeNormalizer =
                    mock<GameOutcomeNormalizer>().also {
                        whenever(it.normalize(any(), eq("WHITE"), eq("player")))
                            .thenReturn(NormalizedGameOutcome(outcome = PracticalOutcome.WIN))
                    },
                engineAnalysisService = mock(),
            )

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            producer.produce(
                EvaluationEvidenceProducerInput(
                    roster = listOf(EvaluationEvidencePlayer(player.id, player.username)),
                    operationalOccurrenceIds = listOf(operational.id),
                    referencePopulation = population,
                    configuration = configuration(),
                ),
            )
        }

        verify(verifier, org.mockito.kotlin.never()).verify(any(), any())
        verify(snapshotService, org.mockito.kotlin.never()).persist(any(), any())
    }

    @Test
    fun `rejects selected operational IDs absent from the explicit repository lookup`() {
        val playerId = UUID.randomUUID()
        val selectedId = UUID.randomUUID()
        val occurrenceRepository = mock<PositionOccurrenceRepository>()
        whenever(occurrenceRepository.findSelectedWithOperationalFacts(setOf(selectedId))).thenReturn(emptyList())
        val verifier = mock<SelectedPopulationVerificationService>()
        val snapshotService = mock<EvaluationEvidenceSnapshotService>()
        val producer =
            EvaluationEvidenceProducerService(
                positionOccurrenceRepository = occurrenceRepository,
                engineAnalysisRepository = mock(),
                jdbcTemplate = jdbcTemplate(),
                selectedPopulationVerificationService = verifier,
                evaluationEvidenceSnapshotService = snapshotService,
                gameOutcomeNormalizer = mock(),
                engineAnalysisService = mock(),
            )

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            producer.produce(
                EvaluationEvidenceProducerInput(
                    roster = listOf(EvaluationEvidencePlayer(playerId, "player")),
                    operationalOccurrenceIds = listOf(selectedId),
                    referencePopulation = referencePopulation(prefixN = 2, coveredPrefix = 3),
                    configuration = configuration(),
                ),
            )
        }

        verify(verifier, org.mockito.kotlin.never()).verify(any(), any())
        verify(snapshotService, org.mockito.kotlin.never()).persist(any(), any())
    }

    @Test
    fun `rejects selected decisions outside the declared player roster`() {
        val harness =
            singleDecisionHarness(
                bestMoveEvalCp = 100,
                moveEvalCp = 25,
                persistedLoss = 0.4,
                declareDifferentPlayer = true,
            )

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            harness.producer.produce(harness.input)
        }

        verify(harness.verifier, never()).verify(any(), any())
        verify(harness.snapshotService, never()).persist(any(), any())
    }

    @Test
    fun `rejects selected decisions without engine analysis or exact move evaluation`() {
        listOf(
            singleDecisionHarness(
                bestMoveEvalCp = 100,
                moveEvalCp = 25,
                persistedLoss = 0.4,
                includeEngineAnalysis = false,
            ),
            singleDecisionHarness(
                bestMoveEvalCp = 100,
                moveEvalCp = 25,
                persistedLoss = 0.4,
                evaluatedMove = "d4",
            ),
        ).forEach { harness ->
            assertFailsWith<EvaluationEvidenceIntegrityException> {
                harness.producer.produce(harness.input)
            }

            verify(harness.verifier, never()).verify(any(), any())
            verify(harness.snapshotService, never()).persist(any(), any())
        }
    }

    @Test
    fun `fails closed when persisted zero loss has a missing best move centipawn input`() {
        val harness =
            singleDecisionHarness(
                bestMoveEvalCp = null,
                moveEvalCp = 25,
                persistedLoss = 0.0,
            )

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
                persistedLoss = 0.4,
                verifiedSourceRunId = UUID.randomUUID(),
            )

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            harness.producer.produce(harness.input)
        }

        verify(harness.verifier).verify(eq(harness.input.referencePopulation), any())
        verify(harness.snapshotService, never()).persist(any(), any())
    }

    @Test
    fun `fails closed when persisted loss has a missing played move centipawn input`() {
        val harness =
            singleDecisionHarness(
                bestMoveEvalCp = 100,
                moveEvalCp = null,
                persistedLoss = 0.4,
            )

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            harness.producer.produce(harness.input)
        }

        verify(harness.verifier, never()).verify(any(), any())
        verify(harness.snapshotService, never()).persist(any(), any())
    }

    @Test
    fun `preserves a genuine zero loss when both centipawn inputs are present`() {
        val harness =
            singleDecisionHarness(
                bestMoveEvalCp = 25,
                moveEvalCp = 25,
                persistedLoss = 0.0,
            )

        harness.producer.produce(harness.input)

        val snapshotCaptor = argumentCaptor<EvaluationEvidenceSnapshot>()
        verify(harness.snapshotService).persist(snapshotCaptor.capture(), eq(harness.input.referencePopulation))
        assertEquals(0.0, snapshotCaptor.firstValue.rows.single().loss)
    }

    @Test
    fun `fails closed when loss is absent and a required centipawn input is absent`() {
        val harness =
            singleDecisionHarness(
                bestMoveEvalCp = 100,
                moveEvalCp = null,
                persistedLoss = null,
            )

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
            val harness =
                singleDecisionHarness(
                    bestMoveEvalCp = 100,
                    moveEvalCp = 25,
                    persistedLoss = invalidLoss,
                )

            assertFailsWith<EvaluationEvidenceIntegrityException> {
                harness.producer.produce(harness.input)
            }

            verify(harness.verifier, never()).verify(any(), any())
            verify(harness.snapshotService, never()).persist(any(), any())
        }
    }

    @Test
    fun `derives absent loss through the existing deterministic calculation`() {
        val harness =
            singleDecisionHarness(
                bestMoveEvalCp = 100,
                moveEvalCp = 25,
                persistedLoss = null,
                calculatedLoss = 0.75,
            )

        harness.producer.produce(harness.input)

        verify(harness.engineAnalysisService).calculateEvalLoss(100, 25)
        val snapshotCaptor = argumentCaptor<EvaluationEvidenceSnapshot>()
        verify(harness.snapshotService).persist(snapshotCaptor.capture(), eq(harness.input.referencePopulation))
        assertEquals(0.75, snapshotCaptor.firstValue.rows.single().loss)
    }

    @Test
    fun `rejects a returned verification fact with a mismatched position hash`() {
        val harness =
            singleDecisionHarness(
                bestMoveEvalCp = 100,
                moveEvalCp = 25,
                persistedLoss = 0.4,
                verifiedPositionHash = "different-reference-hash",
            )

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            harness.producer.produce(harness.input)
        }

        verify(harness.verifier).verify(eq(harness.input.referencePopulation), any())
        verify(harness.snapshotService, never()).persist(any(), any())
    }

    @Test
    fun `rejects a returned verification fact with a mismatched pre move ply`() {
        val harness =
            singleDecisionHarness(
                bestMoveEvalCp = 100,
                moveEvalCp = 25,
                persistedLoss = 0.4,
                verifiedPreMovePly = 6,
            )

        assertFailsWith<EvaluationEvidenceIntegrityException> {
            harness.producer.produce(harness.input)
        }

        verify(harness.verifier).verify(eq(harness.input.referencePopulation), any())
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

        invalidConfigurations.forEach { invalidConfiguration ->
            val harness =
                singleDecisionHarness(
                    bestMoveEvalCp = 100,
                    moveEvalCp = 25,
                    persistedLoss = 0.4,
                    snapshotConfiguration = invalidConfiguration,
                )

            assertFailsWith<EvaluationEvidenceIntegrityException> {
                harness.producer.produce(harness.input)
            }

            verify(harness.occurrenceRepository, never()).findSelectedWithOperationalFacts(any())
            verify(harness.verifier, never()).verify(any(), any())
            verify(harness.snapshotService, never()).persist(any(), any())
        }
    }

    private fun referencePopulation() =
        EvaluationReferencePopulation(
            contentDigest = "a".repeat(64),
            sourceRunId = UUID.randomUUID(),
            coveredPrefix = 1,
            prefixN = 1,
            ratingBand = "1000-1200",
            minObservations = 1,
            calculationVersion = "v1",
            distributionSha256 = "b".repeat(64),
        )

    private fun referencePopulation(
        prefixN: Int,
        coveredPrefix: Int,
    ) = referencePopulation().copy(prefixN = prefixN, coveredPrefix = coveredPrefix)

    private fun jdbcTemplate(): JdbcTemplate {
        val dataSource =
            DriverManagerDataSource(
                "jdbc:h2:mem:producer-${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
            )
        val jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.execute(
            """
            CREATE TABLE human_move_corpus_occurrence (
                id UUID PRIMARY KEY,
                source_run_id UUID NOT NULL,
                position_hash VARCHAR NOT NULL,
                pre_move_ply INTEGER NOT NULL,
                content_digest VARCHAR NOT NULL,
                covered_prefix INTEGER NOT NULL,
                qualifying_ordinal INTEGER NOT NULL,
                provider_game_id VARCHAR NOT NULL
            )
            """.trimIndent(),
        )
        return jdbcTemplate
    }

    private fun insertReferenceOccurrence(
        jdbcTemplate: JdbcTemplate,
        id: UUID,
        population: EvaluationReferencePopulation,
        qualifyingOrdinal: Int,
        positionHash: String,
        preMovePly: Int,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO human_move_corpus_occurrence
                (id, source_run_id, position_hash, pre_move_ply, content_digest, covered_prefix, qualifying_ordinal, provider_game_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            population.sourceRunId,
            positionHash,
            preMovePly,
            population.contentDigest,
            population.coveredPrefix,
            qualifyingOrdinal,
            "provider-game-$qualifyingOrdinal",
        )
    }

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

    private fun analysis(position: Position) =
        EngineAnalysis(
            position = position,
            depth = 16,
            baselineEvalCp = 100,
            bestMove = "d4",
            bestMoveEvalCp = 100,
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
            occurrenceIds.associateWithIndexed { id, index ->
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
            }
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
    ): SingleDecisionHarness {
        val account = ChessAccount(platform = "CHESS_COM", username = "player")
        val position = Position(hash = "single-position-hash", fen = "position-fen")
        val game = game(account, "single-game")
        val operational = occurrence(game, position, account, 5)
        val analysis =
            analysis(position).also {
                it.bestMoveEvalCp = bestMoveEvalCp
                it.moveEvaluations.add(
                    MoveEvaluation(
                        engineAnalysis = it,
                        move = evaluatedMove,
                        evalCp = moveEvalCp,
                        evalLossFromBest = persistedLoss,
                    ),
                )
            }
        val population = referencePopulation(prefixN = 2, coveredPrefix = 3)
        val jdbcTemplate = jdbcTemplate()
        val referenceId = UUID.randomUUID()
        insertReferenceOccurrence(
            jdbcTemplate,
            referenceId,
            population,
            qualifyingOrdinal = 1,
            positionHash = "single-position-hash",
            preMovePly = 5,
        )
        val occurrenceRepository = mock<PositionOccurrenceRepository>()
        whenever(occurrenceRepository.findSelectedWithOperationalFacts(setOf(operational.id)))
            .thenReturn(listOf(operational))
        val analysisRepository = mock<EngineAnalysisRepository>()
        whenever(analysisRepository.findByPositionIdInWithMoveEvaluations(setOf(position.id)))
            .thenReturn(if (includeEngineAnalysis) listOf(analysis) else emptyList())
        val normalizer = mock<GameOutcomeNormalizer>()
        whenever(normalizer.normalize(any(), eq("WHITE"), eq("player")))
            .thenReturn(NormalizedGameOutcome(outcome = outcome))
        val verifier = mock<SelectedPopulationVerificationService>()
        whenever(verifier.verify(eq(population), any())).thenAnswer {
            verifiedContext(
                population,
                setOf(referenceId),
                positionHash = verifiedPositionHash,
                preMovePly = verifiedPreMovePly,
                sourceRunId = verifiedSourceRunId ?: population.sourceRunId,
            )
        }
        val snapshotService = mock<EvaluationEvidenceSnapshotService>()
        whenever(snapshotService.persist(any(), eq(population))).thenReturn(UUID.randomUUID())
        val engineAnalysisService = mock<EngineAnalysisService>()
        whenever(engineAnalysisService.calculateEvalLoss(100, 25)).thenReturn(calculatedLoss)
        val input =
            EvaluationEvidenceProducerInput(
                roster =
                    listOf(
                        EvaluationEvidencePlayer(
                            if (declareDifferentPlayer) UUID.randomUUID() else account.id,
                            account.username,
                        ),
                    ),
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

    private data class SingleDecisionHarness(
        val producer: EvaluationEvidenceProducerService,
        val input: EvaluationEvidenceProducerInput,
        val verifier: SelectedPopulationVerificationService,
        val snapshotService: EvaluationEvidenceSnapshotService,
        val engineAnalysisService: EngineAnalysisService,
        val occurrenceRepository: PositionOccurrenceRepository,
    )

    private fun <K, V> Set<K>.associateWithIndexed(valueSelector: (K, Int) -> V): Map<K, V> =
        mapIndexed { index, key -> key to valueSelector(key, index) }.toMap()

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
