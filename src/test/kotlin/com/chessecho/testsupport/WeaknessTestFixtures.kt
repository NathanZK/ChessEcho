package com.chessecho.testsupport

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import java.time.Instant
import java.util.UUID

object WeaknessTestFixtures {
    fun createAccount(
        id: UUID = UUID.randomUUID(),
        username: String = "testuser",
        platform: Platform = Platform.CHESS_COM,
        createdAt: Instant = Instant.now(),
    ): ChessAccount =
        ChessAccount(
            id = id,
            platform = platform.name,
            username = username,
            createdAt = createdAt,
        )

    fun createPosition(
        id: UUID = UUID.randomUUID(),
        hash: String = UUID.randomUUID().toString(),
        fen: String = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
        createdAt: Instant = Instant.now(),
    ): Position =
        Position(
            id = id,
            hash = hash,
            fen = fen,
            createdAt = createdAt,
        )

    fun createGame(
        id: UUID = UUID.randomUUID(),
        account: ChessAccount = createAccount(),
        platformGameId: String = "game-${UUID.randomUUID()}",
        pgn: String = "1. e4 e5",
        timeControl: String? = "600",
        playedAt: Instant? = Instant.now(),
        result: String? = "1-0",
        whiteUsername: String? = "whitePlayer",
        blackUsername: String? = "blackPlayer",
    ): Game =
        Game(
            id = id,
            chessAccount = account,
            platformGameId = platformGameId,
            pgn = pgn,
            timeControl = timeControl,
            playedAt = playedAt,
            result = result,
            whiteUsername = whiteUsername,
            blackUsername = blackUsername,
        )

    fun createOccurrence(
        game: Game = createGame(),
        position: Position = createPosition(),
        account: ChessAccount = game.chessAccount,
        plyNumber: Int = 1,
        movePlayed: String = "e4",
        playerColor: PlayerColor = PlayerColor.WHITE,
        decisionTimeMs: Long? = 1000L,
        createdAt: Instant = Instant.now(),
    ): PositionOccurrence =
        PositionOccurrence(
            game = game,
            position = position,
            chessAccount = account,
            plyNumber = plyNumber,
            movePlayed = movePlayed,
            playerColor = playerColor.name,
            decisionTimeMs = decisionTimeMs,
            createdAt = createdAt,
        )

    fun createEngineAnalysis(
        position: Position = createPosition(),
        depth: Int = 20,
        baselineEvalCp: Int? = 0,
        baselineEvalMate: Int? = null,
        bestMove: String? = "e4",
        bestMoveEvalCp: Int? = 0,
        bestMoveEvalMate: Int? = null,
    ): EngineAnalysis =
        EngineAnalysis(
            position = position,
            depth = depth,
            baselineEvalCp = baselineEvalCp,
            baselineEvalMate = baselineEvalMate,
            bestMove = bestMove,
            bestMoveEvalCp = bestMoveEvalCp,
            bestMoveEvalMate = bestMoveEvalMate,
        )

    fun createMoveEvaluation(
        analysis: EngineAnalysis = createEngineAnalysis(),
        move: String = "e4",
        evalLossFromBest: Double? = 0.5,
    ): MoveEvaluation =
        MoveEvaluation(
            engineAnalysis = analysis,
            move = move,
            evalLossFromBest = evalLossFromBest,
        )
}
