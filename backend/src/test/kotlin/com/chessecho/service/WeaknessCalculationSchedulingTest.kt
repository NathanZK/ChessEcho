package com.chessecho.service

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.WeaknessAggregation
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.testsupport.WeaknessCalculationTestUtils.createAgg
import com.chessecho.testsupport.WeaknessCalculationTestUtils.createAnalysisWithLoss
import com.chessecho.testsupport.WeaknessCalculationTestUtils.mockGame
import com.chessecho.testsupport.WeaknessCalculationTestUtils.occurrence
import com.chessecho.testsupport.WeaknessCalculationTestUtils.setupDefaultServiceMocks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class WeaknessCalculationSchedulingTest {
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
    fun `test authenticated scheduling delegates history query with correct scoping and mappings`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "schedUser")
        val position = Position(hash = "pos-sched", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        val appUserId = UUID.randomUUID()
        val principal = AuthenticatedPrincipal(appUserId = appUserId, devPrincipal = false)

        whenever(accountOwnershipService.resolvePrivateRead(Platform.CHESS_COM, "schedUser", principal)).thenReturn(account)
        val occurrence = setupSinglePositionSchedulingMocks(account, position)

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
            puzzleSchedulingEventRepository.findHistory(eq(appUserId), eq(account.id), eq(setOf(position.id)), eq("WHITE")),
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
        )
            .thenReturn(42.0)

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
        verify(puzzleSchedulingEventRepository).findHistory(appUserId, account.id, setOf(position.id), "WHITE")
        assertEquals(position.id, positionIdCaptor.firstValue)
        assertEquals(weaknesses[0].recommendationPriority, priorityCaptor.firstValue)
        assertEquals(1, eventsCaptor.firstValue.size)
        assertEquals(SchedulingEventType.GAME_MISTAKE, eventsCaptor.firstValue[0].type)
        assertEquals(eventTime, eventsCaptor.firstValue[0].occurredAt)
        assertEquals(occurrence.id, eventsCaptor.firstValue[0].sourceOccurrenceId)
    }

    @Test
    fun `test authenticated scheduling re-ranks weaknesses by scheduledScore with secondary tiebreakers`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "schedTiebreaker")
        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)
        whenever(accountOwnershipService.resolvePrivateRead(Platform.CHESS_COM, "schedTiebreaker", principal)).thenReturn(account)

        val posA = Position(hash = "hash-A", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        val posB = Position(hash = "hash-B", fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        setupPairWeaknessMocks(account, posA, posB)
        `when`(adaptivePuzzleSchedulingPolicy.schedule(any(), eq(posA.id), any(), any())).thenReturn(5.0)
        `when`(adaptivePuzzleSchedulingPolicy.schedule(any(), eq(posB.id), any(), any())).thenReturn(10.0)

        val weaknesses1 =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "schedTiebreaker",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1,
                principal = principal,
            )
        assertEquals(listOf(posB.id, posA.id), weaknesses1.map { it.positionId })

        `when`(adaptivePuzzleSchedulingPolicy.schedule(any(), any(), any(), any())).thenReturn(10.0)
        val weaknesses2 =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "schedTiebreaker",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1,
                principal = principal,
            )
        val expectedFirst = if (posA.id.toString() < posB.id.toString()) posA.id else posB.id
        assertEquals(expectedFirst, weaknesses2[0].positionId)
    }

    @Test
    fun `test authenticated scheduling zero-history parity with unauthenticated baseline`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "schedParity")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "schedParity")).thenReturn(account)

        val posA = Position(hash = "hash-A", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        val posB = Position(hash = "hash-B", fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        setupPairWeaknessMocks(account, posA, posB, avgLossB = 2.0)

        val unauthWeaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "schedParity",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1,
                principal = null,
            )

        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)
        whenever(accountOwnershipService.resolvePrivateRead(Platform.CHESS_COM, "schedParity", principal)).thenReturn(account)
        val authWeaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "schedParity",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1,
                principal = principal,
            )

        assertEquals(unauthWeaknesses.size, authWeaknesses.size)
        for (i in unauthWeaknesses.indices) {
            assertEquals(unauthWeaknesses[i].positionId, authWeaknesses[i].positionId)
            assertEquals(unauthWeaknesses[i].priority, authWeaknesses[i].priority, 1e-4)
            assertEquals(unauthWeaknesses[i].recommendationPriority, authWeaknesses[i].recommendationPriority, 1e-4)
        }
    }

    @Test
    fun `test unauthenticated requests gracefully sort weaknesses using unscheduled baseline order`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "schedFallback")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "schedFallback")).thenReturn(account)
        val position = Position(hash = "pos-fallback", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        setupSinglePositionSchedulingMocks(account, position, gameId = "gF")

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "schedFallback",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1,
                principal = null,
            )
        assertEquals(1, weaknesses.size)
        verify(puzzleSchedulingEventRepository, never()).findHistory(any(), any(), any(), any())
    }

    private fun setupSinglePositionSchedulingMocks(
        account: ChessAccount,
        position: Position,
        gameId: String = "g-sched",
    ): PositionOccurrence {
        val occurrence = occurrence(mockGame(gameId), position, account, movePlayed = "BAD")
        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(listOf(occurrence))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(
            listOf(createAnalysisWithLoss(position, "BAD", 1.0)),
        )
        `when`(positionOccurrenceRepository.findWeaknessAggregations(eq(account.id), eq("WHITE"), eq(0.8), eq(1), eq(1L)))
            .thenReturn(listOf(createAgg(position.id, position.fen, reached = 1, mistakes = 1, avgLoss = 1.0, totalLoss = 1.0)))
        return occurrence
    }

    private fun setupPairWeaknessMocks(
        account: ChessAccount,
        posA: Position,
        posB: Position,
        avgLossB: Double = 1.0,
    ) {
        val occurrences =
            listOf(
                occurrence(mockGame("gA"), posA, account, movePlayed = "BAD"),
                occurrence(mockGame("gB"), posB, account, movePlayed = "BAD"),
            )
        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(
            listOf(createAnalysisWithLoss(posA, "BAD", 1.0), createAnalysisWithLoss(posB, "BAD", 1.0)),
        )

        val aggA = createAgg(posA.id, posA.fen, reached = 1, mistakes = 1, avgLoss = 1.0, totalLoss = 1.0)
        val aggB = createAgg(posB.id, posB.fen, reached = 1, mistakes = 1, avgLoss = avgLossB, totalLoss = avgLossB)
        `when`(positionOccurrenceRepository.findWeaknessAggregations(eq(account.id), eq("WHITE"), eq(0.8), eq(1), eq(1L)))
            .thenReturn(listOf(aggA, aggB))
    }

    @Test
    fun `test WeaknessResponse DTO does not expose scheduledScore field`() {
        val fields = com.chessecho.dto.WeaknessResponse::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("scheduledScore"), "WeaknessResponse should not expose scheduledScore")
    }

    @Test
    fun `test getWeaknesses throws AccountSelectionRequiredException when principal is present without accountId`() {
        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)
        whenever(accountOwnershipService.resolvePrivateRead(Platform.CHESS_COM, "nathan", principal))
            .thenThrow(AccountSelectionRequiredException("Multiple accounts found"))

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

        whenever(accountOwnershipService.resolveSharedAccount(accountId, principal)).thenReturn(account)
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
        setupDualColorOccurrences(account, position)
        setupDualColorAnalysisAndAggregations(account, position)
    }

    private fun setupDualColorOccurrences(
        account: ChessAccount,
        position: Position,
    ) {
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
    }

    private fun setupDualColorAnalysisAndAggregations(
        account: ChessAccount,
        position: Position,
    ) {
        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "e4", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "e4", evalCp = -50, evalLossFromBest = 1.5))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(position.id))).thenReturn(listOf(analysis))

        val aggWhite = createDualColorAggregation(position, "WHITE")
        val aggBlack = createDualColorAggregation(position, "BLACK")
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

    private fun createDualColorAggregation(
        position: Position,
        color: String,
    ): WeaknessAggregation =
        WeaknessAggregation(
            positionId = position.id, fen = position.fen, playerColor = color,
            timesReached = 5, bestMove = "e4", baselineEvalCp = 100,
            mistakeCount = 5, averageLoss = 1.5, rawTotalLoss = 7.5,
        )

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
        assertDualColorOrdering(weaknesses)
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
                chessAccountRepository,
                positionOccurrenceRepository,
                engineAnalysisRepository,
                practicalEvidenceService,
                weaknessPriorityPolicy,
                accountOwnershipService,
                puzzleSchedulingEventRepository,
                adaptivePuzzleSchedulingPolicy,
            )

        val weaknesses =
            unscheduledService.getWeaknesses(
                platform = Platform.CHESS_COM,
                username = "nathan",
                playerColor = PlayerColor.BOTH,
                principal = principal,
                accountId = accountId,
            )
        assertDualColorOrdering(weaknesses)
    }

    @Test
    fun `test dual-color tie breaking orders by playerColor ascending in authenticated scheduled sort`() {
        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)
        val accountId = UUID.randomUUID()
        val account = ChessAccount(id = accountId, platform = "CHESS_COM", username = "nathan")
        val position = Position(id = UUID.randomUUID(), hash = "dual_pos_hash_sched", fen = "dual_pos_fen_sched")

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
        assertDualColorOrdering(weaknesses)
    }

    private fun assertDualColorOrdering(weaknesses: List<com.chessecho.dto.WeaknessResponse>) {
        assertEquals(2, weaknesses.size)
        assertEquals(weaknesses[0].positionId, weaknesses[1].positionId)
        assertEquals(weaknesses[0].priority, weaknesses[1].priority)
        assertEquals(weaknesses[0].recommendationPriority, weaknesses[1].recommendationPriority)
        assertEquals("BLACK", weaknesses[0].playerColor)
        assertEquals("WHITE", weaknesses[1].playerColor)
    }
}
