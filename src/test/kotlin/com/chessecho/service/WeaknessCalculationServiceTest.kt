package com.chessecho.service

import com.chessecho.config.ComparatorMethod
import com.chessecho.config.ConfidenceMethod
import com.chessecho.config.PracticalEvidenceProperties
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import com.chessecho.dto.WeaknessResponse
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.WeaknessAggregation
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class WeaknessCalculationServiceTest {
    @Mock
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Mock
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Mock
    private lateinit var engineAnalysisRepository: EngineAnalysisRepository

    @Mock
    private lateinit var practicalEvidenceService: PracticalEvidenceService

    @Mock
    private lateinit var weaknessPriorityPolicy: WeaknessPriorityPolicy

    @Mock
    private lateinit var puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository

    @Mock
    private lateinit var accountOwnershipService: AccountOwnershipService

    @Mock
    private lateinit var adaptivePuzzleSchedulingPolicy: AdaptivePuzzleSchedulingPolicy

    @InjectMocks
    private lateinit var weaknessCalculationService: WeaknessCalculationService

    @BeforeEach
    fun defaultPracticalFallback() {
        lenient().`when`(
            practicalEvidenceService.summarize(
                any(),
                any(),
                any(),
                any(),
                any(),
            ),
        ).thenReturn(emptyMap())

        val fallbackPolicy = WeaknessPriorityPolicy(PracticalEvidenceProperties())
        lenient().`when`(
            weaknessPriorityPolicy.evaluate(
                any(),
                any(),
                anyOrNull(),
            ),
        ).thenAnswer { invocation ->
            fallbackPolicy.evaluate(
                invocation.getArgument(0),
                invocation.getArgument(1),
                invocation.getArgument(2),
            )
        }

        lenient().`when`(
            puzzleSchedulingEventRepository.findHistory(
                any(),
                any(),
                any(),
                any(),
            ),
        ).thenReturn(emptyList())

        lenient().`when`(
            adaptivePuzzleSchedulingPolicy.schedule(
                any(),
                any(),
                any(),
                any(),
            ),
        ).thenAnswer { invocation -> invocation.getArgument<Double>(0) }

        ReflectionTestUtils.setField(weaknessCalculationService, "puzzleSchedulingEventRepository", puzzleSchedulingEventRepository)
        ReflectionTestUtils.setField(weaknessCalculationService, "adaptivePuzzleSchedulingPolicy", adaptivePuzzleSchedulingPolicy)
    }

    @Test
    fun `test dynamic minEvalLoss changes mistakeCount and classifies moves accurately`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash", fen = "fen")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        // Occurrences: move A (loss 0.2), move B (loss 0.3), move C (loss 0.8)
        val occA =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "A",
                playerColor = "WHITE",
            )
        val occB =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "B",
                playerColor = "WHITE",
            )
        val occC =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "C",
                playerColor = "WHITE",
            )

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(listOf(occA, occB, occC))

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "A", evalCp = 80, evalLossFromBest = 0.2))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "B", evalCp = 70, evalLossFromBest = 0.3))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "C", evalCp = 20, evalLossFromBest = 0.8))

        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val aggregation03 =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "WHITE",
                timesReached = 3,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 2,
                averageLoss = 0.55,
                rawTotalLoss = 1.1,
            )

        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = account.id,
                playerColor = "WHITE",
                minEvalLoss = 0.3,
                minTimesReached = 5,
                minMistakeCount = 1,
            ),
        ).thenReturn(listOf(aggregation03))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.3,
                minMistakeCount = 1,
            )

        println("WEAKNESSES SIZE: ${weaknesses.size}")
        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]
        assertEquals(2, w.mistakeCount)
        assertEquals(3, w.timesReached)
        assertEquals(1, w.acceptableMoves.size)
        assertEquals("A", w.acceptableMoves[0].move)
        assertEquals(2, w.movesPlayed.size)
        assertTrue(w.movesPlayed.any { it.move == "B" })
        assertTrue(w.movesPlayed.any { it.move == "C" })
    }

    @Test
    fun `test negative minEvalLoss throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            weaknessCalculationService.getWeaknesses(Platform.CHESS_COM, "nathan", PlayerColor.WHITE, minEvalLoss = -0.5)
        }
    }

    @Test
    fun `test playerColor BOTH calls queries with BOTH color parameter`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = account.id,
                playerColor = "BOTH",
                minEvalLoss = 0.8,
                minTimesReached = 5,
                minMistakeCount = 3,
            ),
        ).thenReturn(emptyList())

        val result = weaknessCalculationService.getWeaknesses(Platform.CHESS_COM, "nathan", PlayerColor.BOTH, minEvalLoss = 0.8)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `test changing minEvalLoss does not trigger Stockfish analysis`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = any(),
                playerColor = any(),
                minEvalLoss = any(),
                minTimesReached = any(),
                minMistakeCount = any(),
            ),
        ).thenReturn(emptyList())

        weaknessCalculationService.getWeaknesses(Platform.CHESS_COM, "nathan", PlayerColor.WHITE, minEvalLoss = 0.3)
        weaknessCalculationService.getWeaknesses(Platform.CHESS_COM, "nathan", PlayerColor.WHITE, minEvalLoss = 0.8)

        verify(engineAnalysisRepository, never()).save(any())
    }

    @Test
    fun `test bestMove is never classified as a historical mistake`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash_qh4", fen = "fen_qh4")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        // Historical occurrences: 5 games playing Qh4 (which IS bestMove), and 3 games playing Nf6 (eval loss 1.2)
        val occQh4List =
            (1..5).map {
                PositionOccurrence(
                    game = mockGame(),
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "Qh4",
                    playerColor = "WHITE",
                )
            }
        val occNf6List =
            (1..3).map {
                PositionOccurrence(
                    game = mockGame(),
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "Nf6",
                    playerColor = "WHITE",
                )
            }

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(occQh4List + occNf6List)

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "Qh4", bestMoveEvalCp = 100)
        // Qh4 has 0.0 loss (or even hypothetical artifact loss), Nf6 has 1.2 loss
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "Qh4", evalCp = 100, evalLossFromBest = 0.0))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "Nf6", evalCp = -20, evalLossFromBest = 1.2))

        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val aggregation =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "WHITE",
                timesReached = 8,
                bestMove = "Qh4",
                baselineEvalCp = 100,
                mistakeCount = 3,
                averageLoss = 1.2,
                rawTotalLoss = 3.6,
            )

        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = account.id,
                playerColor = "WHITE",
                minEvalLoss = 0.8,
                minTimesReached = 5,
                minMistakeCount = 3,
            ),
        ).thenReturn(listOf(aggregation))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 3,
            )

        println("WEAKNESSES SIZE: ${weaknesses.size}")
        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]

        // Qh4 must NOT be in movesPlayed, only Nf6 should be classified as a mistake
        assertEquals(3, w.mistakeCount)
        assertEquals(1, w.movesPlayed.size)
        assertEquals("Nf6", w.movesPlayed[0].move)
        assertEquals(3, w.movesPlayed[0].timesPlayed)
        assertEquals(1.2, w.movesPlayed[0].averageLoss)
        assertTrue(w.movesPlayed.none { it.move == "Qh4" })
    }

    @Test
    fun `test lastSeenAt derives from newest occurrence timestamp and prefers playedAt`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash1", fen = "fen1")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        val olderInstant = Instant.parse("2026-01-01T10:00:00Z")
        val newerInstant = Instant.parse("2026-08-01T10:00:00Z")

        val oldGame = mockGame("gameOld", playedAt = olderInstant)
        val newGame = mockGame("gameNew", playedAt = newerInstant)

        val occ1 =
            PositionOccurrence(
                game = oldGame,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "m1",
                playerColor = "WHITE",
            )
        val occ2 =
            PositionOccurrence(
                game = newGame,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "m1",
                playerColor = "WHITE",
            )

        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(listOf(occ1, occ2))

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "m1", evalCp = 0, evalLossFromBest = 1.0))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val agg =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "WHITE",
                timesReached = 5,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 2,
                averageLoss = 1.0,
                rawTotalLoss = 2.0,
            )
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 5, 2)).thenReturn(listOf(agg))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 2,
            )

        println("WEAKNESSES SIZE: ${weaknesses.size}")
        assertEquals(1, weaknesses.size)
        assertEquals(newerInstant, weaknesses[0].lastSeenAt)
        // Game URLs must be returned newest-first
        assertEquals(listOf("https://www.chess.com/game/live/gameNew", "https://www.chess.com/game/live/gameOld"), weaknesses[0].gameUrls)
    }

    @Test
    fun `test lastSeenAt falls back to createdAt when playedAt is null`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash2", fen = "fen2")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        val gameNoPlayedAt = mockGame("gameNull", playedAt = null)
        val occ =
            PositionOccurrence(
                game = gameNoPlayedAt,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "m1",
                playerColor = "WHITE",
            )

        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(listOf(occ))

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "m1", evalCp = 0, evalLossFromBest = 1.0))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val agg =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "WHITE",
                timesReached = 5,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 5, 1)).thenReturn(listOf(agg))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 1,
            )

        println("WEAKNESSES SIZE: ${weaknesses.size}")
        assertEquals(1, weaknesses.size)
        assertEquals(occ.createdAt, weaknesses[0].lastSeenAt)
    }

    @Test
    fun `test recent weaknesses rank higher than otherwise equivalent older weaknesses`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val posRecent = Position(hash = "recentHash", fen = "recentFen")
        val posOld = Position(hash = "oldHash", fen = "oldFen")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        val now = Instant.now()
        val oldDate = now.minusSeconds(180 * 86400L) // 180 days ago

        val gameRecent = mockGame("recentGame", playedAt = now)
        val gameOld = mockGame("oldGame", playedAt = oldDate)

        val occRecent =
            PositionOccurrence(
                game = gameRecent,
                position = posRecent,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "m1",
                playerColor = "WHITE",
            )
        val occOld =
            PositionOccurrence(
                game = gameOld,
                position = posOld,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "m1",
                playerColor = "WHITE",
            )

        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(listOf(occRecent, occOld))

        val analysisRecent = EngineAnalysis(position = posRecent, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysisRecent.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysisRecent, move = "m1", evalCp = 0, evalLossFromBest = 1.0))

        val analysisOld = EngineAnalysis(position = posOld, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysisOld.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysisOld, move = "m1", evalCp = 0, evalLossFromBest = 1.0))

        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysisRecent, analysisOld))

        val aggRecent =
            WeaknessAggregation(
                positionId = posRecent.id,
                fen = posRecent.fen,
                playerColor = "WHITE",
                timesReached = 5,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )
        val aggOld =
            WeaknessAggregation(
                positionId = posOld.id,
                fen = posOld.fen,
                playerColor = "WHITE",
                timesReached = 5,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )

        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 5, 1)).thenReturn(listOf(aggRecent, aggOld))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 1,
            )

        assertEquals(2, weaknesses.size)
        // Recent weakness must rank ahead of the old weakness due to recency decay priority weighting
        assertEquals(posRecent.id, weaknesses[0].positionId)
        assertEquals(posOld.id, weaknesses[1].positionId)
        assertTrue(weaknesses[0].priority > weaknesses[1].priority)
    }

    @Test
    fun `ranking on orders lower objective poor evidence before higher objective successful evidence`() {
        val account =
            ChessAccount(
                platform = "CHESS_COM",
                username = "recommendation-order",
            )
        val poorPosition =
            Position(
                id = UUID.fromString("00000000-0000-0000-0000-000000000020"),
                hash = "lower-objective-poor",
                fen = "lower-objective-poor-fen",
            )
        val successfulPosition =
            Position(
                id = UUID.fromString("00000000-0000-0000-0000-000000000010"),
                hash = "higher-objective-successful",
                fen = "higher-objective-successful-fen",
            )
        val playedAt = Instant.now()
        val poorOccurrences =
            (1..5).map {
                occurrence(account, poorPosition, "WHITE", "bad", "poor-game-$it", playedAt)
            }
        val successfulOccurrences =
            (1..5).map {
                occurrence(account, successfulPosition, "WHITE", "bad", "successful-game-$it", playedAt)
            }

        `when`(
            chessAccountRepository.findByPlatformAndUsernameIgnoreCase(
                "CHESS_COM",
                account.username,
            ),
        ).thenReturn(account)
        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                account.id,
                "WHITE",
                0.8,
                5,
                3,
            ),
        ).thenReturn(
            listOf(
                WeaknessAggregation(
                    positionId = poorPosition.id,
                    fen = poorPosition.fen,
                    playerColor = "WHITE",
                    timesReached = 5,
                    bestMove = "best",
                    baselineEvalCp = 100,
                    mistakeCount = 5,
                    averageLoss = 1.6,
                    rawTotalLoss = 8.0,
                ),
                WeaknessAggregation(
                    positionId = successfulPosition.id,
                    fen = successfulPosition.fen,
                    playerColor = "WHITE",
                    timesReached = 5,
                    bestMove = "best",
                    baselineEvalCp = 100,
                    mistakeCount = 5,
                    averageLoss = 1.8,
                    rawTotalLoss = 9.0,
                ),
            ),
        )
        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(poorOccurrences + successfulOccurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(
            listOf(
                analysis(poorPosition, evalLoss = 1.6),
                analysis(successfulPosition, evalLoss = 1.8),
            ),
        )

        val poorScope = PracticalEvidenceScope(account.id, poorPosition.id, "WHITE")
        val successfulScope = PracticalEvidenceScope(account.id, successfulPosition.id, "WHITE")
        `when`(
            practicalEvidenceService.summarize(
                eq(account.id),
                eq(account.username),
                any(),
                any(),
                any(),
            ),
        ).thenReturn(
            mapOf(
                poorScope to practicalSummary(poorScope, wins = 0, losses = 5),
                successfulScope to practicalSummary(successfulScope, wins = 5, losses = 0),
            ),
        )
        val rankingPolicy =
            WeaknessPriorityPolicy(
                PracticalEvidenceProperties(
                    rankingEnabled = true,
                    sampleFloor = 5,
                    comparatorMethod = ComparatorMethod.FIXED_SCORE_RATE,
                    comparatorScoreRate = 0.5,
                    confidenceMethod = ConfidenceMethod.BERNOULLI_WILSON_SCORE_POINTS_HALF_DRAW_CONSERVATIVE,
                    wilsonZScore = 1.0,
                    meaningfulDifference = 0.1,
                    maxPriorityAdjustment = 0.25,
                    observationWindowDays = 365,
                    policyVersion = "service-order-test-v1",
                ),
            )
        `when`(
            weaknessPriorityPolicy.evaluate(
                any(),
                any(),
                anyOrNull(),
            ),
        ).thenAnswer { invocation ->
            rankingPolicy.evaluate(
                invocation.getArgument(0),
                invocation.getArgument(1),
                invocation.getArgument(2),
            )
        }

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                account.username,
                PlayerColor.WHITE,
            )

        assertEquals(
            listOf(poorPosition.id, successfulPosition.id),
            weaknesses.map { it.positionId },
        )
        val poor = weaknesses.single { it.positionId == poorPosition.id }
        val successful = weaknesses.single { it.positionId == successfulPosition.id }
        assertEquals(8.0, poor.priority, 0.001)
        assertEquals(9.0, successful.priority, 0.001)
        assertEquals(
            listOf(successfulPosition.id, poorPosition.id),
            weaknesses.sortedByDescending { it.priority }.map { it.positionId },
        )
        assertEquals(10.0, poor.recommendationPriority, 0.001)
        assertEquals(6.75, successful.recommendationPriority, 0.001)
        assertEquals(PracticalAssessment.POOR, poor.practicalEvidence.practicalAssessment)
        assertEquals(PracticalAssessment.SUCCESSFUL, successful.practicalEvidence.practicalAssessment)
    }

    @Test
    fun `practical integration scopes by color uses all rows passes INACCURATE and applies total order`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "scope-user")
        val firstPosition =
            Position(
                id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                hash = "first-hash",
                fen = "first-fen",
            )
        val secondPosition =
            Position(
                id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                hash = "second-hash",
                fen = "second-fen",
            )
        val playedAt = Instant.parse("2026-08-31T12:00:00Z")
        val occurrences =
            buildList {
                repeat(3) { index ->
                    add(occurrence(account, firstPosition, "WHITE", "bad", "first-white-$index", playedAt))
                    add(occurrence(account, firstPosition, "BLACK", "bad", "first-black-$index", playedAt))
                    add(occurrence(account, secondPosition, "WHITE", "bad", "second-white-$index", playedAt))
                }
                add(occurrence(account, firstPosition, "WHITE", "unanalysed", "complete-position-evidence", playedAt))
            }

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "scope-user")).thenReturn(account)
        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                account.id,
                "BOTH",
                0.8,
                1,
                1,
            ),
        ).thenReturn(
            listOf(
                aggregation(firstPosition, "WHITE"),
                aggregation(firstPosition, "BLACK"),
                aggregation(secondPosition, "WHITE"),
            ),
        )
        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("BOTH"),
                any(),
            ),
        ).thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(
            listOf(analysis(firstPosition), analysis(secondPosition)),
        )

        val beforeRequest = Instant.now()
        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                account.username,
                PlayerColor.BOTH,
                minEvalLoss = 0.8,
                minMistakeCount = 1,
                minTimesReached = 1,
            )
        val afterRequest = Instant.now()

        assertEquals(
            listOf(
                firstPosition.id to "BLACK",
                firstPosition.id to "WHITE",
                secondPosition.id to "WHITE",
            ),
            weaknesses.map { it.positionId to it.playerColor },
        )
        weaknesses.forEach {
            assertEquals(it.priority, it.recommendationPriority)
            assertEquals(ObjectiveEvidenceState.INACCURATE, it.objectiveEvidenceState)
        }

        val occurrenceCaptor = argumentCaptor<List<PositionOccurrence>>()
        val scopeCaptor = argumentCaptor<Set<PracticalEvidenceScope>>()
        val asOfCaptor = argumentCaptor<Instant>()
        verify(practicalEvidenceService).summarize(
            eq(account.id),
            eq(account.username),
            occurrenceCaptor.capture(),
            scopeCaptor.capture(),
            asOfCaptor.capture(),
        )
        assertEquals(occurrences, occurrenceCaptor.firstValue)
        assertEquals(6, scopeCaptor.firstValue.size)
        assertTrue(
            scopeCaptor.firstValue.containsAll(
                setOf(
                    PracticalEvidenceScope(account.id, firstPosition.id, "WHITE"),
                    PracticalEvidenceScope(account.id, firstPosition.id, "WHITE", "bad"),
                    PracticalEvidenceScope(account.id, firstPosition.id, "BLACK"),
                    PracticalEvidenceScope(account.id, firstPosition.id, "BLACK", "bad"),
                    PracticalEvidenceScope(account.id, secondPosition.id, "WHITE"),
                    PracticalEvidenceScope(account.id, secondPosition.id, "WHITE", "bad"),
                ),
            ),
        )
        assertTrue(!asOfCaptor.firstValue.isBefore(beforeRequest))
        assertTrue(!asOfCaptor.firstValue.isAfter(afterRequest))

        val objectiveCaptor = argumentCaptor<ObjectiveEvidenceState>()
        verify(weaknessPriorityPolicy, times(3)).evaluate(
            objectiveCaptor.capture(),
            any(),
            anyOrNull(),
        )
        assertTrue(objectiveCaptor.allValues.all { it == ObjectiveEvidenceState.INACCURATE })
    }

    private fun aggregation(
        position: Position,
        color: String,
    ): WeaknessAggregation =
        WeaknessAggregation(
            positionId = position.id,
            fen = position.fen,
            playerColor = color,
            timesReached = 3,
            bestMove = "best",
            baselineEvalCp = 100,
            mistakeCount = 3,
            averageLoss = 1.0,
            rawTotalLoss = 3.0,
        )

    private fun analysis(
        position: Position,
        evalLoss: Double = 1.0,
    ): EngineAnalysis =
        EngineAnalysis(
            position = position,
            depth = 16,
            baselineEvalCp = 100,
            bestMove = "best",
            bestMoveEvalCp = 100,
        ).also {
            it.moveEvaluations.add(
                MoveEvaluation(
                    engineAnalysis = it,
                    move = "bad",
                    evalCp = 0,
                    evalLossFromBest = evalLoss,
                ),
            )
        }

    private fun practicalSummary(
        scope: PracticalEvidenceScope,
        wins: Int,
        losses: Int,
    ): PracticalEvidenceSummary =
        PracticalEvidenceSummary(
            scope = scope,
            candidateGames = wins + losses,
            eligibleGames = wins + losses,
            ineligibleGames = 0,
            excludedGames = 0,
            wins = wins,
            draws = 0,
            losses = losses,
            sideCorroborationConflictGames = 0,
            scoreRate = wins.toDouble() / (wins + losses),
        )

    private fun occurrence(
        account: ChessAccount,
        position: Position,
        color: String,
        san: String,
        gameId: String,
        playedAt: Instant,
    ): PositionOccurrence =
        PositionOccurrence(
            game =
                Game(
                    chessAccount = account,
                    platformGameId = gameId,
                    timeControl = "blitz",
                    pgn = "[Result \"1-0\"]\n\n1. e4",
                    result = "win",
                    playedAt = playedAt,
                ),
            position = position,
            chessAccount = account,
            plyNumber = 1,
            movePlayed = san,
            playerColor = color,
        )

    private fun mockGame(
        platformGameId: String = "123",
        playedAt: Instant? = null,
        timeControl: String? = "bullet",
    ): Game =
        Game(
            chessAccount = ChessAccount(platform = "P", username = "U"),
            platformGameId = platformGameId,
            timeControl = timeControl,
            pgn = "pgn",
            result = "win",
            playedAt = playedAt,
        )

    @Test
    fun `test time control partitions aggregate correctly and exclude unevaluated or unsupported occurrences`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash_tc", fen = "fen_tc")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "A", evalCp = 100, evalLossFromBest = 0.0),
        ) // Not a mistake
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "B", evalCp = 20, evalLossFromBest = 0.8)) // Mistake
        // Move "C" is unevaluated (missing)

        val occ1 =
            PositionOccurrence(
                game = mockGame(timeControl = "BLITZ"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "A",
                playerColor = "WHITE",
            )
        val occ2 =
            PositionOccurrence(
                game = mockGame(timeControl = "BLITZ"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "B",
                playerColor = "WHITE",
            )
        val occ3 =
            PositionOccurrence(
                game = mockGame(timeControl = "BLITZ"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "C",
                playerColor = "WHITE",
            )
        val occ4 =
            PositionOccurrence(
                game = mockGame(timeControl = "RAPID"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "B",
                playerColor = "WHITE",
            )
        val occ5 =
            PositionOccurrence(
                game = mockGame(timeControl = "DAILY"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "B",
                playerColor = "WHITE",
            )
        val occ6 =
            PositionOccurrence(
                game = mockGame(timeControl = null),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "B",
                playerColor = "WHITE",
            )

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(listOf(occ1, occ2, occ3, occ4, occ5, occ6))

        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val aggregation =
            WeaknessAggregation(
                positionId = position.id, fen = position.fen, playerColor = "WHITE",
                timesReached = 6, bestMove = "best", baselineEvalCp = 100,
                mistakeCount = 4, averageLoss = 0.8, rawTotalLoss = 3.2,
            )

        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = eq(account.id),
                playerColor = eq("WHITE"),
                minEvalLoss = eq(0.8),
                minTimesReached = eq(5),
                minMistakeCount = eq(3L),
            ),
        ).thenReturn(listOf(aggregation))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 3,
            )

        println("WEAKNESSES SIZE: ${weaknesses.size}")
        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]

        // Global counts
        assertEquals(4, w.mistakeCount)

        val tcStats = w.timeControlStats
        assertTrue(tcStats != null, "timeControlStats should not be null")

        // Check BLITZ
        val blitzStats = tcStats!!["BLITZ"]
        assertTrue(blitzStats != null, "BLITZ stats missing")
        assertEquals(2, blitzStats!!.timesReached) // occ1, occ2. occ3 is unevaluated.
        assertEquals(1, blitzStats.mistakeCount) // occ2
        assertEquals(0.5, blitzStats.mistakeRate, 0.001) // 1 / 2
        assertEquals(0.8, blitzStats.averageLoss, 0.001) // 0.8 / 1

        // Check RAPID
        val rapidStats = tcStats["RAPID"]
        assertTrue(rapidStats != null, "RAPID stats missing")
        assertEquals(1, rapidStats!!.timesReached)
        assertEquals(1, rapidStats.mistakeCount)
        assertEquals(1.0, rapidStats.mistakeRate, 0.001)
        assertEquals(0.8, rapidStats.averageLoss, 0.001)

        // Ensure unsupported/null are not present
        assertTrue(!tcStats.containsKey("DAILY"), "DAILY should be excluded")
        assertTrue(!tcStats.containsKey("null"), "null should be excluded")
    }

    @Test
    fun `test concrete regression scenario for time control partitions`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash_reg", fen = "fen_reg")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = 100,
                bestMove = "best",
                bestMoveEvalCp = 100,
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "GOOD", evalCp = 100, evalLossFromBest = 0.0),
        )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "BAD", evalCp = 20, evalLossFromBest = 0.8),
        )

        val occurrences = mutableListOf<PositionOccurrence>()

        // 4 evaluated Bullet occurrences (2 good, 2 bad -> 2 mistakes)
        occurrences.add(
            PositionOccurrence(
                game = mockGame(timeControl = "BULLET"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "GOOD",
                playerColor = "WHITE",
            ),
        )
        occurrences.add(
            PositionOccurrence(
                game = mockGame(timeControl = "BULLET"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "GOOD",
                playerColor = "WHITE",
            ),
        )
        occurrences.add(
            PositionOccurrence(
                game = mockGame(timeControl = "BULLET"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "BAD",
                playerColor = "WHITE",
            ),
        )
        occurrences.add(
            PositionOccurrence(
                game = mockGame(timeControl = "BULLET"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "BAD",
                playerColor = "WHITE",
            ),
        )

        // 6 evaluated occurrences with missing or unsupported time controls (5 good, 1 bad)
        // This brings the global evaluated occurrences to 10 and global mistakes to 3.
        occurrences.add(
            PositionOccurrence(
                game = mockGame(timeControl = null),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "BAD",
                playerColor = "WHITE",
            ),
        )
        for (i in 1..5) {
            occurrences.add(
                PositionOccurrence(
                    game = mockGame(timeControl = "UNKNOWN"),
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "GOOD",
                    playerColor = "WHITE",
                ),
            )
        }

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(occurrences)

        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val aggregation =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "WHITE",
                timesReached = 10,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 3,
                averageLoss = 0.8,
                rawTotalLoss = 2.4,
            )

        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = eq(account.id),
                playerColor = eq("WHITE"),
                minEvalLoss = eq(0.8),
                minTimesReached = eq(5),
                minMistakeCount = eq(3L),
            ),
        ).thenReturn(listOf(aggregation))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 3,
            )

        println("WEAKNESSES SIZE: ${weaknesses.size}")
        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]

        // Global stats
        assertEquals(3, w.mistakeCount)
        assertEquals(10, w.timesReached)
        assertEquals(30.0, w.mistakeRate, 0.001)

        val tcStats = w.timeControlStats
        assertTrue(tcStats != null, "timeControlStats should not be null")

        // Bullet stats
        val bulletStats = tcStats!!["BULLET"]
        assertTrue(bulletStats != null, "BULLET stats missing")
        assertEquals(4, bulletStats!!.timesReached)
        assertEquals(2, bulletStats.mistakeCount)
        assertEquals(0.5, bulletStats.mistakeRate, 0.001)
        assertEquals(0.8, bulletStats.averageLoss, 0.001)

        // Ensure unsupported/null are not present
        assertTrue(!tcStats.containsKey("UNKNOWN"), "UNKNOWN should be excluded")
        assertTrue(!tcStats.containsKey("null"), "null should be excluded")
    }

    @Test
    fun `test phase classification correctly partitions weaknesses and omits empty buckets`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash-phase", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "BAD", evalCp = 0, evalLossFromBest = 1.0))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "GOOD", evalCp = 100, evalLossFromBest = 0.0))

        val occurrences = mutableListOf<PositionOccurrence>()

        repeat(2) {
            occurrences.add(
                PositionOccurrence(
                    game = mockGame("g1"),
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "BAD",
                    playerColor = "WHITE",
                ),
            )
        }
        occurrences.add(
            PositionOccurrence(
                game = mockGame("g1"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "GOOD",
                playerColor = "WHITE",
            ),
        )

        occurrences.add(
            PositionOccurrence(
                game = mockGame("g2"),
                position = position,
                chessAccount = account,
                plyNumber = 30,
                movePlayed = "BAD",
                playerColor = "WHITE",
            ),
        )

        occurrences.add(
            PositionOccurrence(
                game = mockGame("g3"),
                position = position,
                chessAccount = account,
                plyNumber = 60,
                movePlayed = "BAD",
                playerColor = "WHITE",
            ),
        )

        occurrences.add(
            PositionOccurrence(
                game = mockGame("g6"),
                position = position,
                chessAccount = account,
                plyNumber = 60,
                movePlayed = "UNEVALUATED",
                playerColor = "WHITE",
            ),
        )

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(occurrences)

        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val aggregation =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "WHITE",
                timesReached = 6,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 4,
                averageLoss = 1.0,
                rawTotalLoss = 4.0,
            )

        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = eq(account.id),
                playerColor = eq("WHITE"),
                minEvalLoss = eq(0.8),
                minTimesReached = eq(5),
                minMistakeCount = eq(3L),
            ),
        ).thenReturn(listOf(aggregation))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 3,
            )

        println("WEAKNESSES SIZE: ${weaknesses.size}")
        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]

        assertEquals(4, w.mistakeCount)
        assertEquals(6, w.timesReached)

        val pStats = w.phaseStats
        assertTrue(pStats != null, "phaseStats should not be null")

        val openingStats = pStats!!["OPENING"]
        assertTrue(openingStats != null, "OPENING stats missing")
        assertEquals(3, openingStats!!.timesReached)
        assertEquals(2, openingStats.mistakeCount)
        assertEquals(2.0 / 3.0, openingStats.mistakeRate, 1e-4)
        assertEquals(1.0, openingStats.averageLoss, 1e-4)

        val midStats = pStats["MIDDLEGAME"]
        assertTrue(midStats != null, "MIDDLEGAME stats missing")
        assertEquals(2, midStats!!.timesReached)
        assertEquals(2, midStats.mistakeCount)
        assertEquals(1.0, midStats.mistakeRate, 1e-4)
        assertEquals(1.0, midStats.averageLoss, 1e-4)

        val endStats = pStats["ENDGAME"]
        assertTrue(
            endStats == null,
            "ENDGAME stats should not be present because the FEN has 32 pieces and moveNumber > 14 forces MIDDLEGAME",
        )
    }

    private fun testSinglePhaseClassification(
        fen: String,
        plyNumber: Int,
    ): String {
        val account = ChessAccount(platform = "CHESS_COM", username = "phaseUser")
        val position = Position(hash = "pos-phase-${UUID.randomUUID()}", fen = fen)

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "phaseUser")).thenReturn(account)

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "BAD", evalCp = 0, evalLossFromBest = 1.0))

        val occurrences =
            listOf(
                PositionOccurrence(
                    game = mockGame("g1"),
                    position = position,
                    chessAccount = account,
                    plyNumber = plyNumber,
                    movePlayed = "BAD",
                    playerColor = "WHITE",
                ),
            )

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(occurrences)

        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val aggregation =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "WHITE",
                timesReached = 1,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )

        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = eq(account.id),
                playerColor = eq("WHITE"),
                minEvalLoss = eq(0.8),
                minTimesReached = eq(1),
                minMistakeCount = eq(1L),
            ),
        ).thenReturn(listOf(aggregation))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "phaseUser",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1,
            )
        assertEquals(1, weaknesses.size)
        val phaseStats = weaknesses[0].phaseStats
        assertTrue(phaseStats != null, "phaseStats must not be null")
        assertEquals(1, phaseStats!!.size, "Expected exactly one classified phase")
        return phaseStats.keys.first()
    }

    @Test
    fun `test phase classification corrupt FEN with fewer than 8 ranks defaults to OPENING`() {
        val corruptFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w KQkq - 0 1"
        assertEquals("OPENING", testSinglePhaseClassification(corruptFen, plyNumber = 40))
    }

    @Test
    fun `test phase classification exact mmCount boundary at 6 and heavy piece endgame are ENDGAME`() {
        // Heavy-piece endgame (Q+R vs Q+R, mmCount = 4)
        val heavyEndgameFen = "4k2r/4q3/8/8/8/8/4Q3/4K2R w - - 0 1"
        assertEquals("ENDGAME", testSinglePhaseClassification(heavyEndgameFen, plyNumber = 10))

        // Exact mmCount = 6 boundary (Black: r, b, r; White: R, B, R)
        val mm6Fen = "r2k1b1r/8/8/8/8/8/8/R2K1B1R w - - 0 1"
        assertEquals("ENDGAME", testSinglePhaseClassification(mm6Fen, plyNumber = 10))
    }

    @Test
    fun `test phase classification exact mmCount boundary at 10 is queenless middlegame and 11 can be OPENING`() {
        // mmCount = 10 boundary: Queenless middlegame (5 pieces on each side)
        val mm10Fen = "r1b1kbnr/8/8/8/8/8/8/R1B1KBNR w - - 0 1"
        assertEquals("MIDDLEGAME", testSinglePhaseClassification(mm10Fen, plyNumber = 2))

        // mmCount = 11: 11 minor/major pieces, both backranks populated (>= 4 non-pawn pieces), early move
        val mm11Fen = "r1b1kbnr/8/8/8/8/8/4N3/R1B1KBNR w - - 0 1"
        assertEquals("OPENING", testSinglePhaseClassification(mm11Fen, plyNumber = 2))
    }

    @Test
    fun `test phase classification home-rank sparsity triggers MIDDLEGAME when mmCount greater than 6`() {
        // White rank 1 has only 3 pieces (R, K, R < 4), Black rank 8 has 8 pieces, mmCount = 12 > 6
        val sparseWhiteRank1Fen = "rnbqkbnr/8/8/8/8/8/2NN4/R3K2R w - - 0 1"
        assertEquals("MIDDLEGAME", testSinglePhaseClassification(sparseWhiteRank1Fen, plyNumber = 2))

        // Black rank 8 has only 3 pieces (r, k, r < 4), White rank 1 has 8 pieces, mmCount = 12 > 6
        val sparseBlackRank8Fen = "r3k2r/2nn4/8/8/8/8/8/RNBQKBNR w - - 0 1"
        assertEquals("MIDDLEGAME", testSinglePhaseClassification(sparseBlackRank8Fen, plyNumber = 2))
    }

    @Test
    fun `test phase classification populated backranks with mmCount greater than 10 before move 15 remains OPENING`() {
        val standardFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        assertEquals("OPENING", testSinglePhaseClassification(standardFen, plyNumber = 20))
    }

    @Test
    fun `test phase classification home-rank boundary at exactly 4 non-pawn pieces remains OPENING`() {
        // White rank 1 has exactly 4 pieces (R, B, K, R), Black rank 8 has 8 pieces, rank 2 has N, mmCount = 11 > 10, move <= 14
        val white4Fen = "rnbqkbnr/8/8/8/8/8/2N5/R1B1K2R w - - 0 1"
        assertEquals("OPENING", testSinglePhaseClassification(white4Fen, plyNumber = 2))

        // Black rank 8 has exactly 4 pieces (r, b, k, r), White rank 1 has 8 pieces, rank 7 has n, mmCount = 11 > 10, move <= 14
        val black4Fen = "r1b1k2r/2n5/8/8/8/8/8/RNBQKBNR w - - 0 1"
        assertEquals("OPENING", testSinglePhaseClassification(black4Fen, plyNumber = 2))
    }

    @Test
    fun `test phase classification move number boundary transitions from OPENING at ply 28 to MIDDLEGAME at ply 29 and 30`() {
        val standardFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        // Ply 28 -> move 14 -> OPENING
        assertEquals("OPENING", testSinglePhaseClassification(standardFen, plyNumber = 28))

        // Ply 29 -> move 15 -> MIDDLEGAME
        assertEquals("MIDDLEGAME", testSinglePhaseClassification(standardFen, plyNumber = 29))

        // Ply 30 -> move 15 -> MIDDLEGAME
        assertEquals("MIDDLEGAME", testSinglePhaseClassification(standardFen, plyNumber = 30))
    }

    @Test
    fun `test authenticated scheduling delegates history query with correct scoping and mappings`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "schedUser")
        val position = Position(hash = "pos-sched", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        val appUserId = UUID.randomUUID()
        val principal = AuthenticatedPrincipal(appUserId = appUserId, devPrincipal = false)

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "schedUser")).thenReturn(account)

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "BAD", evalCp = 0, evalLossFromBest = 1.0))

        val game = mockGame("g-sched")
        val occurrence =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "BAD",
                playerColor = "WHITE",
            )

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(listOf(occurrence))

        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val aggregation =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "WHITE",
                timesReached = 1,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )

        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = eq(account.id),
                playerColor = eq("WHITE"),
                minEvalLoss = eq(0.8),
                minTimesReached = eq(1),
                minMistakeCount = eq(1L),
            ),
        ).thenReturn(listOf(aggregation))

        val eventTime = Instant.now().minusSeconds(120)
        val schedulingEventEntity =
            PuzzleSchedulingEvent(
                chessAccount = account,
                position = position,
                playerColor = "WHITE",
                eventType = SchedulingEventType.GAME_MISTAKE,
                occurredAt = eventTime,
                sourceOccurrence = occurrence,
            )

        `when`(
            puzzleSchedulingEventRepository.findHistory(
                eq(appUserId),
                eq(account.id),
                eq(setOf(position.id)),
                eq("WHITE"),
            ),
        ).thenReturn(listOf(schedulingEventEntity))

        val eventsCaptor = argumentCaptor<List<com.chessecho.service.SchedulingEvent>>()
        val positionIdCaptor = argumentCaptor<UUID>()
        val priorityCaptor = argumentCaptor<Double>()
        val timeCaptor = argumentCaptor<Instant>()

        `when`(
            adaptivePuzzleSchedulingPolicy.schedule(
                priorityCaptor.capture(),
                positionIdCaptor.capture(),
                eventsCaptor.capture(),
                timeCaptor.capture(),
            ),
        ).thenReturn(42.0)

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "schedUser",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1,
                principal = principal,
            )

        assertEquals(1, weaknesses.size)

        // Verify query delegation scoping
        verify(puzzleSchedulingEventRepository).findHistory(
            appUserId,
            account.id,
            setOf(position.id),
            "WHITE",
        )

        // Verify policy invocation arguments
        assertEquals(position.id, positionIdCaptor.firstValue)
        assertEquals(weaknesses[0].recommendationPriority, priorityCaptor.firstValue)
        assertEquals(1, eventsCaptor.firstValue.size)
        val mappedEvent = eventsCaptor.firstValue[0]
        assertEquals(SchedulingEventType.GAME_MISTAKE, mappedEvent.type)
        assertEquals(eventTime, mappedEvent.occurredAt)
        assertEquals(occurrence.id, mappedEvent.sourceOccurrenceId)
    }

    @Test
    fun `test authenticated scheduling re-ranks weaknesses by scheduledScore with secondary tiebreakers`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "schedTiebreaker")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "schedTiebreaker")).thenReturn(account)

        val posA = Position(hash = "hash-A", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        val posB = Position(hash = "hash-B", fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")

        val analysisA = EngineAnalysis(position = posA, depth = 16, baselineEvalCp = 100, bestMove = "bestA", bestMoveEvalCp = 100)
        analysisA.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysisA, move = "BAD", evalCp = 0, evalLossFromBest = 1.0))
        val analysisB = EngineAnalysis(position = posB, depth = 16, baselineEvalCp = 100, bestMove = "bestB", bestMoveEvalCp = 100)
        analysisB.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysisB, move = "BAD", evalCp = 0, evalLossFromBest = 1.0))

        val occurrences =
            listOf(
                PositionOccurrence(
                    game = mockGame("gA"),
                    position = posA,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "BAD",
                    playerColor = "WHITE",
                ),
                PositionOccurrence(
                    game = mockGame("gB"),
                    position = posB,
                    chessAccount = account,
                    plyNumber = 2,
                    movePlayed = "BAD",
                    playerColor = "WHITE",
                ),
            )

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysisA, analysisB))

        val aggA =
            WeaknessAggregation(
                positionId = posA.id,
                fen = posA.fen,
                playerColor = "WHITE",
                timesReached = 1,
                bestMove = "bestA",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )
        val aggB =
            WeaknessAggregation(
                positionId = posB.id,
                fen = posB.fen,
                playerColor = "WHITE",
                timesReached = 1,
                bestMove = "bestB",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )

        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                eq(account.id),
                eq("WHITE"),
                eq(0.8),
                eq(1),
                eq(1L),
            ),
        ).thenReturn(listOf(aggA, aggB))

        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)

        // Test 1: scheduledScore DESC re-ranks regardless of baseline recommendation priority
        `when`(adaptivePuzzleSchedulingPolicy.schedule(any(), eq(posA.id), any(), any())).thenReturn(5.0)
        `when`(adaptivePuzzleSchedulingPolicy.schedule(any(), eq(posB.id), any(), any())).thenReturn(10.0)

        val reRanked =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "schedTiebreaker",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1,
                principal = principal,
            )
        assertEquals(listOf(posB.id, posA.id), reRanked.map { it.positionId })

        // Test 2: Secondary tiebreaker positionId.toString() ASC when scheduledScore and priorities are equal
        `when`(adaptivePuzzleSchedulingPolicy.schedule(any(), any(), any(), any())).thenReturn(10.0)
        val tiedResults =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "schedTiebreaker",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1,
                principal = principal,
            )
        assertEquals(2, tiedResults.size)
        val expectedFirst = if (posA.id.toString() < posB.id.toString()) posA.id else posB.id
        assertEquals(expectedFirst, tiedResults[0].positionId)
    }

    @Test
    fun `test authenticated scheduling zero-history parity with unauthenticated baseline`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "schedZero")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "schedZero")).thenReturn(account)

        val pos1 = Position(hash = "hash-1", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        val pos2 = Position(hash = "hash-2", fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")

        val analysis1 =
            EngineAnalysis(
                position = pos1,
                depth = 16,
                baselineEvalCp = 100,
                bestMove = "best1",
                bestMoveEvalCp = 100,
            )
        analysis1.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis1, move = "BAD", evalCp = 0, evalLossFromBest = 1.0))
        val analysis2 =
            EngineAnalysis(
                position = pos2,
                depth = 16,
                baselineEvalCp = 100,
                bestMove = "best2",
                bestMoveEvalCp = 100,
            )
        analysis2.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis2, move = "BAD", evalCp = 0, evalLossFromBest = 1.0))

        val occurrences =
            listOf(
                PositionOccurrence(
                    game = mockGame("g1"),
                    position = pos1,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "BAD",
                    playerColor = "WHITE",
                ),
                PositionOccurrence(
                    game = mockGame("g2"),
                    position = pos2,
                    chessAccount = account,
                    plyNumber = 2,
                    movePlayed = "BAD",
                    playerColor = "WHITE",
                ),
            )

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis1, analysis2))

        val agg1 =
            WeaknessAggregation(
                positionId = pos1.id,
                fen = pos1.fen,
                playerColor = "WHITE",
                timesReached = 1,
                bestMove = "best1",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )
        val agg2 =
            WeaknessAggregation(
                positionId = pos2.id,
                fen = pos2.fen,
                playerColor = "WHITE",
                timesReached = 1,
                bestMove = "best2",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )

        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                eq(account.id),
                eq("WHITE"),
                eq(0.8),
                eq(1),
                eq(1L),
            ),
        ).thenReturn(listOf(agg1, agg2))

        val guestWeaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "schedZero",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1,
                principal = null,
            )

        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)
        `when`(puzzleSchedulingEventRepository.findHistory(any(), any(), any(), any())).thenReturn(emptyList())
        `when`(adaptivePuzzleSchedulingPolicy.schedule(any(), any(), eq(emptyList()), any())).thenAnswer { it.getArgument<Double>(0) }

        val authenticatedWeaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "schedZero",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1,
                principal = principal,
            )

        assertEquals(guestWeaknesses.map { it.positionId }, authenticatedWeaknesses.map { it.positionId })
    }

    @Test
    fun `test authenticated scheduling graceful fallback when policy is null or repository is null`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "schedFallback")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "schedFallback")).thenReturn(account)

        val pos = Position(hash = "hash-fb", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        val analysis =
            EngineAnalysis(
                position = pos,
                depth = 16,
                baselineEvalCp = 100,
                bestMove = "best",
                bestMoveEvalCp = 100,
            )
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "BAD", evalCp = 0, evalLossFromBest = 1.0))
        val occurrences =
            listOf(
                PositionOccurrence(
                    game = mockGame("g"),
                    position = pos,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "BAD",
                    playerColor = "WHITE",
                ),
            )

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))
        val agg =
            WeaknessAggregation(
                positionId = pos.id,
                fen = pos.fen,
                playerColor = "WHITE",
                timesReached = 1,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )
        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                eq(account.id),
                eq("WHITE"),
                eq(0.8),
                eq(1),
                eq(1L),
            ),
        ).thenReturn(listOf(agg))

        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)

        // Fallback 1: adaptivePuzzleSchedulingPolicy == null
        ReflectionTestUtils.setField(weaknessCalculationService, "adaptivePuzzleSchedulingPolicy", null)
        val nullPolicyResult =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "schedFallback",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1,
                principal = principal,
            )
        assertEquals(1, nullPolicyResult.size)

        // Restore policy, null out repository
        ReflectionTestUtils.setField(weaknessCalculationService, "adaptivePuzzleSchedulingPolicy", adaptivePuzzleSchedulingPolicy)
        ReflectionTestUtils.setField(weaknessCalculationService, "puzzleSchedulingEventRepository", null)
        val nullRepoResult =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "schedFallback",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1,
                principal = principal,
            )
        assertEquals(1, nullRepoResult.size)
    }

    @Test
    fun `test WeaknessResponse DTO does not expose scheduledScore field`() {
        val declaredFieldNames = WeaknessResponse::class.java.declaredFields.map { it.name }
        assertTrue("scheduledScore" !in declaredFieldNames, "WeaknessResponse DTO must not expose scheduledScore")
    }

    @Test
    fun `test top-level averageLoss and objective priority arithmetic with multiple occurrences and rawRate less than 1`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "arithmeticUser")
        val position = Position(hash = "pos-arithmetic", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "arithmeticUser")).thenReturn(account)

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "BAD1", evalCp = -20, evalLossFromBest = 1.2))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "BAD2", evalCp = 20, evalLossFromBest = 0.8))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "GOOD", evalCp = 90, evalLossFromBest = 0.1))

        val gameToday = mockGame("g-today")
        val occurrences =
            listOf(
                PositionOccurrence(
                    game = gameToday,
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "BAD1",
                    playerColor = "WHITE",
                ),
                PositionOccurrence(
                    game = gameToday,
                    position = position,
                    chessAccount = account,
                    plyNumber = 2,
                    movePlayed = "BAD2",
                    playerColor = "WHITE",
                ),
                PositionOccurrence(
                    game = gameToday,
                    position = position,
                    chessAccount = account,
                    plyNumber = 3,
                    movePlayed = "GOOD",
                    playerColor = "WHITE",
                ),
                PositionOccurrence(
                    game = gameToday,
                    position = position,
                    chessAccount = account,
                    plyNumber = 4,
                    movePlayed = "GOOD",
                    playerColor = "WHITE",
                ),
            )

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val aggregation =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "WHITE",
                timesReached = 4,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 2,
                averageLoss = 1.0,
                rawTotalLoss = 2.0,
            )

        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = eq(account.id),
                playerColor = eq("WHITE"),
                minEvalLoss = eq(0.8),
                minTimesReached = eq(4),
                minMistakeCount = eq(2L),
            ),
        ).thenReturn(listOf(aggregation))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "arithmeticUser",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 4,
                minMistakeCount = 2,
            )

        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]
        assertEquals(2, w.mistakeCount)
        assertEquals(4, w.timesReached)
        assertEquals(50.0, w.mistakeRate)
        assertEquals(1.0, w.averageLoss, 1e-4)
        assertEquals(1.0, w.priority, 1e-4)
    }

    @Test
    fun `test opening context consensus matching ECO and ECOUrl returns openingContext`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "ecoUser")
        val position = Position(hash = "pos-eco-match", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "ecoUser")).thenReturn(account)

        val pgnMatch = "[ECO \"B23\"]\n[ECOUrl \"https://www.chess.com/openings/Sicilian-Defense\"]\n[White \"ecoUser\"]\n\n1. e4 c5 2. Nc3"
        val game1 = Game(chessAccount = account, platformGameId = "g-eco-1", pgn = pgnMatch)
        val game2 = Game(chessAccount = account, platformGameId = "g-eco-2", pgn = pgnMatch)

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "BAD", evalCp = 0, evalLossFromBest = 1.0))

        val occurrences =
            listOf(
                PositionOccurrence(
                    game = game1,
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "BAD",
                    playerColor = "WHITE",
                ),
                PositionOccurrence(
                    game = game2,
                    position = position,
                    chessAccount = account,
                    plyNumber = 2,
                    movePlayed = "BAD",
                    playerColor = "WHITE",
                ),
            )

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))
        val agg =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "WHITE",
                timesReached = 2,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 2,
                averageLoss = 1.0,
                rawTotalLoss = 2.0,
            )
        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                eq(account.id),
                eq("WHITE"),
                eq(0.8),
                eq(2),
                eq(2L),
            ),
        ).thenReturn(listOf(agg))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "ecoUser",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 2,
                minMistakeCount = 2,
            )
        assertEquals(1, weaknesses.size)
        val opening = weaknesses[0].openingContext
        assertTrue(opening != null, "Opening context should be present for matching consensus")
        assertEquals("B23", opening!!.eco)
        assertEquals("https://www.chess.com/openings/Sicilian-Defense", opening.ecoUrl)
    }

    @Test
    fun `test opening context non-consensus and missing tags return null openingContext`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "ecoMismatchUser")
        val position = Position(hash = "pos-eco-mismatch", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "ecoMismatchUser")).thenReturn(account)

        val pgn1 = "[ECO \"B23\"]\n[ECOUrl \"https://www.chess.com/openings/Sicilian-Defense\"]\n\n"
        val pgn2 = "[ECO \"B20\"]\n[ECOUrl \"https://www.chess.com/openings/Sicilian-Defense-Other\"]\n\n"
        val game1 = Game(chessAccount = account, platformGameId = "g-mismatch-1", pgn = pgn1)
        val game2 = Game(chessAccount = account, platformGameId = "g-mismatch-2", pgn = pgn2)

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "BAD", evalCp = 0, evalLossFromBest = 1.0))

        val occurrences =
            listOf(
                PositionOccurrence(
                    game = game1,
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "BAD",
                    playerColor = "WHITE",
                ),
                PositionOccurrence(
                    game = game2,
                    position = position,
                    chessAccount = account,
                    plyNumber = 2,
                    movePlayed = "BAD",
                    playerColor = "WHITE",
                ),
            )

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))
        val agg =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "WHITE",
                timesReached = 2,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 2,
                averageLoss = 1.0,
                rawTotalLoss = 2.0,
            )
        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                eq(account.id),
                eq("WHITE"),
                eq(0.8),
                eq(2),
                eq(2L),
            ),
        ).thenReturn(listOf(agg))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "ecoMismatchUser",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 2,
                minMistakeCount = 2,
            )
        assertEquals(1, weaknesses.size)
        assertTrue(weaknesses[0].openingContext == null, "Opening context should be null for mismatched ECO tags")
    }

    @Test
    fun `test forced-mate outcomes checkmate blunders excluded from acceptableMoves, prolongation retained, null loss excluded`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash_mate", fen = "6k1/5ppp/8/8/8/8/5PPP/4R1K1 w - - 0 1")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                bestMove = "Re8#",
                bestMoveEvalMate = 1,
                baselineEvalMate = 1,
            )
        // Acceptable best move (loss 0.0)
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "Re8#", evalMate = 1, evalLossFromBest = 0.0),
        )
        // Acceptable winning mate prolongation (mate in 3 -> loss 0.20 < 0.80)
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "Qxf7+", evalMate = 3, evalLossFromBest = 0.20),
        )
        // Checkmate blunder into losing mate (loss 19.5 >= 0.80)
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "f3", evalMate = -1, evalLossFromBest = 19.5),
        )
        // Unevaluated move (null loss)
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "a3", evalLossFromBest = null),
        )

        val occ1 =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "f3",
                playerColor = "WHITE",
            )
        val occ2 =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 3,
                movePlayed = "f3",
                playerColor = "WHITE",
            )
        val occ3 =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 5,
                movePlayed = "a3",
                playerColor = "WHITE",
            )

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(listOf(occ1, occ2, occ3))

        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val aggregation =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "WHITE",
                timesReached = 3,
                bestMove = "Re8#",
                baselineEvalCp = null,
                mistakeCount = 2,
                averageLoss = 19.5,
                rawTotalLoss = 39.0,
            )

        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = eq(account.id),
                playerColor = eq("WHITE"),
                minEvalLoss = eq(0.8),
                minTimesReached = eq(2),
                minMistakeCount = eq(2L),
            ),
        ).thenReturn(listOf(aggregation))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 2,
                minMistakeCount = 2,
            )

        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]
        assertEquals(2, w.mistakeCount)

        // Acceptable moves must contain Re8# and Qxf7+, but exclude f3 (blunder) and a3 (null loss)
        val acceptableMovesList = w.acceptableMoves.map { it.move }
        assertTrue(acceptableMovesList.contains("Re8#"), "Should contain best move Re8#")
        assertTrue(acceptableMovesList.contains("Qxf7+"), "Should contain prolongation move Qxf7+")
        assertFalse(acceptableMovesList.contains("f3"), "Should exclude checkmate blunder f3")
        assertFalse(acceptableMovesList.contains("a3"), "Should exclude unevaluated move a3")

        // Moves played must only contain f3; a3 must be skipped
        assertEquals(1, w.movesPlayed.size)
        assertEquals("f3", w.movesPlayed[0].move)
        assertEquals(2, w.movesPlayed[0].timesPlayed)
    }

    @Test
    fun `test recency weighting attenuates priority for historical games based on days old`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash-recency", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        // Game played exactly 182 days ago (approx half a year)
        val pastInstant = Instant.now().minus(182, ChronoUnit.DAYS)
        val game = mockGame("gamePast", playedAt = pastInstant)

        val occ =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "e4",
                playerColor = "WHITE",
            )

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "d4", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "e4", evalCp = 0, evalLossFromBest = 1.0))

        `when`(positionOccurrenceRepository.countByChessAccountId(account.id)).thenReturn(1)
        `when`(positionOccurrenceRepository.countByChessAccountIdAndPlayerColorOrBoth(account.id, "WHITE")).thenReturn(1)
        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()),
        ).thenReturn(listOf(occ))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val aggregation =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "WHITE",
                timesReached = 1,
                bestMove = "d4",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )

        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = eq(account.id),
                playerColor = eq("WHITE"),
                minEvalLoss = eq(0.8),
                minTimesReached = eq(1),
                minMistakeCount = eq(1L),
            ),
        ).thenReturn(listOf(aggregation))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "nathan",
                playerColor = PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1,
            )

        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]

        // daysOld between pastInstant and Instant.now() is 182 days
        // expected weight = max(0.1, 1.0 - (182.0 / 365.0)) = 1.0 - 0.49863 = ~0.50137
        // unmutated priority: priorityScore * (mistakeCount / timesReached) = 1.0 * weight * (1 / 1) = ~0.50137
        // mutated MathMutator (daysOld * 365.0): 1.0 - (182 * 365.0) = -66429.0 -> clamped to 0.1 -> priority = 0.1
        assertTrue(w.priority > 0.40, "Expected recency-weighted priority around 0.50, but was ${w.priority}")
        assertEquals(1.0 - (182.0 / 365.0), w.priority, 0.05)
    }

    @Test
    fun `test gameUrl generates lichess URL for LICHESS platform occurrences`() {
        val account = ChessAccount(platform = "LICHESS", username = "lichessUser")
        val position = Position(hash = "pos-lichess", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "lichessUser")).thenReturn(account)

        val game = mockGame(platformGameId = "gameLichess123")
        val occ =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "e4",
                playerColor = "WHITE",
            )
        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "d4", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "e4", evalCp = 0, evalLossFromBest = 1.0))

        `when`(positionOccurrenceRepository.countByChessAccountId(account.id)).thenReturn(1)
        `when`(positionOccurrenceRepository.countByChessAccountIdAndPlayerColorOrBoth(account.id, "WHITE")).thenReturn(1)
        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()),
        ).thenReturn(listOf(occ))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val aggregation =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "WHITE",
                timesReached = 1,
                bestMove = "d4",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )

        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = eq(account.id),
                playerColor = eq("WHITE"),
                minEvalLoss = eq(0.8),
                minTimesReached = eq(1),
                minMistakeCount = eq(1L),
            ),
        ).thenReturn(listOf(aggregation))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "lichessUser",
                playerColor = PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1,
            )

        assertEquals(1, weaknesses.size)
        assertEquals(listOf("https://lichess.org/gameLichess123"), weaknesses[0].gameUrls)
    }

    @Test
    fun `test getWeaknesses accepts minEvalLoss of exactly zero without exception`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = eq(account.id),
                playerColor = eq("WHITE"),
                minEvalLoss = eq(0.0),
                minTimesReached = any(),
                minMistakeCount = any(),
            ),
        ).thenReturn(emptyList())

        // Must succeed without throwing IllegalArgumentException for boundary value 0.0
        val result =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "nathan",
                playerColor = PlayerColor.WHITE,
                minEvalLoss = 0.0,
            )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `test getWeaknesses throws AccountSelectionRequiredException when principal is present without accountId`() {
        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)
        ReflectionTestUtils.setField(weaknessCalculationService, "accountOwnershipService", accountOwnershipService)
        whenever(accountOwnershipService.resolvePrivateRead(Platform.CHESS_COM, "nathan", principal))
            .thenThrow(AccountSelectionRequiredException())

        assertThrows<AccountSelectionRequiredException> {
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "nathan",
                playerColor = PlayerColor.WHITE,
                principal = principal,
                accountId = null,
            )
        }

        verify(accountOwnershipService).resolvePrivateRead(Platform.CHESS_COM, "nathan", principal)
        verify(chessAccountRepository, never()).findByPlatformAndUsernameIgnoreCase(any(), any())
    }

    @Test
    fun `test getWeaknesses delegates to resolveSharedAccount when accountId is provided`() {
        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)
        val accountId = UUID.randomUUID()
        val account = ChessAccount(id = accountId, platform = "CHESS_COM", username = "nathan")

        ReflectionTestUtils.setField(weaknessCalculationService, "accountOwnershipService", accountOwnershipService)
        whenever(accountOwnershipService.resolveSharedAccount(accountId, principal)).thenReturn(account)

        `when`(positionOccurrenceRepository.countByChessAccountId(accountId)).thenReturn(0)
        `when`(
            positionOccurrenceRepository.countByChessAccountIdAndPlayerColorOrBoth(
                accountId,
                "WHITE",
            ),
        ).thenReturn(0)
        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = accountId,
                playerColor = "WHITE",
                minEvalLoss = WeaknessCalculationService.DEFAULT_MIN_EVAL_LOSS,
                minTimesReached = WeaknessCalculationService.DEFAULT_MIN_TIMES_REACHED,
                minMistakeCount = WeaknessCalculationService.DEFAULT_MIN_MISTAKE_COUNT.toLong(),
            ),
        ).thenReturn(emptyList())

        val result =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "nathan",
                playerColor = PlayerColor.WHITE,
                principal = principal,
                accountId = accountId,
            )

        assertTrue(result.isEmpty())
        verify(accountOwnershipService).resolveSharedAccount(accountId, principal)
        verify(chessAccountRepository, never()).findByPlatformAndUsernameIgnoreCase(any(), any())
    }

    private fun setupDualColorPositionOccurrences(
        account: ChessAccount,
        position: Position,
    ) {
        `when`(positionOccurrenceRepository.countByChessAccountId(account.id)).thenReturn(10)
        `when`(
            positionOccurrenceRepository.countByChessAccountIdAndPlayerColorOrBoth(
                account.id,
                "BOTH",
            ),
        ).thenReturn(10)

        val occWhite =
            (1..5).map {
                PositionOccurrence(
                    game = mockGame(),
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "e4",
                    playerColor = "WHITE",
                )
            }
        val occBlack =
            (1..5).map {
                PositionOccurrence(
                    game = mockGame(),
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "e4",
                    playerColor = "BLACK",
                )
            }

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("BOTH"),
                eq(setOf(position.id)),
            ),
        ).thenReturn(occWhite + occBlack)

        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = 100,
                bestMove = "e4",
                bestMoveEvalCp = 100,
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e4",
                evalCp = -50,
                evalLossFromBest = 1.5,
            ),
        )

        `when`(
            engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(position.id)),
        ).thenReturn(listOf(analysis))

        val aggWhite =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "WHITE",
                timesReached = 5,
                bestMove = "e4",
                baselineEvalCp = 100,
                mistakeCount = 5,
                averageLoss = 1.5,
                rawTotalLoss = 7.5,
            )
        val aggBlack =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "BLACK",
                timesReached = 5,
                bestMove = "e4",
                baselineEvalCp = 100,
                mistakeCount = 5,
                averageLoss = 1.5,
                rawTotalLoss = 7.5,
            )

        // Intentionally return WHITE first from repository so that the test verifies active tie-breaking by .thenBy { it.playerColor }
        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = account.id,
                playerColor = "BOTH",
                minEvalLoss = WeaknessCalculationService.DEFAULT_MIN_EVAL_LOSS,
                minTimesReached = WeaknessCalculationService.DEFAULT_MIN_TIMES_REACHED,
                minMistakeCount = WeaknessCalculationService.DEFAULT_MIN_MISTAKE_COUNT.toLong(),
            ),
        ).thenReturn(listOf(aggWhite, aggBlack))
    }

    @Test
    fun `test dual-color tie breaking orders by playerColor ascending in unauthenticated baseline sort`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(id = UUID.randomUUID(), hash = "dual_pos_hash", fen = "dual_pos_fen")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        setupDualColorPositionOccurrences(account, position)

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "nathan",
                playerColor = PlayerColor.BOTH,
                principal = null,
                accountId = null,
            )

        assertEquals(2, weaknesses.size)
        assertEquals(weaknesses[0].positionId, weaknesses[1].positionId)
        assertEquals(weaknesses[0].priority, weaknesses[1].priority)
        assertEquals(weaknesses[0].recommendationPriority, weaknesses[1].recommendationPriority)
        assertEquals("BLACK", weaknesses[0].playerColor)
        assertEquals("WHITE", weaknesses[1].playerColor)
    }

    @Test
    fun `test dual-color tie breaking orders by playerColor ascending in authenticated unscheduled sort`() {
        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)
        val accountId = UUID.randomUUID()
        val account = ChessAccount(id = accountId, platform = "CHESS_COM", username = "nathan")
        val position = Position(id = UUID.randomUUID(), hash = "dual_pos_hash_unsched", fen = "dual_pos_fen_unsched")

        whenever(accountOwnershipService.resolveSharedAccount(accountId, principal)).thenReturn(account)
        setupDualColorPositionOccurrences(account, position)

        val unscheduledService =
            WeaknessCalculationService(
                chessAccountRepository = chessAccountRepository,
                positionOccurrenceRepository = positionOccurrenceRepository,
                engineAnalysisRepository = engineAnalysisRepository,
                practicalEvidenceService = practicalEvidenceService,
                weaknessPriorityPolicy = weaknessPriorityPolicy,
            )
        ReflectionTestUtils.setField(unscheduledService, "accountOwnershipService", accountOwnershipService)

        val weaknesses =
            unscheduledService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "nathan",
                playerColor = PlayerColor.BOTH,
                principal = principal,
                accountId = accountId,
            )

        assertEquals(2, weaknesses.size)
        assertEquals(weaknesses[0].positionId, weaknesses[1].positionId)
        assertEquals(weaknesses[0].priority, weaknesses[1].priority)
        assertEquals(weaknesses[0].recommendationPriority, weaknesses[1].recommendationPriority)
        assertEquals("BLACK", weaknesses[0].playerColor)
        assertEquals("WHITE", weaknesses[1].playerColor)
    }

    @Test
    fun `test dual-color tie breaking orders by playerColor ascending in authenticated scheduled sort`() {
        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)
        val accountId = UUID.randomUUID()
        val account = ChessAccount(id = accountId, platform = "CHESS_COM", username = "nathan")
        val position = Position(id = UUID.randomUUID(), hash = "dual_pos_hash_sched", fen = "dual_pos_fen_sched")

        ReflectionTestUtils.setField(weaknessCalculationService, "accountOwnershipService", accountOwnershipService)
        whenever(accountOwnershipService.resolveSharedAccount(accountId, principal)).thenReturn(account)
        setupDualColorPositionOccurrences(account, position)

        whenever(adaptivePuzzleSchedulingPolicy.schedule(any(), any(), any(), any())).thenReturn(10.0)

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "nathan",
                playerColor = PlayerColor.BOTH,
                principal = principal,
                accountId = accountId,
            )

        assertEquals(2, weaknesses.size)
        assertEquals(weaknesses[0].positionId, weaknesses[1].positionId)
        assertEquals(weaknesses[0].priority, weaknesses[1].priority)
        assertEquals(weaknesses[0].recommendationPriority, weaknesses[1].recommendationPriority)
        assertEquals("BLACK", weaknesses[0].playerColor)
        assertEquals("WHITE", weaknesses[1].playerColor)
    }
}
