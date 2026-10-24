package com.chessecho.service

import com.chessecho.config.PracticalEvidenceProperties
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.dto.TimeControlStats
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.WeaknessAggregation
import com.chessecho.testsupport.WeaknessCalculationTestUtils.mockGame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.springframework.test.util.ReflectionTestUtils

@ExtendWith(MockitoExtension::class)
class WeaknessCalculationTimeControlTest {
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
            practicalEvidenceService.summarize(any(), any(), any(), any(), any()),
        ).thenReturn(emptyMap())

        val fallbackPolicy = WeaknessPriorityPolicy(PracticalEvidenceProperties())
        lenient().`when`(
            weaknessPriorityPolicy.evaluate(any(), any(), anyOrNull()),
        ).thenAnswer { invocation ->
            fallbackPolicy.evaluate(
                invocation.getArgument(0),
                invocation.getArgument(1),
                invocation.getArgument(2),
            )
        }

        lenient().`when`(
            puzzleSchedulingEventRepository.findHistory(any(), any(), any(), any()),
        ).thenReturn(emptyList())

        lenient().`when`(
            adaptivePuzzleSchedulingPolicy.schedule(any(), any(), any(), any()),
        ).thenAnswer { invocation -> invocation.getArgument<Double>(0) }

        ReflectionTestUtils.setField(
            weaknessCalculationService,
            "puzzleSchedulingEventRepository",
            puzzleSchedulingEventRepository,
        )
        ReflectionTestUtils.setField(
            weaknessCalculationService,
            "adaptivePuzzleSchedulingPolicy",
            adaptivePuzzleSchedulingPolicy,
        )
    }

    @Test
    fun `test time control partitions aggregate correctly and exclude unevaluated or unsupported occurrences`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash_tc", fen = "fen_tc")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        val analysis = createPartitionAnalysis(position)
        val occurrences = createPartitionOccurrences(account, position)

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

        assertEquals(1, weaknesses.size)
        assertEquals(4, weaknesses[0].mistakeCount)
        assertPartitionStats(weaknesses[0].timeControlStats)
    }

    private fun createPartitionAnalysis(position: Position): EngineAnalysis {
        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = 100,
                bestMove = "best",
                bestMoveEvalCp = 100,
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "A", evalCp = 100, evalLossFromBest = 0.0),
        )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "B", evalCp = 20, evalLossFromBest = 0.8),
        )
        return analysis
    }

    private fun createPartitionOccurrences(
        account: ChessAccount,
        position: Position,
    ): List<PositionOccurrence> =
        listOf(
            PositionOccurrence(
                game = mockGame(timeControl = "BLITZ"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "A",
                playerColor = "WHITE",
            ),
            PositionOccurrence(
                game = mockGame(timeControl = "BLITZ"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "B",
                playerColor = "WHITE",
            ),
            PositionOccurrence(
                game = mockGame(timeControl = "BLITZ"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "C",
                playerColor = "WHITE",
            ),
            PositionOccurrence(
                game = mockGame(timeControl = "RAPID"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "B",
                playerColor = "WHITE",
            ),
            PositionOccurrence(
                game = mockGame(timeControl = "DAILY"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "B",
                playerColor = "WHITE",
            ),
            PositionOccurrence(
                game = mockGame(timeControl = null),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "B",
                playerColor = "WHITE",
            ),
        )

    private fun assertPartitionStats(tcStats: Map<String, TimeControlStats>?) {
        assertTrue(tcStats != null, "timeControlStats should not be null")
        val blitzStats = tcStats!!["BLITZ"]
        assertTrue(blitzStats != null, "BLITZ stats missing")
        assertEquals(2, blitzStats!!.timesReached)
        assertEquals(1, blitzStats.mistakeCount)
        assertEquals(0.5, blitzStats.mistakeRate, 0.001)
        assertEquals(0.8, blitzStats.averageLoss, 0.001)

        val rapidStats = tcStats["RAPID"]
        assertTrue(rapidStats != null, "RAPID stats missing")
        assertEquals(1, rapidStats!!.timesReached)
        assertEquals(1, rapidStats.mistakeCount)
        assertEquals(1.0, rapidStats.mistakeRate, 0.001)
        assertEquals(0.8, rapidStats.averageLoss, 0.001)

        assertTrue(!tcStats.containsKey("DAILY"), "DAILY should be excluded")
        assertTrue(!tcStats.containsKey("null"), "null should be excluded")
    }

    @Test
    fun `test concrete regression scenario for time control partitions`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash_reg", fen = "fen_reg")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        val analysis = createRegressionAnalysis(position)
        val occurrences = createRegressionOccurrences(account, position)

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
                positionId = position.id, fen = position.fen, playerColor = "WHITE",
                timesReached = 10, bestMove = "best", baselineEvalCp = 100,
                mistakeCount = 3, averageLoss = 0.8, rawTotalLoss = 2.4,
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

        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]
        assertEquals(3, w.mistakeCount)
        assertEquals(10, w.timesReached)
        assertEquals(30.0, w.mistakeRate, 0.001)
        assertRegressionStats(w.timeControlStats)
    }

    private fun createRegressionAnalysis(position: Position): EngineAnalysis {
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
        return analysis
    }

    private fun createRegressionOccurrences(
        account: ChessAccount,
        position: Position,
    ): List<PositionOccurrence> {
        val occurrences = mutableListOf<PositionOccurrence>()
        repeat(2) {
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
        }
        repeat(2) {
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
        }
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
        repeat(5) {
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
        return occurrences
    }

    private fun assertRegressionStats(tcStats: Map<String, TimeControlStats>?) {
        assertTrue(tcStats != null, "timeControlStats should not be null")
        val bulletStats = tcStats!!["BULLET"]
        assertTrue(bulletStats != null, "BULLET stats missing")
        assertEquals(4, bulletStats!!.timesReached)
        assertEquals(2, bulletStats.mistakeCount)
        assertEquals(0.5, bulletStats.mistakeRate, 0.001)
        assertEquals(0.8, bulletStats.averageLoss, 0.001)

        assertTrue(!tcStats.containsKey("UNKNOWN"), "UNKNOWN should be excluded")
        assertTrue(!tcStats.containsKey("null"), "null should be excluded")
    }
}
