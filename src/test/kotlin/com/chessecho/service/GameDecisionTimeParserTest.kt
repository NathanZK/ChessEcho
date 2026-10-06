package com.chessecho.service

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

class GameDecisionTimeParserTest {
    private lateinit var positionRepository: PositionRepository
    private lateinit var occurrenceRepository: PositionOccurrenceRepository
    private lateinit var parser: GameParserService

    @BeforeEach
    fun setUp() {
        positionRepository = mock()
        occurrenceRepository = mock()
        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.saveAll(any<List<Position>>())).thenAnswer { it.getArgument(0) }
        whenever(occurrenceRepository.saveAll(any<List<PositionOccurrence>>())).thenAnswer { it.getArgument(0) }
        parser = GameParserService(positionRepository, occurrenceRepository)
    }

    @Test
    fun `derives account decision times from post-move clocks and increment`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "tester")
        val game =
            game(
                account = account,
                white = "tester",
                pgn =
                    """
                    [White "tester"]
                    [Black "opponent"]
                    [TimeControl "60+2"]

                    1. e4 {[%clk 0:01:01.5]} e5 {[%clk 0:01:01.0]} 2. Nf3 {[%clk 0:01:01.2]} *
                    """.trimIndent(),
            )

        parser.parseAndSavePositions(listOf(game))

        val occurrences = argumentCaptor<List<PositionOccurrence>>()
        org.mockito.kotlin.verify(occurrenceRepository).saveAll(occurrences.capture())
        assertEquals(
            listOf(500L, 2_300L),
            occurrences.firstValue.sortedBy { it.plyNumber }.map { it.decisionTimeMs },
        )
    }

    @Test
    fun `derives black first decision from initial control and later decision from own clock`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "tester")
        val game =
            game(
                account = account,
                white = "opponent",
                black = "tester",
                pgn =
                    """
                    [White "opponent"]
                    [Black "tester"]
                    [TimeControl "60+2"]

                    1. e4 {[%clk 0:01:01.8]} e5 {[%clk 0:01:01.3]} 2. Nf3 {[%clk 0:01:01.6]} 2... Nc6 {[%clk 0:01:01.7]} *
                    """.trimIndent(),
            )

        parser.parseAndSavePositions(listOf(game))

        val occurrences = argumentCaptor<List<PositionOccurrence>>()
        org.mockito.kotlin.verify(occurrenceRepository).saveAll(occurrences.capture())
        assertEquals(
            listOf(700L, 1_600L),
            occurrences.firstValue.sortedBy { it.plyNumber }.map { it.decisionTimeMs },
        )
    }

    @Test
    fun `missing required account clock excludes all game timings but preserves occurrences`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "tester")
        val game =
            game(
                account = account,
                white = "tester",
                pgn =
                    """
                    [White "tester"]
                    [Black "opponent"]
                    [TimeControl "60"]

                    1. e4 {[%clk 0:00:59]} e5 2. Nf3 *
                    """.trimIndent(),
            )

        parser.parseAndSavePositions(listOf(game))

        val occurrences = argumentCaptor<List<PositionOccurrence>>()
        org.mockito.kotlin.verify(occurrenceRepository).saveAll(occurrences.capture())
        assertEquals(2, occurrences.firstValue.size)
        assertEquals(setOf("e4", "Nf3"), occurrences.firstValue.map { it.movePlayed }.toSet())
        assertEquals(2, occurrences.firstValue.count { it.decisionTimeMs == null })
    }

    @Test
    fun `missing opponent clock does not prevent deriving account decisions`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "tester")
        val game =
            game(
                account = account,
                white = "tester",
                pgn =
                    """
                    [White "tester"]
                    [Black "opponent"]
                    [TimeControl "60"]

                    1. e4 {[%clk 0:00:59]} e5 2. Nf3 {[%clk 0:00:58]} *
                    """.trimIndent(),
            )

        parser.parseAndSavePositions(listOf(game))

        val occurrences = argumentCaptor<List<PositionOccurrence>>()
        org.mockito.kotlin.verify(occurrenceRepository).saveAll(occurrences.capture())
        assertEquals(listOf(1_000L, 1_000L), occurrences.firstValue.sortedBy { it.plyNumber }.map { it.decisionTimeMs })
    }

    @Test
    fun `replay refreshes and clears timing without replacing existing occurrences`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "tester")
        val originalGame =
            game(
                account = account,
                white = "tester",
                pgn =
                    """
                    [White "tester"]
                    [Black "opponent"]
                    [TimeControl "60+2"]

                    1. e4 {[%clk 0:01:01.5]} e5 {[%clk 0:01:01.0]} 2. Nf3 {[%clk 0:01:01.2]} *
                    """.trimIndent(),
            )
        parser.parseAndSavePositions(listOf(originalGame))

        val persistedOccurrences = argumentCaptor<List<PositionOccurrence>>()
        org.mockito.kotlin.verify(occurrenceRepository).saveAll(persistedOccurrences.capture())
        val existing = persistedOccurrences.firstValue
        whenever(positionRepository.findByHashIn(any())).thenReturn(existing.map { it.position }.distinct())
        whenever(occurrenceRepository.findByGameIdIn(any())).thenReturn(existing)

        val updatedGame =
            Game(
                id = originalGame.id,
                chessAccount = account,
                platformGameId = originalGame.platformGameId,
                pgn = originalGame.pgn.replace("0:01:01.5", "0:01:00.5"),
                timeControl = originalGame.timeControl,
                whiteUsername = originalGame.whiteUsername,
                blackUsername = originalGame.blackUsername,
            )
        parser.parseAndSavePositions(listOf(updatedGame))

        org.mockito.kotlin.verify(occurrenceRepository).updateDecisionTime(
            existing[0].game.id,
            existing[0].position.id,
            existing[0].plyNumber,
            existing[0].playerColor,
            1_500L,
        )

        val ineligibleReplay =
            Game(
                id = originalGame.id,
                chessAccount = account,
                platformGameId = originalGame.platformGameId,
                pgn = updatedGame.pgn.replace("{[%clk 0:01:01.2]}", ""),
                timeControl = originalGame.timeControl,
                whiteUsername = originalGame.whiteUsername,
                blackUsername = originalGame.blackUsername,
            )
        parser.parseAndSavePositions(listOf(ineligibleReplay))

        org.mockito.kotlin.verify(occurrenceRepository, org.mockito.kotlin.times(2)).updateDecisionTime(
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.isNull(),
        )
    }

    private fun game(
        account: ChessAccount,
        white: String,
        black: String = if (white == "tester") "opponent" else "tester",
        pgn: String,
    ) = Game(
        chessAccount = account,
        platformGameId = "clock-fixture",
        pgn = pgn,
        timeControl = "blitz",
        whiteUsername = white,
        blackUsername = black,
    )
}
