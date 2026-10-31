package com.chessecho.service

import com.chessecho.config.PracticalEvidenceProperties
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.dto.PhaseStats
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.WeaknessAggregation
import com.chessecho.testsupport.WeaknessCalculationTestUtils.createAgg
import com.chessecho.testsupport.WeaknessCalculationTestUtils.createAnalysisWithLoss
import com.chessecho.testsupport.WeaknessCalculationTestUtils.mockGame
import com.chessecho.testsupport.WeaknessCalculationTestUtils.occurrence
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
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class WeaknessCalculationPhaseTest {
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
    fun `test phase classification correctly partitions weaknesses and omits empty buckets`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash-phase", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        val analysis = createPhaseAnalysis(position)
        val occurrences = createPhaseOccurrences(account, position)

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

        assertEquals(1, weaknesses.size)
        assertPhaseStatsCorrect(weaknesses[0].phaseStats)
    }

    private fun createPhaseAnalysis(position: Position): EngineAnalysis {
        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = 100,
                bestMove = "best",
                bestMoveEvalCp = 100,
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "BAD", evalCp = 0, evalLossFromBest = 1.0),
        )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "GOOD", evalCp = 100, evalLossFromBest = 0.0),
        )
        return analysis
    }

    private fun createPhaseOccurrences(
        account: ChessAccount,
        position: Position,
    ): List<PositionOccurrence> =
        listOf(
            PositionOccurrence(
                game = mockGame("g1"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "BAD",
                playerColor = "WHITE",
            ),
            PositionOccurrence(
                game = mockGame("g1"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "BAD",
                playerColor = "WHITE",
            ),
            PositionOccurrence(
                game = mockGame("g1"),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "GOOD",
                playerColor = "WHITE",
            ),
            PositionOccurrence(
                game = mockGame("g2"),
                position = position,
                chessAccount = account,
                plyNumber = 30,
                movePlayed = "BAD",
                playerColor = "WHITE",
            ),
            PositionOccurrence(
                game = mockGame("g3"),
                position = position,
                chessAccount = account,
                plyNumber = 60,
                movePlayed = "BAD",
                playerColor = "WHITE",
            ),
            PositionOccurrence(
                game = mockGame("g6"),
                position = position,
                chessAccount = account,
                plyNumber = 60,
                movePlayed = "UNEVALUATED",
                playerColor = "WHITE",
            ),
        )

    private fun assertPhaseStatsCorrect(pStats: Map<String, PhaseStats>?) {
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
        setupSinglePhaseMocks(account, position, plyNumber)

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

    private fun setupSinglePhaseMocks(
        account: ChessAccount,
        position: Position,
        plyNumber: Int,
    ) {
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "phaseUser")).thenReturn(account)
        val analysis = createAnalysisWithLoss(position, "BAD", 1.0)
        val occurrences = listOf(occurrence(mockGame("g1"), position, account, plyNumber, "BAD"))

        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val aggregation = createAgg(position.id, position.fen, reached = 1, mistakes = 1, avgLoss = 1.0, totalLoss = 1.0)
        `when`(positionOccurrenceRepository.findWeaknessAggregations(eq(account.id), eq("WHITE"), eq(0.8), eq(1), eq(1L)))
            .thenReturn(listOf(aggregation))
    }

    @Test
    fun `test phase classification corrupt FEN with fewer than 8 ranks defaults to OPENING`() {
        val corruptFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w KQkq - 0 1"
        assertEquals("OPENING", testSinglePhaseClassification(corruptFen, plyNumber = 40))
    }

    @Test
    fun `test phase classification exact mmCount boundary at 6 and heavy piece endgame are ENDGAME`() {
        val heavyEndgameFen = "4k2r/4q3/8/8/8/8/4Q3/4K2R w - - 0 1"
        assertEquals("ENDGAME", testSinglePhaseClassification(heavyEndgameFen, plyNumber = 10))

        val mm6Fen = "r2k1b1r/8/8/8/8/8/8/R2K1B1R w - - 0 1"
        assertEquals("ENDGAME", testSinglePhaseClassification(mm6Fen, plyNumber = 10))
    }

    @Test
    fun `test phase classification exact mmCount boundary at 10 is queenless middlegame and 11 can be OPENING`() {
        val mm10Fen = "r1b1kbnr/8/8/8/8/8/8/R1B1KBNR w - - 0 1"
        assertEquals("MIDDLEGAME", testSinglePhaseClassification(mm10Fen, plyNumber = 2))

        val mm11Fen = "r1b1kbnr/8/8/8/8/8/4N3/R1B1KBNR w - - 0 1"
        assertEquals("OPENING", testSinglePhaseClassification(mm11Fen, plyNumber = 2))
    }

    @Test
    fun `test phase classification home-rank sparsity triggers MIDDLEGAME when mmCount greater than 6`() {
        val sparseWhiteRank1Fen = "rnbqkbnr/8/8/8/8/8/2NN4/R3K2R w - - 0 1"
        assertEquals("MIDDLEGAME", testSinglePhaseClassification(sparseWhiteRank1Fen, plyNumber = 2))

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
        val white4Fen = "rnbqkbnr/8/8/8/8/8/2N5/R1B1K2R w - - 0 1"
        assertEquals("OPENING", testSinglePhaseClassification(white4Fen, plyNumber = 2))

        val black4Fen = "r1b1k2r/2n5/8/8/8/8/8/RNBQKBNR w - - 0 1"
        assertEquals("OPENING", testSinglePhaseClassification(black4Fen, plyNumber = 2))
    }

    @Test
    fun `test phase classification move number boundary transitions from OPENING at ply 28 to MIDDLEGAME at ply 29 and 30`() {
        val standardFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        assertEquals("OPENING", testSinglePhaseClassification(standardFen, plyNumber = 28))
        assertEquals("MIDDLEGAME", testSinglePhaseClassification(standardFen, plyNumber = 29))
        assertEquals("MIDDLEGAME", testSinglePhaseClassification(standardFen, plyNumber = 30))
    }
}
