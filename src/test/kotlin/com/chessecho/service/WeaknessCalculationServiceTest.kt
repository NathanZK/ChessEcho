package com.chessecho.service

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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
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
