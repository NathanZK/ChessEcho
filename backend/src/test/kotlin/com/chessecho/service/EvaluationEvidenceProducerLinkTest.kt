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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
import kotlin.test.assertFailsWith

class EvaluationEvidenceProducerLinkTest {
    @Test
    fun `producer input defensively copies and freezes its explicit selections`() {
        val player = ChessAccount(platform = "CHESS_COM", username = "player")
        val mutableRoster = mutableListOf(EvaluationEvidencePlayer(player.id, player.username))
        val mutableOccurrences = mutableListOf(UUID.randomUUID())
        val population = referencePopulation()
        val configuration = configuration()

        val input =
            EvaluationEvidenceProducerInput(
                roster = mutableRoster,
                operationalOccurrenceIds = mutableOccurrences,
                referencePopulation = population,
                configuration = configuration,
            )

        mutableRoster.add(EvaluationEvidencePlayer(UUID.randomUUID(), "injected-player"))
        mutableOccurrences.add(UUID.randomUUID())

        assertEquals(1, input.roster.size)
        assertEquals(1, input.operationalOccurrenceIds.size)
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
        val position = Position(hash = "canonical-position-hash", fen = "position-fen")
        val firstGame = game(player, "game-one")
        val secondGame = game(player, "game-two")
        val firstOperational = occurrence(firstGame, position, player, 1)
        val secondOperational = occurrence(secondGame, position, player, 1)
        val analysis =
            analysis(position).also {
                it.moveEvaluations.add(MoveEvaluation(engineAnalysis = it, move = "e4", evalCp = 25, evalLossFromBest = 0.75))
            }
        val firstReferenceId = UUID.randomUUID()
        val secondReferenceId = UUID.randomUUID()
        val population = referencePopulation(prefixN = 2, coveredPrefix = 3)
        val occurrenceRows = jdbcTemplate()
        insertTestOccurrences(occurrenceRows, population, firstReferenceId, secondReferenceId)

        val occurrenceRepository = mock<PositionOccurrenceRepository>()
        whenever(occurrenceRepository.findSelectedWithOperationalFacts(setOf(firstOperational.id, secondOperational.id)))
            .thenReturn(listOf(secondOperational, firstOperational))
        val analysisRepository = mock<EngineAnalysisRepository>()
        whenever(analysisRepository.findByPositionIdInWithMoveEvaluations(setOf(position.id))).thenReturn(listOf(analysis))
        val normalizer = mock<GameOutcomeNormalizer>()
        whenever(normalizer.normalize(any(), eq("WHITE"), eq("player")))
            .thenReturn(NormalizedGameOutcome(outcome = PracticalOutcome.WIN))
        val verifier = mock<SelectedPopulationVerificationService>()
        val snapshotService = mock<EvaluationEvidenceSnapshotService>()
        val persistedSnapshotId = UUID.randomUUID()
        mockVerificationAndPersistence(population, verifier, snapshotService, persistedSnapshotId)
        val producer =
            buildProducerService(occurrenceRepository, analysisRepository, occurrenceRows, verifier, snapshotService, normalizer)
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

        assertSnapshotResults(
            snapshot = snapshotCaptor.firstValue,
            actualSnapshotId = actualSnapshotId,
            persistedSnapshotId = persistedSnapshotId,
            declaredPlayer = declaredPlayer,
            zeroRowPlayer = zeroRowPlayer,
            firstGameId = firstOperational.game.id,
            secondGameId = secondOperational.game.id,
            firstReferenceId = firstReferenceId,
            secondReferenceId = secondReferenceId,
        )
    }

    private fun mockVerificationAndPersistence(
        population: EvaluationReferencePopulation,
        verifier: SelectedPopulationVerificationService,
        snapshotService: EvaluationEvidenceSnapshotService,
        persistedSnapshotId: UUID,
    ) {
        whenever(verifier.verify(eq(population), any())).thenAnswer { invocation ->
            val ids = invocation.getArgument<Set<UUID>>(1)
            verifiedContext(population, ids)
        }
        whenever(snapshotService.persist(any(), eq(population))).thenReturn(persistedSnapshotId)
    }

    private fun buildProducerService(
        occurrenceRepository: PositionOccurrenceRepository,
        analysisRepository: EngineAnalysisRepository,
        occurrenceRows: JdbcTemplate,
        verifier: SelectedPopulationVerificationService,
        snapshotService: EvaluationEvidenceSnapshotService,
        normalizer: GameOutcomeNormalizer,
    ): EvaluationEvidenceProducerService =
        EvaluationEvidenceProducerService(
            positionOccurrenceRepository = occurrenceRepository,
            engineAnalysisRepository = analysisRepository,
            jdbcTemplate = occurrenceRows,
            selectedPopulationVerificationService = verifier,
            evaluationEvidenceSnapshotService = snapshotService,
            gameOutcomeNormalizer = normalizer,
            engineAnalysisService = mock(),
        )

    private fun insertTestOccurrences(
        occurrenceRows: JdbcTemplate,
        population: EvaluationReferencePopulation,
        firstReferenceId: UUID,
        secondReferenceId: UUID,
    ) {
        val outsidePrefixId = UUID.randomUUID()
        val otherRunId = UUID.randomUUID()
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
    }

    private fun assertSnapshotResults(
        snapshot: EvaluationEvidenceSnapshot,
        actualSnapshotId: UUID,
        persistedSnapshotId: UUID,
        declaredPlayer: EvaluationEvidencePlayer,
        zeroRowPlayer: EvaluationEvidencePlayer,
        firstGameId: UUID,
        secondGameId: UUID,
        firstReferenceId: UUID,
        secondReferenceId: UUID,
    ) {
        assertEquals(persistedSnapshotId, actualSnapshotId)
        assertEquals(setOf(declaredPlayer, zeroRowPlayer), snapshot.players)
        assertEquals(
            setOf(
                firstGameId to firstReferenceId,
                firstGameId to secondReferenceId,
                secondGameId to firstReferenceId,
                secondGameId to secondReferenceId,
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
        val position = Position(hash = "selected-position-hash", fen = "position-fen")
        val game = game(player, "selected-game")
        val operational = occurrence(game, position, player, 5)
        val analysis =
            analysis(position).also {
                it.moveEvaluations.add(MoveEvaluation(engineAnalysis = it, move = "e4", evalCp = 25, evalLossFromBest = 0.75))
            }
        val population = referencePopulation(prefixN = 2, coveredPrefix = 3)
        val jdbcTemplate = jdbcTemplate()
        insertReferenceOccurrence(jdbcTemplate, UUID.randomUUID(), population, 1, "selected-position-hash", 4)
        val occurrenceRepository = mock<PositionOccurrenceRepository>()
        whenever(occurrenceRepository.findSelectedWithOperationalFacts(setOf(operational.id))).thenReturn(listOf(operational))
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

        verify(verifier, never()).verify(any(), any())
        verify(snapshotService, never()).persist(any(), any())
    }

    private fun referencePopulation(
        prefixN: Int = 1,
        coveredPrefix: Int = 1,
    ) = EvaluationReferencePopulation(
        contentDigest = "a".repeat(64),
        sourceRunId = UUID.randomUUID(),
        coveredPrefix = coveredPrefix,
        prefixN = prefixN,
        ratingBand = "1000-1200",
        minObservations = 1,
        calculationVersion = "v1",
        distributionSha256 = "b".repeat(64),
    )

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
    ): VerifiedSelectedPopulationContext {
        val projectionId = UUID.randomUUID()
        val occurrences =
            occurrenceIds.associateWithIndexed { id, index ->
                RetainedReferenceOccurrence(
                    sourceRunId = population.sourceRunId,
                    qualifyingOrdinal = index + 1,
                    preMovePly = 1,
                    providerGameId = "provider-$index",
                    positionHash = "canonical-position-hash",
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
