package com.chessecho.repository

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@DataJpaTest
@ActiveProfiles("test")
class PositionOccurrenceRepositoryTest {
    @Autowired
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    private lateinit var engineAnalysisRepository: EngineAnalysisRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Test
    fun `findWeaknessAggregations counts mate blunders and ignores unevaluated moves even when minEvalLoss is 0_0`() {
        val account = chessAccountRepository.save(ChessAccount(platform = "CHESS_COM", username = "player1"))
        val game =
            gameRepository.save(
                Game(
                    chessAccount = account,
                    platformGameId = "game_mate_agg",
                    pgn = "1. e4",
                    timeControl = "blitz",
                    playedAt = Instant.now(),
                    result = "win",
                    whiteUsername = "player1",
                    blackUsername = "opponent",
                ),
            )
        val position = positionRepository.save(Position(hash = "mate_agg_pos", fen = "6k1/5ppp/8/8/8/8/5PPP/4R1K1 w - - 0 1"))

        setupMateBlunderAnalysis(position)
        setupMateBlunderOccurrences(game, position, account)

        val resultStandard =
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = account.id,
                playerColor = "WHITE",
                minEvalLoss = 0.8,
                minTimesReached = 1,
                minMistakeCount = 1L,
            )
        assertEquals(1, resultStandard.size)
        assertEquals(1L, resultStandard[0].mistakeCount)
        assertEquals(19.5, resultStandard[0].rawTotalLoss)

        val resultZeroThreshold =
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = account.id,
                playerColor = "WHITE",
                minEvalLoss = 0.0,
                minTimesReached = 1,
                minMistakeCount = 1L,
            )
        assertEquals(1, resultZeroThreshold.size)
        assertEquals(2L, resultZeroThreshold[0].mistakeCount)
    }

    @Test
    fun `remaining_clock_ms persists, retrieves, and updates on conflict`() {
        val account = chessAccountRepository.save(ChessAccount(platform = "CHESS_COM", username = "clock_user"))
        val game =
            gameRepository.save(
                Game(
                    chessAccount = account,
                    platformGameId = "game_clock",
                    pgn = "1. e4",
                    timeControl = "blitz",
                    playedAt = Instant.now(),
                    result = "win",
                    whiteUsername = "clock_user",
                    blackUsername = "opp",
                ),
            )
        val position =
            positionRepository.save(
                Position(hash = "clock_pos", fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"),
            )

        val occ =
            positionOccurrenceRepository.saveAndFlush(
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "e4",
                    playerColor = "WHITE",
                    decisionTimeMs = 2500L,
                    remainingClockMs = 175000L,
                ),
            )

        val retrieved = positionOccurrenceRepository.findById(occ.id).orElseThrow()
        assertEquals(175000L, retrieved.remainingClockMs)

        // JPQL update
        val updatedRows =
            positionOccurrenceRepository.updateDecisionTime(
                gameId = game.id,
                positionId = position.id,
                plyNumber = 1,
                playerColor = "WHITE",
                decisionTimeMs = 3000L,
                remainingClockMs = 170000L,
            )
        assertEquals(1, updatedRows)

        val refreshed = positionOccurrenceRepository.findById(occ.id).orElseThrow()
        assertEquals(3000L, refreshed.decisionTimeMs)
        assertEquals(170000L, refreshed.remainingClockMs)
    }

    private fun setupMateBlunderAnalysis(position: Position) {
        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = null,
                baselineEvalMate = 1,
                bestMove = "Re8#",
                bestMoveEvalCp = null,
                bestMoveEvalMate = 1,
            )
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "Re8#", evalMate = 1, evalLossFromBest = 0.0))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "f3", evalMate = -1, evalLossFromBest = 19.5))
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "a3", evalCp = null, evalMate = null, evalLossFromBest = null),
        )
        engineAnalysisRepository.saveAndFlush(analysis)
    }

    private fun setupMateBlunderOccurrences(
        game: Game,
        position: Position,
        account: ChessAccount,
    ) {
        val occ1 =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "Re8#",
                playerColor = "WHITE",
            )
        val occ2 =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 3,
                movePlayed = "f3",
                playerColor = "WHITE",
            )
        val occ3 =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 5,
                movePlayed = "a3",
                playerColor = "WHITE",
            )
        positionOccurrenceRepository.saveAllAndFlush(listOf(occ1, occ2, occ3))
    }

    @Test
    fun `findAllByGameIdOrderByPlyNumberAsc returns all occurrences for game ordered by ply number`() {
        val account = chessAccountRepository.save(ChessAccount(platform = "CHESS_COM", username = "order_test"))
        val game1 =
            gameRepository.save(
                Game(
                    chessAccount = account,
                    platformGameId = "game_order_1",
                    pgn = "1. e4 e5",
                    timeControl = "blitz",
                    playedAt = Instant.now(),
                    result = "win",
                    whiteUsername = "order_test",
                    blackUsername = "opponent",
                ),
            )
        val pos1 =
            positionRepository.save(
                Position(
                    hash = "order_pos_1",
                    fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
                ),
            )
        val pos2 =
            positionRepository.save(
                Position(
                    hash = "order_pos_2",
                    fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
                ),
            )

        val occ2 =
            PositionOccurrence(
                game = game1,
                position = pos2,
                chessAccount = account,
                plyNumber = 2,
                movePlayed = "e5",
                playerColor = "BLACK",
            )
        val occ1 =
            PositionOccurrence(
                game = game1,
                position = pos1,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "e4",
                playerColor = "WHITE",
            )
        positionOccurrenceRepository.saveAllAndFlush(listOf(occ2, occ1))

        val results = positionOccurrenceRepository.findAllByGameIdOrderByPlyNumberAsc(game1.id)
        assertEquals(2, results.size)
        assertEquals(1, results[0].plyNumber)
        assertEquals(2, results[1].plyNumber)
    }

    @Test
    fun `findAllByChessAccountIdAndPositionIdIn breaks timestamp and ply ties deterministically by id ASC`() {
        val account = chessAccountRepository.save(ChessAccount(platform = "CHESS_COM", username = "tie_breaker_user"))
        val now = Instant.now()
        val game =
            gameRepository.save(
                Game(
                    chessAccount = account,
                    platformGameId = "game_tie",
                    pgn = "1. e4 e5",
                    timeControl = "blitz",
                    playedAt = now,
                    result = "win",
                    whiteUsername = "tie_breaker_user",
                    blackUsername = "opponent",
                ),
            )
        val pos1 = positionRepository.save(Position(hash = "tie_pos_1", fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"))
        val pos2 =
            positionRepository.save(
                Position(hash = "tie_pos_2", fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"),
            )

        val occ1 =
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = pos1,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "e4",
                    playerColor = "WHITE",
                ),
            )
        val occ2 =
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = pos2,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "e4",
                    playerColor = "WHITE",
                ),
            )
        positionOccurrenceRepository.flush()

        val expectedFirst = if (occ1.id.toString() < occ2.id.toString()) occ1.id else occ2.id
        val expectedSecond = if (occ1.id.toString() < occ2.id.toString()) occ2.id else occ1.id

        val results =
            positionOccurrenceRepository.findAllByChessAccountIdAndPositionIdIn(
                account.id,
                listOf(pos1.id, pos2.id),
            )

        assertEquals(2, results.size)
        assertEquals(expectedFirst, results[0].id)
        assertEquals(expectedSecond, results[1].id)
    }
}
