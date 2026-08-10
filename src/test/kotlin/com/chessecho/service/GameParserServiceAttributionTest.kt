package com.chessecho.service

import com.chessecho.domain.AppUser
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
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameParserServiceAttributionTest {
    private lateinit var positionRepository: PositionRepository
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository
    private lateinit var gameParserService: GameParserService

    @BeforeEach
    fun setup() {
        positionRepository = mock()
        positionOccurrenceRepository = mock()
        gameParserService = GameParserService(positionRepository, positionOccurrenceRepository)

        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.saveAll(any<List<Position>>())).thenAnswer {
            it.getArgument<List<Position>>(0)
        }
    }

    @Test
    fun `parseAndSavePositions attributes ONLY White moves to user when user is White`() {
        val appUser = AppUser(email = "gotham@example.com")
        val chessAccount = ChessAccount(user = appUser, platform = "CHESS_COM", username = "gothamchess")
        val game =
            Game(
                chessAccount = chessAccount,
                platformGameId = "game-white-1",
                whiteUsername = "gothamchess",
                blackUsername = "opponent",
                pgn =
                    """
                    [Event "Live Chess"]
                    [White "gothamchess"]
                    [Black "opponent"]

                    1. e4 e5 2. Nf3 Nc6 3. Bb5 1/2-1/2
                    """.trimIndent(),
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(game))

        val occurrencesCaptor = argumentCaptor<List<PositionOccurrence>>()
        verify(positionOccurrenceRepository, times(1)).saveAll(occurrencesCaptor.capture())

        val savedOccurrences = occurrencesCaptor.firstValue

        // Total half moves = 5 (e4, e5, Nf3, Nc6, Bb5)
        // White moves (user) = 3 (ply 1: e4, ply 3: Nf3, ply 5: Bb5)
        // Black moves (opponent) = 2 (ply 2: e5, ply 4: Nc6) -> MUST BE EXCLUDED!
        assertEquals(3, savedOccurrences.size)

        assertTrue(savedOccurrences.all { it.playerColor == "WHITE" })
        assertTrue(savedOccurrences.all { it.chessAccount.username == "gothamchess" })

        val plies = savedOccurrences.map { it.plyNumber }.sorted()
        assertEquals(listOf(1, 3, 5), plies)

        val moves = savedOccurrences.map { it.movePlayed }
        assertEquals(listOf("e4", "Nf3", "Bb5"), moves)
    }

    @Test
    fun `parseAndSavePositions attributes ONLY Black moves to user when user is Black`() {
        val appUser = AppUser(email = "gotham@example.com")
        val chessAccount = ChessAccount(user = appUser, platform = "CHESS_COM", username = "gothamchess")
        val game =
            Game(
                chessAccount = chessAccount,
                platformGameId = "game-black-1",
                whiteUsername = "opponent",
                blackUsername = "gothamchess",
                pgn =
                    """
                    [Event "Live Chess"]
                    [White "opponent"]
                    [Black "gothamchess"]

                    1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 1/2-1/2
                    """.trimIndent(),
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(game))

        val occurrencesCaptor = argumentCaptor<List<PositionOccurrence>>()
        verify(positionOccurrenceRepository, times(1)).saveAll(occurrencesCaptor.capture())

        val savedOccurrences = occurrencesCaptor.firstValue

        // Total half moves = 6 (e4, e5, Nf3, Nc6, Bb5, a6)
        // Black moves (user) = 3 (ply 2: e5, ply 4: Nc6, ply 6: a6)
        // White moves (opponent) = 3 (ply 1: e4, ply 3: Nf3, ply 5: Bb5) -> MUST BE EXCLUDED!
        assertEquals(3, savedOccurrences.size)

        assertTrue(savedOccurrences.all { it.playerColor == "BLACK" })
        assertTrue(savedOccurrences.all { it.chessAccount.username == "gothamchess" })

        val plies = savedOccurrences.map { it.plyNumber }.sorted()
        assertEquals(listOf(2, 4, 6), plies)

        val moves = savedOccurrences.map { it.movePlayed }
        assertEquals(listOf("e5", "Nc6", "a6"), moves)
    }

    @Test
    fun `parseAndSavePositions creates 0 occurrences when account username matches neither White nor Black`() {
        val appUser = AppUser(email = "gotham@example.com")
        val chessAccount = ChessAccount(user = appUser, platform = "CHESS_COM", username = "gothamchess")
        val game =
            Game(
                chessAccount = chessAccount,
                platformGameId = "game-unknown-1",
                whiteUsername = "opponent1",
                blackUsername = "opponent2",
                pgn =
                    """
                    [Event "Live Chess"]
                    [White "opponent1"]
                    [Black "opponent2"]

                    1. e4 e5 2. Nf3 Nc6 3. Bb5 1/2-1/2
                    """.trimIndent(),
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(game))

        // No occurrences created for unmatched player game
        val occurrencesCaptor = argumentCaptor<List<PositionOccurrence>>()
        verify(positionOccurrenceRepository, times(1)).saveAll(occurrencesCaptor.capture())

        val savedOccurrences = occurrencesCaptor.firstValue
        assertEquals(0, savedOccurrences.size)
    }

    @Test
    fun `parseAndSavePositions creates 0 occurrences when account username matches both White and Black`() {
        val appUser = AppUser(email = "gotham@example.com")
        val chessAccount = ChessAccount(user = appUser, platform = "CHESS_COM", username = "gothamchess")
        val game =
            Game(
                chessAccount = chessAccount,
                platformGameId = "game-dual-1",
                whiteUsername = "gothamchess",
                blackUsername = "gothamchess",
                pgn =
                    """
                    [Event "Live Chess"]
                    [White "gothamchess"]
                    [Black "gothamchess"]

                    1. e4 e5 2. Nf3 Nc6 3. Bb5 1/2-1/2
                    """.trimIndent(),
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(game))

        // Invalid dual identity -> 0 occurrences created
        val occurrencesCaptor = argumentCaptor<List<PositionOccurrence>>()
        verify(positionOccurrenceRepository, times(1)).saveAll(occurrencesCaptor.capture())

        val savedOccurrences = occurrencesCaptor.firstValue
        assertEquals(0, savedOccurrences.size)
    }
}
