package com.chessecho.service

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.DecisionSeverity
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.PositionWeaknessSeverity
import com.chessecho.domain.TimeControl
import com.chessecho.dto.GameAnalysisStatus
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.service.similarity.SimilarityConcurrencyManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class GameAnalysisServiceTest {
    @Mock
    private lateinit var gameRepository: GameRepository

    @Mock
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Mock
    private lateinit var engineAnalysisRepository: EngineAnalysisRepository

    @Mock
    private lateinit var engineAnalysisService: EngineAnalysisService

    @Mock
    private lateinit var similarityConcurrencyManager: SimilarityConcurrencyManager

    @Mock
    private lateinit var decisionTimingPolicy: DecisionTimingPolicy

    @InjectMocks
    private lateinit var gameAnalysisService: GameAnalysisService

    @Test
    fun `analyzeGame throws 404 when game not found`() {
        val gameId = UUID.randomUUID()
        whenever(gameRepository.findById(gameId)).thenReturn(Optional.empty())

        assertThrows(ResponseStatusException::class.java) {
            gameAnalysisService.analyzeGame(gameId, null)
        }
    }

    @Test
    fun `analyzeGame returns complete analysis without engine call when all plies already evaluated`() {
        val (game, pos1, occ1) = createTestFixture(12000L)
        val gameId = game.id
        val analysis1 = createAnalysis(pos1, 30)

        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game))
        whenever(positionOccurrenceRepository.findAllByGameIdOrderByPlyNumberAsc(gameId)).thenReturn(listOf(occ1))
        whenever(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(pos1.id))).thenReturn(listOf(analysis1))
        whenever(decisionTimingPolicy.classify(TimeControl.BLITZ, 12000L)).thenReturn(DecisionSeverity.MINOR)

        val response = gameAnalysisService.analyzeGame(gameId, null)

        assertEquals(GameAnalysisStatus.COMPLETE, response.analysisStatus)
        assertEquals(1, response.plies.size)
        val ply = response.plies[0]
        assertEquals(1, ply.plyNumber)
        assertEquals("e4", ply.movePlayed)
        assertEquals("WHITE", ply.playerColor)
        assertEquals(12000L, ply.decisionTimeMs)
        assertEquals(DecisionSeverity.MINOR, ply.decisionSeverity)
        assertEquals(0.0, ply.evalLoss)
        assertEquals(PositionWeaknessSeverity.NO_CONCERN, ply.weaknessSeverity)
        verify(engineAnalysisService, never()).analyzePosition(any())
    }

    @Test
    fun `analyzeGame evaluates missing plies via EngineAnalysisService when permit is acquired`() {
        val (game, pos1, occ1) = createTestFixture(5000L)
        val gameId = game.id

        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game))
        whenever(positionOccurrenceRepository.findAllByGameIdOrderByPlyNumberAsc(gameId)).thenReturn(listOf(occ1))
        whenever(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(pos1.id))).thenReturn(emptyList())

        whenever(similarityConcurrencyManager.withPermitOrFallback<Any?>(any(), any())).thenAnswer { invocation ->
            val onPermit = invocation.getArgument<() -> Any?>(0)
            onPermit()
        }

        val analysisAfter = createAnalysis(pos1, 35)
        whenever(engineAnalysisRepository.findByPositionId(pos1.id)).thenReturn(analysisAfter)
        whenever(decisionTimingPolicy.classify(TimeControl.BLITZ, 5000L)).thenReturn(DecisionSeverity.NO_CONCERN)

        val response = gameAnalysisService.analyzeGame(gameId, null)

        assertEquals(GameAnalysisStatus.COMPLETE, response.analysisStatus)
        verify(engineAnalysisService).analyzePosition(pos1)
        assertEquals(1, response.plies.size)
        assertEquals("e4", response.plies[0].bestMove)
        assertEquals(0.0, response.plies[0].evalLoss)
    }

    @Test
    fun `analyzeGame returns CAPACITY_EXCEEDED when SimilarityConcurrencyManager capacity is exceeded`() {
        val (game, pos1, occ1) = createTestFixture(5000L)
        val gameId = game.id

        whenever(gameRepository.findById(gameId)).thenReturn(Optional.of(game))
        whenever(positionOccurrenceRepository.findAllByGameIdOrderByPlyNumberAsc(gameId)).thenReturn(listOf(occ1))
        whenever(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(pos1.id))).thenReturn(emptyList())

        whenever(similarityConcurrencyManager.withPermitOrFallback<Any?>(any(), any())).thenAnswer { invocation ->
            val onExceeded = invocation.getArgument<() -> Any?>(1)
            onExceeded()
        }
        whenever(decisionTimingPolicy.classify(TimeControl.BLITZ, 5000L)).thenReturn(DecisionSeverity.NO_CONCERN)

        val response = gameAnalysisService.analyzeGame(gameId, null)

        assertEquals(GameAnalysisStatus.CAPACITY_EXCEEDED, response.analysisStatus)
        verify(engineAnalysisService, never()).analyzePosition(any())
        assertEquals(1, response.plies.size)
        assertNull(response.plies[0].evalLoss)
    }

    private fun createTestFixture(decisionTimeMs: Long = 12000L): Triple<Game, Position, PositionOccurrence> {
        val gameId = UUID.randomUUID()
        val account = ChessAccount(platform = "CHESS_COM", username = "player_white")
        val game =
            Game(
                id = gameId,
                chessAccount = account,
                platformGameId = "game_1",
                pgn = "1. e4",
                timeControl = "blitz",
                playedAt = Instant.now(),
                result = "win",
                whiteUsername = "player_white",
                blackUsername = "player_black",
            )
        val pos = Position(id = UUID.randomUUID(), hash = "h1", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
        val occ =
            PositionOccurrence(
                game = game,
                position = pos,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "e4",
                playerColor = "WHITE",
                decisionTimeMs = decisionTimeMs,
            )
        return Triple(game, pos, occ)
    }

    private fun createAnalysis(
        position: Position,
        evalCp: Int = 30,
    ): EngineAnalysis {
        val analysis =
            EngineAnalysis(
                id = UUID.randomUUID(),
                position = position,
                depth = 16,
                baselineEvalCp = evalCp,
                bestMove = "e4",
                bestMoveEvalCp = evalCp,
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e4",
                evalCp = evalCp,
                evalLossFromBest = 0.0,
            ),
        )
        return analysis
    }
}
