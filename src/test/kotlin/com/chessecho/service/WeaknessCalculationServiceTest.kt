package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.WeaknessAggregation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class WeaknessCalculationServiceTest {
    @Mock
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Mock
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Mock
    private lateinit var engineAnalysisRepository: EngineAnalysisRepository

    @InjectMocks
    private lateinit var weaknessCalculationService: WeaknessCalculationService

    @Test
    fun `test dynamic threshold changes mistakeCount and classifies moves accurately`() {
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

        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE"))
            .thenReturn(listOf(occA, occB, occC))

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "A", evalCp = 80, evalLossFromBest = 0.2))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "B", evalCp = 70, evalLossFromBest = 0.3))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "C", evalCp = 20, evalLossFromBest = 0.8))

        `when`(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(position.id)).thenReturn(analysis)

        // At threshold 0.3:
        // move A (0.2) is NOT a mistake
        // move B (0.3) IS a mistake
        // move C (0.8) IS a mistake
        // Total mistakes = 2 (B and C)
        val aggregation03 =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
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
                mistakeThreshold = 0.3,
                minTimesReached = 3,
                minMistakeCount = 1,
            ),
        ).thenReturn(listOf(aggregation03))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                "CHESS_COM",
                "nathan",
                "WHITE",
                mistakeThreshold = 0.3,
                minMistakeCount = 1,
            )

        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]
        assertEquals(2, w.mistakeCount)
        assertEquals(3, w.timesReached)
        // Acceptable moves at threshold 0.3: move A (0.2 < 0.3)
        assertEquals(1, w.acceptableMoves.size)
        assertEquals("A", w.acceptableMoves[0].move)
        // Moves played at threshold 0.3: moves B (0.3) and C (0.8)
        assertEquals(2, w.movesPlayed.size)
        assertTrue(w.movesPlayed.any { it.move == "B" })
        assertTrue(w.movesPlayed.any { it.move == "C" })
    }

    @Test
    fun `test negative threshold throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            weaknessCalculationService.getWeaknesses("CHESS_COM", "nathan", "WHITE", mistakeThreshold = -0.5)
        }
    }

    @Test
    fun `test changing threshold does not trigger Stockfish analysis`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = any(),
                playerColor = any(),
                mistakeThreshold = any(),
                minTimesReached = any(),
                minMistakeCount = any(),
            ),
        ).thenReturn(emptyList())

        // Run weakness requests with different thresholds
        weaknessCalculationService.getWeaknesses("CHESS_COM", "nathan", "WHITE", mistakeThreshold = 0.3)
        weaknessCalculationService.getWeaknesses("CHESS_COM", "nathan", "WHITE", mistakeThreshold = 0.8)

        // Verify no engine analysis repository save or external execution occurred
        verify(engineAnalysisRepository, never()).save(any())
    }

    private fun mockGame(playedAt: Instant? = null): Game =
        Game(
            chessAccount = ChessAccount(user = AppUser(email = "t"), platform = "P", username = "U"),
            platformGameId = "123",
            timeControl = "bullet",
            pgn = "pgn",
            result = "win",
            playedAt = playedAt,
        )
}
