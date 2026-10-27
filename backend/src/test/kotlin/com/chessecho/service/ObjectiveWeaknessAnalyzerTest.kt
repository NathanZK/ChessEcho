package com.chessecho.service

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Platform
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.WeaknessAggregation
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ObjectiveWeaknessAnalyzerTest {
    private val analyzer = ObjectiveWeaknessAnalyzer()

    @Test
    fun `classifyPhase evaluates 4-rule precedence correctly`() {
        // Rule 1: Corrupt FEN (< 8 ranks) -> OPENING
        val corruptFen = "rnbqkbnr/pppppppp 0 1"
        assertEquals("OPENING", analyzer.classifyPhase(corruptFen, plyNumber = 30))

        // Rule 2: Reduced piece endgame (<= 6 major/minor pieces) -> ENDGAME
        // 2 rooks (R, r) + 2 kings (K, k) = mmCount = 2 <= 6
        val endgameFen = "4k3/8/8/8/8/8/8/4K2R w - - 0 40"
        assertEquals("ENDGAME", analyzer.classifyPhase(endgameFen, plyNumber = 80))

        // Rule 3: Middlegame (mmCount <= 10 or backrankSparse or moveNumber > 14)
        // Full pieces on board (16 major/minor), but plyNumber = 31 -> moveNumber = (31+1)/2 = 16 > 14 -> MIDDLEGAME
        val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        assertEquals("MIDDLEGAME", analyzer.classifyPhase(startFen, plyNumber = 31))

        // Rule 4: Opening default (plyNumber <= 28, ranks intact, backranks full >= 4 pieces, mmCount > 10)
        assertEquals("OPENING", analyzer.classifyPhase(startFen, plyNumber = 2))
    }

    @Test
    fun `gameUrl generates correct platform specific URL`() {
        assertEquals("https://www.chess.com/game/live/12345", analyzer.gameUrl("CHESS_COM", "12345"))
        assertEquals("https://lichess.org/abcdef", analyzer.gameUrl("LICHESS", "abcdef"))
        assertEquals("https://custom.com/game/1", analyzer.gameUrl("CHESS_COM", "https://custom.com/game/1"))
        assertEquals("unknown-game-id", analyzer.gameUrl("OTHER", "unknown-game-id"))
    }

    @Test
    fun `openingContext returns null when games list has inconsistent ECO tags`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "alice")
        val game1 =
            Game(
                chessAccount = account,
                platformGameId = "123",
                pgn = "[ECO \"B01\"]\n[ECOUrl \"https://www.chess.com/openings/Scandinavian\"]\n\n1. e4",
            )
        val game2 =
            Game(
                chessAccount = account,
                platformGameId = "124",
                pgn = "[ECO \"C00\"]\n[ECOUrl \"https://www.chess.com/openings/French\"]\n\n1. e4",
            )
        assertNull(analyzer.openingContext(Platform.CHESS_COM, listOf(game1, game2)))
    }

    @Test
    fun `buildDraft returns null when mistake count is below minMistakeCount`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "alice")
        val position = Position(hash = "hash-1", fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        val aggregation =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                playerColor = "BLACK",
                timesReached = 5,
                mistakeCount = 1,
                averageLoss = 1.5,
                rawTotalLoss = 1.5,
                baselineEvalCp = 20,
                bestMove = "e5",
            )
        val game =
            Game(
                chessAccount = account,
                platformGameId = "g1",
                pgn = "1. e4 e5",
                playedAt = Instant.now(),
            )
        val occurrence =
            PositionOccurrence(
                chessAccount = account,
                game = game,
                position = position,
                plyNumber = 2,
                movePlayed = "d5",
                playerColor = "BLACK",
            )
        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                bestMove = "e5",
                bestMoveEvalCp = 20,
            )
        val moveEvaluation =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "d5",
                evalCp = -130,
                evalLossFromBest = null,
            )
        analysis.moveEvaluations.add(moveEvaluation)

        val draft =
            analyzer.buildDraft(
                aggregation = aggregation,
                scopedOccurrences = listOf(occurrence),
                analysis = analysis,
                minEvalLoss = 0.8,
                minMistakeCount = 3,
                platform = Platform.CHESS_COM,
                account = account,
            )

        assertNull(draft)
    }

    @Test
    fun `buildDraft creates valid ObjectiveWeaknessDraft with correctly aggregated stats`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "alice")
        val position = Position(hash = "hash-2", fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        val aggregation = createAggregation(position)
        val occurrences = createOccurrences(account, position)
        val analysis = createAnalysis(position)

        val draft =
            analyzer.buildDraft(
                aggregation = aggregation,
                scopedOccurrences = occurrences,
                analysis = analysis,
                minEvalLoss = 0.8,
                minMistakeCount = 3,
                platform = Platform.CHESS_COM,
                account = account,
            )

        assertNotNull(draft)
        assertEquals(position.id, draft.positionId)
        assertEquals(3, draft.mistakeCount)
        assertEquals(60.0, draft.mistakeRate)
        assertEquals(1, draft.acceptableMoves.size)
        assertEquals("e5", draft.acceptableMoves[0].move)
        assertEquals(1, draft.movesPlayed.size)
        assertEquals("d5", draft.movesPlayed[0].move)
        assertEquals(3, draft.movesPlayed[0].timesPlayed)
        assertNotNull(draft.openingContext)
        assertEquals("B01", draft.openingContext?.eco)
        assertNotNull(draft.timeControlStats?.get("BLITZ"))
        assertEquals(3, draft.timeControlStats?.get("BLITZ")?.mistakeCount)
    }

    private fun createAggregation(position: Position): WeaknessAggregation =
        WeaknessAggregation(
            positionId = position.id,
            fen = position.fen,
            playerColor = "BLACK",
            timesReached = 5,
            mistakeCount = 3,
            averageLoss = 1.2,
            rawTotalLoss = 3.6,
            baselineEvalCp = 20,
            bestMove = "e5",
        )

    private fun createOccurrences(
        account: ChessAccount,
        position: Position,
    ): List<PositionOccurrence> =
        (1..3).map { i ->
            val game =
                Game(
                    chessAccount = account,
                    platformGameId = "game-$i",
                    pgn = "[ECO \"B01\"]\n[ECOUrl \"https://www.chess.com/openings/Scandinavian-Defense\"]\n\n1. e4 d5",
                    timeControl = "blitz",
                    playedAt = Instant.now(),
                )
            PositionOccurrence(
                chessAccount = account,
                game = game,
                position = position,
                plyNumber = 2,
                movePlayed = "d5",
                playerColor = "BLACK",
            )
        }

    private fun createAnalysis(position: Position): EngineAnalysis {
        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                bestMove = "e5",
                bestMoveEvalCp = 20,
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "d5",
                evalCp = -100,
                evalLossFromBest = null,
            ),
        )
        analysis.moveEvaluations.add(
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e5",
                evalCp = 20,
                evalLossFromBest = null,
            ),
        )
        return analysis
    }
}
