package com.chessecho.service

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
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.WeaknessAggregation
import com.chessecho.testsupport.WeaknessCalculationTestUtils.aggregation
import com.chessecho.testsupport.WeaknessCalculationTestUtils.analysis
import com.chessecho.testsupport.WeaknessCalculationTestUtils.buildArithmeticAnalysis
import com.chessecho.testsupport.WeaknessCalculationTestUtils.buildArithmeticOccurrences
import com.chessecho.testsupport.WeaknessCalculationTestUtils.buildPracticalOccurrences
import com.chessecho.testsupport.WeaknessCalculationTestUtils.createAgg
import com.chessecho.testsupport.WeaknessCalculationTestUtils.createAnalysis
import com.chessecho.testsupport.WeaknessCalculationTestUtils.createAnalysisWithLoss
import com.chessecho.testsupport.WeaknessCalculationTestUtils.mockGame
import com.chessecho.testsupport.WeaknessCalculationTestUtils.occurrence
import com.chessecho.testsupport.WeaknessCalculationTestUtils.setupDefaultServiceMocks
import com.chessecho.testsupport.WeaknessCalculationTestUtils.setupPracticalRanking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
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
        setupDefaultServiceMocks(
            weaknessCalculationService,
            practicalEvidenceService,
            weaknessPriorityPolicy,
            puzzleSchedulingEventRepository,
            adaptivePuzzleSchedulingPolicy,
        )
    }

    @Test
    fun `test WeaknessCalculationService primary constructor injects all required dependencies cleanly`() {
        val service =
            WeaknessCalculationService(
                chessAccountRepository,
                positionOccurrenceRepository,
                engineAnalysisRepository,
                practicalEvidenceService,
                weaknessPriorityPolicy,
                accountOwnershipService,
                puzzleSchedulingEventRepository,
                adaptivePuzzleSchedulingPolicy,
            )
        assertNotNull(service)
    }

    @Test
    fun `test getWeaknesses does not execute redundant full-table count queries`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = eq(account.id),
                playerColor = eq("WHITE"),
                minEvalLoss = any(),
                minTimesReached = any(),
                minMistakeCount = any(),
            ),
        ).thenReturn(emptyList())

        weaknessCalculationService.getWeaknesses(
            platform = Platform.CHESS_COM,
            username = "nathan",
            playerColor = PlayerColor.WHITE,
        )

        verify(positionOccurrenceRepository, never()).countByChessAccountId(any())
        verify(positionOccurrenceRepository, never()).countByChessAccountIdAndPlayerColorOrBoth(any(), any())
    }

    @Test
    fun `test dynamic minEvalLoss changes mistakeCount and classifies moves accurately`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash", fen = "fen")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        val occurrences = listOf("A", "B", "C").map { occurrence(mockGame(), position, account, movePlayed = it) }
        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(occurrences)

        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = 100,
                bestMove = "best",
                bestMoveEvalCp = 100,
            )
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "A", evalCp = 80, evalLossFromBest = 0.2))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "B", evalCp = 70, evalLossFromBest = 0.3))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "C", evalCp = 20, evalLossFromBest = 0.8))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val agg = createAgg(position.id, position.fen, reached = 3, mistakes = 2, avgLoss = 0.55, totalLoss = 1.1)
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.3, 5, 1)).thenReturn(listOf(agg))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "nathan",
                playerColor = PlayerColor.WHITE,
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
        assertTrue(w.movesPlayed.any { it.move == "B" } && w.movesPlayed.any { it.move == "C" })
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
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "BOTH", 0.8, 5, 3)).thenReturn(emptyList())

        val result = weaknessCalculationService.getWeaknesses(Platform.CHESS_COM, "nathan", PlayerColor.BOTH, minEvalLoss = 0.8)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `test changing minEvalLoss does not trigger Stockfish analysis`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        `when`(positionOccurrenceRepository.findWeaknessAggregations(any(), any(), any(), any(), any())).thenReturn(emptyList())

        weaknessCalculationService.getWeaknesses(Platform.CHESS_COM, "nathan", PlayerColor.WHITE, minEvalLoss = 0.3)
        weaknessCalculationService.getWeaknesses(Platform.CHESS_COM, "nathan", PlayerColor.WHITE, minEvalLoss = 0.8)

        verify(engineAnalysisRepository, never()).save(any())
    }

    @Test
    fun `test bestMove is never classified as a historical mistake`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash_qh4", fen = "fen_qh4")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        val occList =
            (1..5).map { occurrence(mockGame(), position, account, movePlayed = "Qh4") } +
                (1..3).map { occurrence(mockGame(), position, account, movePlayed = "Nf6") }
        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(occList)

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "Qh4", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "Qh4", evalCp = 100, evalLossFromBest = 0.0))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "Nf6", evalCp = -20, evalLossFromBest = 1.2))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val agg = createAgg(position.id, position.fen, reached = 8, bestMove = "Qh4", mistakes = 3, avgLoss = 1.2, totalLoss = 3.6)
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 5, 3)).thenReturn(listOf(agg))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "nathan",
                playerColor = PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 3,
            )

        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]
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
        val occ1 = occurrence(mockGame("gameOld", playedAt = olderInstant), position, account, movePlayed = "m1")
        val occ2 = occurrence(mockGame("gameNew", playedAt = newerInstant), position, account, movePlayed = "m1")
        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(listOf(occ1, occ2))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any()))
            .thenReturn(listOf(createAnalysisWithLoss(position, "m1", 1.0)))

        val agg = createAgg(position.id, position.fen, reached = 5, mistakes = 2, avgLoss = 1.0, totalLoss = 2.0)
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 5, 2)).thenReturn(listOf(agg))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "nathan",
                playerColor = PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 2,
            )

        assertEquals(1, weaknesses.size)
        assertEquals(newerInstant, weaknesses[0].lastSeenAt)
        assertEquals(
            listOf(
                "https://www.chess.com/game/live/gameNew",
                "https://www.chess.com/game/live/gameOld",
            ),
            weaknesses[0].gameUrls,
        )
    }

    @Test
    fun `test lastSeenAt falls back to createdAt when playedAt is null`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash2", fen = "fen2")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        val occ = occurrence(mockGame("gameNull", playedAt = null), position, account, movePlayed = "m1")
        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(listOf(occ))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any()))
            .thenReturn(listOf(createAnalysisWithLoss(position, "m1", 1.0)))

        val agg = createAgg(position.id, position.fen, reached = 5, mistakes = 1, avgLoss = 1.0, totalLoss = 1.0)
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 5, 1)).thenReturn(listOf(agg))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "nathan",
                playerColor = PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 1,
            )

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
        val oldDate = now.minusSeconds(180 * 86400L)
        val occRecent = occurrence(mockGame("recentGame", playedAt = now), posRecent, account, movePlayed = "m1")
        val occOld = occurrence(mockGame("oldGame", playedAt = oldDate), posOld, account, movePlayed = "m1")
        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(listOf(occRecent, occOld))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any()))
            .thenReturn(
                listOf(
                    createAnalysisWithLoss(posRecent, "m1", 1.0),
                    createAnalysisWithLoss(posOld, "m1", 1.0),
                ),
            )

        val aggRecent = createAgg(posRecent.id, posRecent.fen, reached = 5, mistakes = 1, avgLoss = 1.0, totalLoss = 1.0)
        val aggOld = createAgg(posOld.id, posOld.fen, reached = 5, mistakes = 1, avgLoss = 1.0, totalLoss = 1.0)
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 5, 1))
            .thenReturn(listOf(aggRecent, aggOld))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "nathan",
                playerColor = PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 1,
            )

        assertEquals(2, weaknesses.size)
        assertEquals(posRecent.id, weaknesses[0].positionId)
        assertEquals(posOld.id, weaknesses[1].positionId)
        assertTrue(weaknesses[0].priority > weaknesses[1].priority)
    }

    @Test
    fun `ranking on orders lower objective poor evidence before higher objective successful evidence`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "recommendation-order")
        val poorPos = Position(UUID.fromString("00000000-0000-0000-0000-000000000020"), "lower-obj-poor", "poor-fen")
        val succPos = Position(UUID.fromString("00000000-0000-0000-0000-000000000010"), "higher-obj-succ", "succ-fen")
        val playedAt = Instant.now()
        val occurrences =
            (1..5).map { occurrence(account, poorPos, "WHITE", "bad", "poor-game-$it", playedAt) } +
                (1..5).map { occurrence(account, succPos, "WHITE", "bad", "succ-game-$it", playedAt) }

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", account.username)).thenReturn(account)
        val aggPoor = createAgg(poorPos.id, poorPos.fen, reached = 5, mistakes = 5, avgLoss = 1.6, totalLoss = 8.0)
        val aggSucc = createAgg(succPos.id, succPos.fen, reached = 5, mistakes = 5, avgLoss = 1.8, totalLoss = 9.0)
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 5, 3))
            .thenReturn(listOf(aggPoor, aggSucc))
        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any()))
            .thenReturn(listOf(analysis(poorPos, 1.6), analysis(succPos, 1.8)))

        setupPracticalRanking(practicalEvidenceService, weaknessPriorityPolicy, account, poorPos, succPos)

        val weaknesses = weaknessCalculationService.getWeaknesses(Platform.CHESS_COM, account.username, PlayerColor.WHITE)
        assertEquals(listOf(poorPos.id, succPos.id), weaknesses.map { it.positionId })
        val poor = weaknesses.single { it.positionId == poorPos.id }
        val succ = weaknesses.single { it.positionId == succPos.id }
        assertEquals(8.0, poor.priority, 0.001)
        assertEquals(9.0, succ.priority, 0.001)
        assertEquals(10.0, poor.recommendationPriority, 0.001)
        assertEquals(6.75, succ.recommendationPriority, 0.001)
        assertEquals(PracticalAssessment.POOR, poor.practicalEvidence.practicalAssessment)
        assertEquals(PracticalAssessment.SUCCESSFUL, succ.practicalEvidence.practicalAssessment)
    }

    @Test
    fun `practical integration scopes by color uses all rows passes INACCURATE and applies total order`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "scope-user")
        val firstPos = Position(UUID.fromString("00000000-0000-0000-0000-000000000001"), "first-hash", "first-fen")
        val secondPos = Position(UUID.fromString("00000000-0000-0000-0000-000000000002"), "second-hash", "second-fen")
        val playedAt = Instant.parse("2026-08-31T12:00:00Z")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "scope-user")).thenReturn(account)
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "BOTH", 0.8, 1, 1))
            .thenReturn(listOf(aggregation(firstPos, "WHITE"), aggregation(firstPos, "BLACK"), aggregation(secondPos, "WHITE")))
        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("BOTH"), any()))
            .thenReturn(buildPracticalOccurrences(account, firstPos, secondPos, playedAt))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any()))
            .thenReturn(listOf(analysis(firstPos), analysis(secondPos)))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = account.username,
                playerColor = PlayerColor.BOTH,
                minEvalLoss = 0.8,
                minMistakeCount = 1,
                minTimesReached = 1,
            )

        assertEquals(
            listOf(firstPos.id to "BLACK", firstPos.id to "WHITE", secondPos.id to "WHITE"),
            weaknesses.map { it.positionId to it.playerColor },
        )
        weaknesses.forEach {
            assertEquals(it.priority, it.recommendationPriority)
            assertEquals(ObjectiveEvidenceState.INACCURATE, it.objectiveEvidenceState)
        }
    }

    @Test
    fun `test top-level averageLoss and objective priority arithmetic with multiple occurrences and rawRate less than 1`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "arithmeticUser")
        val position = Position(hash = "pos-arithmetic", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "arithmeticUser")).thenReturn(account)
        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(buildArithmeticOccurrences(account, position))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any()))
            .thenReturn(listOf(buildArithmeticAnalysis(position)))

        val agg = createAgg(position.id, position.fen, reached = 4, mistakes = 2, avgLoss = 1.0, totalLoss = 2.0)
        `when`(positionOccurrenceRepository.findWeaknessAggregations(eq(account.id), eq("WHITE"), eq(0.8), eq(4), eq(2L)))
            .thenReturn(listOf(agg))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "arithmeticUser",
                playerColor = PlayerColor.WHITE,
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

        val pgn =
            "[ECO \"B23\"]\n[ECOUrl \"https://www.chess.com/openings/Sicilian-Defense\"]\n[White \"ecoUser\"]\n\n1. e4 c5 2. Nc3"
        val occurrences =
            listOf(
                occurrence(Game(chessAccount = account, platformGameId = "g-eco-1", pgn = pgn), position, account, 1, "BAD"),
                occurrence(Game(chessAccount = account, platformGameId = "g-eco-2", pgn = pgn), position, account, 2, "BAD"),
            )
        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any()))
            .thenReturn(listOf(createAnalysisWithLoss(position, "BAD", 1.0)))
        val agg = createAgg(position.id, position.fen, reached = 2, mistakes = 2, avgLoss = 1.0, totalLoss = 2.0)
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 2, 2))
            .thenReturn(listOf(agg))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "ecoUser",
                playerColor = PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 2,
                minMistakeCount = 2,
            )
        assertEquals(1, weaknesses.size)
        val opening = weaknesses[0].openingContext
        assertTrue(opening != null)
        assertEquals("B23", opening?.eco)
        assertEquals("https://www.chess.com/openings/Sicilian-Defense", opening?.ecoUrl)
    }

    @Test
    fun `test opening context non-consensus and missing tags return null openingContext`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "ecoMismatchUser")
        val position = Position(hash = "pos-eco-mismatch", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "ecoMismatchUser")).thenReturn(account)

        val pgn1 = "[ECO \"B23\"]\n[ECOUrl \"https://www.chess.com/openings/Sicilian-Defense\"]\n\n"
        val pgn2 = "[ECO \"B20\"]\n[ECOUrl \"https://www.chess.com/openings/Sicilian-Defense-Other\"]\n\n"
        val occurrences =
            listOf(
                occurrence(Game(chessAccount = account, platformGameId = "g-m-1", pgn = pgn1), position, account, 1, "BAD"),
                occurrence(Game(chessAccount = account, platformGameId = "g-m-2", pgn = pgn2), position, account, 2, "BAD"),
            )
        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any()))
            .thenReturn(listOf(createAnalysisWithLoss(position, "BAD", 1.0)))
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 2, 2))
            .thenReturn(listOf(createAgg(position.id, position.fen, reached = 2, mistakes = 2, avgLoss = 1.0, totalLoss = 2.0)))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "ecoMismatchUser",
                playerColor = PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 2,
                minMistakeCount = 2,
            )
        assertEquals(1, weaknesses.size)
        assertTrue(weaknesses[0].openingContext == null)
    }

    @Test
    fun `test recency weighting attenuates priority for historical games based on days old`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash-recency", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        val pastInstant = Instant.now().minus(182, ChronoUnit.DAYS)
        val occ = occurrence(mockGame("gamePast", playedAt = pastInstant), position, account, movePlayed = "e4")
        val analysis = createAnalysis(position, move = "e4", evalCp = 0, evalLoss = 1.0, bestMove = "d4")
        val agg = createAgg(position.id, position.fen, reached = 1, bestMove = "d4", mistakes = 1, avgLoss = 1.0, totalLoss = 1.0)
        setupSingleOccurrenceWeakness(account, occ, analysis, agg)

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
        assertTrue(w.priority > 0.40)
        assertEquals(1.0 - (182.0 / 365.0), w.priority, 0.05)
    }

    @Test
    fun `test gameUrl generates lichess URL for LICHESS platform occurrences`() {
        val account = ChessAccount(platform = "LICHESS", username = "lichessUser")
        val position = Position(hash = "pos-lichess", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "lichessUser")).thenReturn(account)

        val occ = occurrence(mockGame(platformGameId = "gameLichess123"), position, account, movePlayed = "e4")
        val analysis = createAnalysis(position, move = "e4", evalCp = 0, evalLoss = 1.0, bestMove = "d4")
        val agg = createAgg(position.id, position.fen, reached = 1, bestMove = "d4", mistakes = 1, avgLoss = 1.0, totalLoss = 1.0)
        setupSingleOccurrenceWeakness(account, occ, analysis, agg)

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

    private fun setupSingleOccurrenceWeakness(
        account: ChessAccount,
        occ: PositionOccurrence,
        analysis: EngineAnalysis,
        agg: WeaknessAggregation,
    ) {
        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(listOf(occ))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))
        `when`(positionOccurrenceRepository.findWeaknessAggregations(eq(account.id), eq("WHITE"), eq(0.8), eq(1), eq(1L)))
            .thenReturn(listOf(agg))
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

        val result =
            weaknessCalculationService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "nathan",
                playerColor = PlayerColor.WHITE,
                minEvalLoss = 0.0,
            )
        assertTrue(result.isEmpty())
    }
}
