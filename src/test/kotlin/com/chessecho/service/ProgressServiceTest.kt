package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProgressServiceTest {
    private val accountRepository = mock<ChessAccountRepository>()
    private val occurrenceRepository = mock<PositionOccurrenceRepository>()
    private val analysisRepository = mock<EngineAnalysisRepository>()
    private val service =
        ProgressService(
            accountRepository,
            occurrenceRepository,
            analysisRepository,
            GameOutcomeNormalizer(PgnHeaderTagReader()),
        )
    private val user = AppUser()
    private val account = ChessAccount(user = user, platform = "CHESS_COM", username = "player")
    private val position = Position(hash = "progress", fen = "8/8/8/8/8/8/8/8 w - -")
    private val principal = AuthenticatedPrincipal(user.id, devPrincipal = false)
    private val firstPlayedAt = Instant.parse("2026-09-01T12:00:00Z")

    @ParameterizedTest
    @CsvSource(
        "WHITE, win, 100",
        "BLACK, resigned, 100",
        "WHITE, resigned, 0",
        "BLACK, win, 0",
        "WHITE, agreed, 0",
        "BLACK, agreed, 0",
    )
    fun `first encounter counts normalized wins only`(
        color: PlayerColor,
        sourceResult: String,
        expectedRate: Double,
    ) {
        stubOccurrences(color, listOf(occurrence(color, sourceResult)))

        val response = service.getProgress(position.id, color, principal)

        assertEquals(1, response.points.size)
        val point = response.points.single()
        assertEquals(1, point.attempts)
        assertEquals(firstPlayedAt, point.occurredAt)
        assertEquals(expectedRate, point.winRate)
        assertEquals("More played encounters are needed to assess progress.", response.assessment)
    }

    @ParameterizedTest
    @EnumSource(value = PlayerColor::class, names = ["WHITE", "BLACK"])
    fun `ordered encounters retain first point and cumulative win loss draw rates`(color: PlayerColor) {
        val results = if (color == PlayerColor.WHITE) listOf("win", "resigned", "agreed") else listOf("resigned", "win", "agreed")
        val occurrences = results.mapIndexed { index, result -> occurrence(color, result, index = index) }
        stubOccurrences(color, occurrences)
        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 0, bestMove = "best", bestMoveEvalCp = 0)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "move-0", evalCp = -100, evalLossFromBest = 1.0))
        whenever(analysisRepository.findByPositionIdWithMoveEvaluations(position.id)).thenReturn(analysis)

        val response = service.getProgress(position.id, color, principal)

        assertEquals(listOf(1, 2, 3), response.points.map { it.attempts })
        assertEquals(occurrences.map { it.game.playedAt }, response.points.map { it.occurredAt })
        response.points.zip(listOf(100.0, 50.0, 100.0 / 3)).forEach { (point, expected) ->
            assertEquals(expected, point.winRate, 0.000001)
            assertEquals(expected, point.mistakeRate, 0.000001)
        }
        assertEquals(-200.0 / 3, requireNotNull(response.winRateChange), 0.000001)
        assertEquals(-200.0 / 3, requireNotNull(response.mistakeRateChange), 0.000001)
        assertEquals("You are making fewer mistakes at this position.", response.assessment)
        verify(accountRepository).findAllByUserIdOrderByCreatedAtAsc(user.id)
    }

    @Test
    fun `unrecognized source result falls back to PGN Result`() {
        stubOccurrences(
            PlayerColor.BLACK,
            listOf(occurrence(PlayerColor.BLACK, "unknown", pgn = """[Result "0-1"]""")),
        )

        val point = service.getProgress(position.id, PlayerColor.BLACK, principal).points.single()

        assertEquals(1, point.attempts)
        assertEquals(100.0, point.winRate)
    }

    @Test
    fun `unnormalizable encounters remain in the denominator`() {
        stubOccurrences(
            PlayerColor.WHITE,
            listOf(
                occurrence(PlayerColor.WHITE, "win"),
                occurrence(PlayerColor.WHITE, "unknown", index = 1, pgn = """[Result "*"]"""),
            ),
        )

        val points = service.getProgress(position.id, PlayerColor.WHITE, principal).points

        assertEquals(listOf(1, 2), points.map { it.attempts })
        assertEquals(listOf(100.0, 50.0), points.map { it.winRate })
    }

    @Test
    fun `no owned account keeps existing account not found behavior`() {
        whenever(accountRepository.findAllByUserIdOrderByCreatedAtAsc(user.id)).thenReturn(emptyList())

        assertFailsWith<AccountNotFoundException> {
            service.getProgress(position.id, PlayerColor.WHITE, principal)
        }
        verify(accountRepository).findAllByUserIdOrderByCreatedAtAsc(user.id)
    }

    private fun stubOccurrences(
        color: PlayerColor,
        occurrences: List<PositionOccurrence>,
    ) {
        whenever(accountRepository.findAllByUserIdOrderByCreatedAtAsc(user.id)).thenReturn(listOf(account))
        whenever(occurrenceRepository.findProgressOccurrences(account.id, position.id, color.name)).thenReturn(occurrences)
    }

    private fun occurrence(
        color: PlayerColor,
        sourceResult: String,
        index: Int = 0,
        pgn: String = """[Result "*"]""",
    ): PositionOccurrence =
        PositionOccurrence(
            game =
                Game(
                    chessAccount = account,
                    platformGameId = "game-$index",
                    pgn = pgn,
                    result = sourceResult,
                    playedAt = firstPlayedAt.plusSeconds(index * 60L),
                    whiteUsername = if (color == PlayerColor.WHITE) account.username else "opponent",
                    blackUsername = if (color == PlayerColor.BLACK) account.username else "opponent",
                ),
            position = position,
            chessAccount = account,
            plyNumber = 1,
            movePlayed = "move-$index",
            playerColor = color.name,
        )
}
