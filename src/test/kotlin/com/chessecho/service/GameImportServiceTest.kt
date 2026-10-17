package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import com.chessecho.domain.UserPositionWeakness
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.UserPositionWeaknessRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class GameImportServiceTest {
    private val positionOccurrenceRepository = mock<PositionOccurrenceRepository>()
    private val engineAnalysisRepository = mock<EngineAnalysisRepository>()
    private val userPositionWeaknessRepository = mock<UserPositionWeaknessRepository>()
    private val puzzleSchedulingEventRepository = mock<PuzzleSchedulingEventRepository>()

    private lateinit var gameImportService: GameImportService

    private val user = AppUser(email = "user@example.com")
    private val account = ChessAccount(platform = "CHESS_COM", username = "player")
    private val position = Position(hash = "pos_hash", fen = "6k1/5ppp/8/8/8/8/5PPP/4R1K1 w - - 0 1")

    @BeforeEach
    fun setup() {
        val transactionManager = mock<PlatformTransactionManager>()
        whenever(transactionManager.getTransaction(any())).thenReturn(SimpleTransactionStatus())
        val transactionTemplate = TransactionTemplate(transactionManager)
        val dataSource = mock<DataSource>()

        gameImportService =
            GameImportService(
                asyncJobRepository = mock(),
                appUserRepository = mock(),
                chessAccountRepository = mock(),
                gameRepository = mock(),
                importedArchiveRepository = mock(),
                archiveDerivedProcessingRepository = mock(),
                chessComClient = mock(),
                gameParserService = mock(),
                userPositionStatsRepository = mock(),
                positionOccurrenceRepository = positionOccurrenceRepository,
                positionRepository = mock(),
                engineAnalysisOrchestrator = mock(),
                transactionTemplate = transactionTemplate,
                accountOwnershipService = mock(),
                engineAnalysisRepository = engineAnalysisRepository,
                userPositionWeaknessRepository = userPositionWeaknessRepository,
                puzzleSchedulingEventRepository = puzzleSchedulingEventRepository,
                dataSource = dataSource,
            )
    }

    @Test
    fun `emitSchedulingEvents emits GAME_MISTAKE for checkmate blunders`() {
        val game =
            Game(
                id = UUID.randomUUID(),
                chessAccount = account,
                platformGameId = "game_1",
                pgn = "1. e4",
                playedAt = Instant.now(),
            )
        val occurrence =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "f3",
                playerColor = "WHITE",
            )

        val weakness = UserPositionWeakness(chessAccount = account, position = position, playerColor = "WHITE")
        whenever(userPositionWeaknessRepository.findByChessAccountIdAndPositionIdAndPlayerColor(account.id, position.id, "WHITE"))
            .thenReturn(weakness)

        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalMate = 1,
                bestMove = "Re8#",
                bestMoveEvalMate = 1,
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "f3", evalMate = -1, evalLossFromBest = 19.5),
        )
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(position.id)).thenReturn(analysis)

        whenever(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                setOf(position.id),
            ),
        ).thenReturn(listOf(occurrence))
        whenever(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "BLACK",
                setOf(position.id),
            ),
        ).thenReturn(emptyList())

        invokeEmitSchedulingEvents(listOf(game), user, setOf(position.id))

        val captor = argumentCaptor<PuzzleSchedulingEvent>()
        verify(puzzleSchedulingEventRepository, times(2)).saveAndFlush(captor.capture())

        val eventTypes = captor.allValues.map { it.eventType }
        assertTrue(eventTypes.contains(SchedulingEventType.GAME_REENCOUNTERED))
        assertTrue(eventTypes.contains(SchedulingEventType.GAME_MISTAKE))
    }

    @Test
    fun `emitSchedulingEvents emits GAME_HANDLED_SUCCESSFULLY for evaluated non-mistake`() {
        val game =
            Game(
                id = UUID.randomUUID(),
                chessAccount = account,
                platformGameId = "game_2",
                pgn = "1. Re8#",
                playedAt = Instant.now(),
            )
        val occurrence =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "Re8#",
                playerColor = "WHITE",
            )

        val weakness = UserPositionWeakness(chessAccount = account, position = position, playerColor = "WHITE")
        whenever(userPositionWeaknessRepository.findByChessAccountIdAndPositionIdAndPlayerColor(account.id, position.id, "WHITE"))
            .thenReturn(weakness)

        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalMate = 1,
                bestMove = "Re8#",
                bestMoveEvalMate = 1,
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "Re8#", evalMate = 1, evalLossFromBest = 0.0),
        )
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(position.id)).thenReturn(analysis)

        whenever(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                setOf(position.id),
            ),
        ).thenReturn(listOf(occurrence))
        whenever(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "BLACK",
                setOf(position.id),
            ),
        ).thenReturn(emptyList())

        invokeEmitSchedulingEvents(listOf(game), user, setOf(position.id))

        val captor = argumentCaptor<PuzzleSchedulingEvent>()
        verify(puzzleSchedulingEventRepository, times(2)).saveAndFlush(captor.capture())

        val eventTypes = captor.allValues.map { it.eventType }
        assertTrue(eventTypes.contains(SchedulingEventType.GAME_REENCOUNTERED))
        assertTrue(eventTypes.contains(SchedulingEventType.GAME_HANDLED_SUCCESSFULLY))
    }

    @Test
    fun `emitSchedulingEvents suppresses GAME_HANDLED_SUCCESSFULLY when evaluation is missing or inconclusive`() {
        val game =
            Game(
                id = UUID.randomUUID(),
                chessAccount = account,
                platformGameId = "game_3",
                pgn = "1. a3",
                playedAt = Instant.now(),
            )
        val occurrence =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "a3",
                playerColor = "WHITE",
            )

        val weakness = UserPositionWeakness(chessAccount = account, position = position, playerColor = "WHITE")
        whenever(userPositionWeaknessRepository.findByChessAccountIdAndPositionIdAndPlayerColor(account.id, position.id, "WHITE"))
            .thenReturn(weakness)

        // Position analysis exists, but candidate move has no evaluation (evalLoss is null)
        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalMate = 1,
                bestMove = "Re8#",
                bestMoveEvalMate = 1,
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "a3", evalLossFromBest = null),
        )
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(position.id)).thenReturn(analysis)

        whenever(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                setOf(position.id),
            ),
        ).thenReturn(listOf(occurrence))
        whenever(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "BLACK",
                setOf(position.id),
            ),
        ).thenReturn(emptyList())

        invokeEmitSchedulingEvents(listOf(game), user, setOf(position.id))

        val captor = argumentCaptor<PuzzleSchedulingEvent>()
        // Only 1 event emitted: GAME_REENCOUNTERED. Outcome event is suppressed!
        verify(puzzleSchedulingEventRepository, times(1)).saveAndFlush(captor.capture())

        assertEquals(SchedulingEventType.GAME_REENCOUNTERED, captor.firstValue.eventType)
    }

    private fun invokeEmitSchedulingEvents(
        savedGames: List<Game>,
        initiatingUser: AppUser?,
        affectedPositionIds: Set<UUID>,
    ) {
        val method =
            GameImportService::class.java.getDeclaredMethod(
                "emitSchedulingEvents",
                List::class.java,
                AppUser::class.java,
                Set::class.java,
            ).apply { isAccessible = true }
        method.invoke(gameImportService, savedGames, initiatingUser, affectedPositionIds)
    }
}
