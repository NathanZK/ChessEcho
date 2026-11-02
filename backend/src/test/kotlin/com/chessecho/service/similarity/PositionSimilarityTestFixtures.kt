package com.chessecho.service.similarity

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.service.ContinuationResult
import com.chessecho.service.PrincipalVariation
import com.chessecho.service.SearchStatus
import java.time.Instant
import java.util.UUID

internal fun createTestGame(
    account: ChessAccount,
    id: UUID = UUID.randomUUID(),
    platformGameId: String = "https://www.chess.com/game/live/${UUID.randomUUID()}",
    whiteUsername: String = "testplayer",
    blackUsername: String = "opponent",
    result: String = "win",
): Game =
    Game(
        id = id,
        chessAccount = account,
        platformGameId = platformGameId,
        pgn = "1. e4 e5",
        timeControl = "600",
        playedAt = Instant.parse("2026-01-10T12:00:00Z"),
        whiteUsername = whiteUsername,
        blackUsername = blackUsername,
        result = result,
    )

internal fun createTestOccurrence(
    account: ChessAccount,
    game: Game,
    positionId: UUID,
    fen: String,
    playerColor: String = "WHITE",
    movePlayed: String = "f3",
    plyNumber: Int = 40,
): PositionOccurrence =
    PositionOccurrence(
        id = UUID.randomUUID(),
        game = game,
        position = Position(id = positionId, hash = fen, fen = fen),
        chessAccount = account,
        plyNumber = plyNumber,
        movePlayed = movePlayed,
        playerColor = playerColor,
    )

internal fun generateCandidateFens(count: Int): List<String> =
    (0 until count).map { i ->
        val file1 = i % 8
        val file2 = (i / 8) % 8
        val r6 = formatFenRank(file1, 'n')
        val r3 = formatFenRank(file2, 'N')
        if (i < 64) {
            "4k3/pppppppp/$r6/8/8/$r3/PPPPPPPP/4K3 w - - 0 1"
        } else {
            val r5 = formatFenRank(file1, 'b')
            "4k3/pppppppp/8/$r5/8/$r3/PPPPPPPP/4K3 w - - 0 1"
        }
    }

private fun formatFenRank(
    file: Int,
    piece: Char,
): String =
    buildString {
        if (file > 0) append(file)
        append(piece)
        if (7 - file > 0) append(7 - file)
    }

internal fun completedContinuation(
    depth: Int = 8,
    moves: List<String> = emptyList(),
): ContinuationResult =
    ContinuationResult(
        status = SearchStatus.COMPLETED,
        achievedDepth = depth,
        principalVariations = listOf(PrincipalVariation(1, moves, depth)),
    )

internal fun interruptedContinuation(depth: Int = 4): ContinuationResult =
    ContinuationResult(
        status = SearchStatus.INTERRUPTED,
        achievedDepth = depth,
        principalVariations = emptyList(),
    )
