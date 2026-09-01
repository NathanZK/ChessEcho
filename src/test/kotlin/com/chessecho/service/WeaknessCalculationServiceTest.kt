package com.chessecho.service

import com.chessecho.config.ComparatorMethod
import com.chessecho.config.ConfidenceMethod
import com.chessecho.config.PracticalEvidenceProperties
import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.WeaknessAggregation
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.time.Instant
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
    }

    @Test
    fun `test dynamic minEvalLoss changes mistakeCount and classifies moves accurately`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
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
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
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
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
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
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
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
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
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

        assertEquals(1, weaknesses.size)
        assertEquals(newerInstant, weaknesses[0].lastSeenAt)
        // Game URLs must be returned newest-first
        assertEquals(listOf("https://www.chess.com/game/live/gameNew", "https://www.chess.com/game/live/gameOld"), weaknesses[0].gameUrls)
    }

    @Test
    fun `test lastSeenAt falls back to createdAt when playedAt is null`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
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

        assertEquals(1, weaknesses.size)
        assertEquals(occ.createdAt, weaknesses[0].lastSeenAt)
    }

    @Test
    fun `test recent weaknesses rank higher than otherwise equivalent older weaknesses`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
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
                user = AppUser(email = "recommendation-order@test.com"),
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
        val account = ChessAccount(user = AppUser(email = "scope@test.com"), platform = "CHESS_COM", username = "scope-user")
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
    ): Game =
        Game(
            chessAccount = ChessAccount(user = AppUser(email = "t"), platform = "P", username = "U"),
            platformGameId = platformGameId,
            timeControl = "bullet",
            pgn = "pgn",
            result = "win",
            playedAt = playedAt,
        )
}
