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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant
import java.time.temporal.ChronoUnit

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
    fun `test priority calculation with mistakes and playable moves`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash", fen = "fen")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        // Player played e4 (good) 2 times, Nf3 (playable) 1 time, and Qh5 (blunder) 2 times
        val occ1 =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "e4",
                playerColor = "WHITE",
            )
        val occ2 =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "e4",
                playerColor = "WHITE",
            )
        val occ3 =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "Nf3",
                playerColor = "WHITE",
            )
        val occ4 =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "Qh5",
                playerColor = "WHITE",
            )
        val occ5 =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "Qh5",
                playerColor = "WHITE",
            )

        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE"))
            .thenReturn(listOf(occ1, occ2, occ3, occ4, occ5))

        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = 40,
                bestMove = "e2e4",
                bestMoveEvalCp = 40,
            )
        // +0.45
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "e4", evalCp = 45, evalLossFromBest = null),
        )
        // -0.50 -> dropped 0.9, but still > -1.0 so playable
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "Nf3", evalCp = -50, evalLossFromBest = null),
        )
        // -1.50 -> dropped 1.9 pawns, massive blunder!
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "Qh5", evalCp = -150, evalLossFromBest = null),
        )

        `when`(engineAnalysisRepository.findByPositionId(position.id)).thenReturn(analysis)

        val weaknesses = weaknessCalculationService.getWeaknesses("CHESS_COM", "nathan", "WHITE", 0.8, minMistakeCount = 2)

        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]
        assertEquals(2, w.mistakeCount) // Only Qh5 x2 counts
        assertEquals(5, w.timesReached)
        assertEquals(1.9, w.averageLoss, 0.01) // 40 - (-150) = 190 cp = 1.9 pawns
        assertEquals(1.52, w.priority, 0.01) // priority × mistakeRate = 3.8 × (2/5) = 1.52
        assertEquals("e2e4", w.bestMove)
        // e4 (loss 0.05) and Nf3 (loss 0.9, but is playable so not filtered) are below acceptableThreshold=0.5
        assertTrue(w.acceptableMoves.any { it.move == "e4" })
        // Qh5 (loss 1.9) and Nf3 (loss 0.9) are above minEvalLoss threshold 0.8
        assertEquals(2, w.movesPlayed.size)
        assertEquals("Qh5", w.movesPlayed[0].move)
        assertEquals(2, w.movesPlayed[0].timesPlayed)
        assertEquals(1.9, w.movesPlayed[0].averageLoss, 0.01)
        assertEquals("Nf3", w.movesPlayed[1].move)
        assertEquals(1, w.movesPlayed[1].timesPlayed)
        assertEquals(0.9, w.movesPlayed[1].averageLoss, 0.01)
    }

    @Test
    fun `test priority is fully weighted for a mistake made today`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash2", fen = "fen2")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        // One blunder played today — weight should be ~1.0
        val occ =
            PositionOccurrence(
                game = mockGame(playedAt = Instant.now()),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "Qh5",
                playerColor = "WHITE",
            )

        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE"))
            .thenReturn(listOf(occ))

        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = 40,
                bestMove = "e2e4",
                bestMoveEvalCp = 40,
            )
        // -1.50 -> 1.9 pawn loss
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "Qh5", evalCp = -150, evalLossFromBest = null),
        )

        `when`(engineAnalysisRepository.findByPositionId(position.id)).thenReturn(analysis)

        val weaknesses = weaknessCalculationService.getWeaknesses("CHESS_COM", "nathan", "WHITE", 0.8, minMistakeCount = 1)

        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]
        // weight ≈ 1.0 (played today), so priority ≈ 1.9
        assertTrue(w.priority >= 1.85, "Priority should be close to 1.9 for a mistake made today, got ${w.priority}")
        assertTrue(w.priority <= 1.9, "Priority should not exceed eval loss for today's game, got ${w.priority}")
    }

    @Test
    fun `test priority is heavily discounted for a mistake made over a year ago`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash3", fen = "fen3")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        // One blunder played 400 days ago (well past the 365-day floor)
        val oldDate = Instant.now().minus(400, ChronoUnit.DAYS)
        val occ =
            PositionOccurrence(
                game = mockGame(playedAt = oldDate),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "Qh5",
                playerColor = "WHITE",
            )

        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE"))
            .thenReturn(listOf(occ))

        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = 40,
                bestMove = "e2e4",
                bestMoveEvalCp = 40,
            )
        // -1.50 -> 1.9 pawn loss
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "Qh5", evalCp = -150, evalLossFromBest = null),
        )

        `when`(engineAnalysisRepository.findByPositionId(position.id)).thenReturn(analysis)

        val weaknesses = weaknessCalculationService.getWeaknesses("CHESS_COM", "nathan", "WHITE", 0.8, minMistakeCount = 1)

        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]
        // weight is floored at 0.1, so priority = 1.9 * 0.1 = 0.19
        assertEquals(0.19, w.priority, 0.01)
    }

    @Test
    fun `test filtering acceptable moves by custom threshold`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash4", fen = "fen4")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        val occ =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "Qh5",
                playerColor = "WHITE",
            )

        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE"))
            .thenReturn(listOf(occ))

        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = 100,
                bestMove = "e4",
                bestMoveEvalCp = 100,
            )
        // e4 (loss 0.0)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "e4", evalCp = 100, evalLossFromBest = null))
        // d4 (loss 0.15)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "d4", evalCp = 85, evalLossFromBest = null))
        // Nf3 (loss 0.40)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "Nf3", evalCp = 60, evalLossFromBest = null))
        // Qh5 (blunder, loss 3.0)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "Qh5", evalCp = -200, evalLossFromBest = null))

        `when`(engineAnalysisRepository.findByPositionId(position.id)).thenReturn(analysis)

        // Strict threshold 0.20 -> only e4 (0.0) and d4 (0.15) pass
        val strictWeaknesses =
            weaknessCalculationService.getWeaknesses(
                "CHESS_COM",
                "nathan",
                "WHITE",
                0.8,
                acceptableThreshold = 0.20,
                minMistakeCount = 1,
            )
        val strictAcceptable = strictWeaknesses[0].acceptableMoves.map { it.move }
        assertEquals(listOf("e4", "d4"), strictAcceptable)

        // Loose threshold 0.50 -> e4 (0.0), d4 (0.15), and Nf3 (0.40) pass
        val looseWeaknesses =
            weaknessCalculationService.getWeaknesses(
                "CHESS_COM",
                "nathan",
                "WHITE",
                0.8,
                acceptableThreshold = 0.50,
                minMistakeCount = 1,
            )
        val looseAcceptable = looseWeaknesses[0].acceptableMoves.map { it.move }
        assertEquals(listOf("e4", "d4", "Nf3"), looseAcceptable)
    }

    @Test
    fun `test positions with mistakeCount less than minMistakeCount are filtered out`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash5", fen = "fen5")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        // 2 blunders
        val occ1 =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "Qh5",
                playerColor = "WHITE",
            )
        val occ2 =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "Qh5",
                playerColor = "WHITE",
            )

        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE"))
            .thenReturn(listOf(occ1, occ2))

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "e4", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "Qh5", evalCp = -200, evalLossFromBest = null))

        `when`(engineAnalysisRepository.findByPositionId(position.id)).thenReturn(analysis)

        // Default minMistakeCount is 3 -> 2 mistakes is not enough, should return empty list
        val weaknesses = weaknessCalculationService.getWeaknesses("CHESS_COM", "nathan", "WHITE", 0.8, minMistakeCount = 3)
        assertTrue(weaknesses.isEmpty(), "Positions with fewer mistakes than minMistakeCount should be excluded")
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
