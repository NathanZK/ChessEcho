package com.chessecho.service

import com.chessecho.config.PracticalEvidenceProperties
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
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
import com.chessecho.testsupport.WeaknessCalculationTestUtils.mockGame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
class WeaknessCalculationForcedMateTest {
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
        lenient()
            .`when`(
                practicalEvidenceService.summarize(any(), any(), any(), any(), any()),
            ).thenReturn(emptyMap())

        val fallbackPolicy = WeaknessPriorityPolicy(PracticalEvidenceProperties())
        lenient()
            .`when`(
                weaknessPriorityPolicy.evaluate(any(), any(), anyOrNull()),
            ).thenAnswer { invocation ->
                fallbackPolicy.evaluate(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                )
            }

        lenient()
            .`when`(
                puzzleSchedulingEventRepository.findHistory(any(), any(), any(), any()),
            ).thenReturn(emptyList())

        lenient()
            .`when`(
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
    fun `test forced-mate outcomes checkmate blunders excluded from acceptableMoves, prolongation retained, null loss excluded`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash_mate", fen = "6k1/5ppp/8/8/8/8/5PPP/4R1K1 w - - 0 1")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        val analysis = createForcedMateAnalysis(position)
        val occurrences = createForcedMateOccurrences(account, position)

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
        assertForcedMateWeakness(weaknesses[0])
    }

    private fun createForcedMateAnalysis(position: Position): EngineAnalysis {
        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                bestMove = "Re8#",
                bestMoveEvalMate = 1,
                baselineEvalMate = 1,
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "Re8#", evalMate = 1, evalLossFromBest = 0.0),
        )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "Qxf7+", evalMate = 3, evalLossFromBest = 0.20),
        )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "f3", evalMate = -1, evalLossFromBest = 19.5),
        )
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "a3", evalLossFromBest = null))
        return analysis
    }

    private fun createForcedMateOccurrences(
        account: ChessAccount,
        position: Position,
    ): List<PositionOccurrence> =
        listOf(
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "f3",
                playerColor = "WHITE",
            ),
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 3,
                movePlayed = "f3",
                playerColor = "WHITE",
            ),
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 5,
                movePlayed = "a3",
                playerColor = "WHITE",
            ),
        )

    private fun assertForcedMateWeakness(w: com.chessecho.dto.WeaknessResponse) {
        assertEquals(2, w.mistakeCount)
        val acceptableMovesList = w.acceptableMoves.map { it.move }
        assertTrue(acceptableMovesList.contains("Re8#"), "Should contain best move Re8#")
        assertTrue(acceptableMovesList.contains("Qxf7+"), "Should contain prolongation move Qxf7+")
        assertFalse(acceptableMovesList.contains("f3"), "Should exclude checkmate blunder f3")
        assertFalse(acceptableMovesList.contains("a3"), "Should exclude unevaluated move a3")

        assertEquals(1, w.movesPlayed.size)
        assertEquals("f3", w.movesPlayed[0].move)
        assertEquals(2, w.movesPlayed[0].timesPlayed)
    }
}
