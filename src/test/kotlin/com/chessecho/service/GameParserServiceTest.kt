package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.Position
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

class GameParserServiceTest {
    private lateinit var positionRepository: PositionRepository
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository
    private lateinit var gameParserService: GameParserService

    @BeforeEach
    fun setup() {
        positionRepository = mock()
        positionOccurrenceRepository = mock()
        gameParserService = GameParserService(positionRepository, positionOccurrenceRepository)
    }

    @Test
    fun `parseAndSavePositions extracts positions correctly`() {
        // Mock DB dependencies
        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.saveAll(any<List<Position>>())).thenAnswer {
            val list = it.getArgument<List<Position>>(0)
            list
        }

        val appUser = AppUser(email = "test@example.com")
        val chessAccount = ChessAccount(user = appUser, platform = "CHESS_COM", username = "tester")
        val game =
            Game(
                chessAccount = chessAccount,
                platformGameId = "test-game-1",
                whiteUsername = "tester",
                blackUsername = "opponent",
                pgn =
                    """
                    [Event "FIDE World Cup 2017"]
                    [White "tester"]
                    [Black "opponent"]

                    1. e4 e5 2. Nf3 Nc6 3. Bb5 1/2-1/2
                    """.trimIndent(),
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(game))

        // 3 decision plies for White: e4, Nf3, Bb5
        val positionsCaptor = argumentCaptor<List<Position>>()
        verify(positionRepository, times(1)).saveAll(positionsCaptor.capture())

        val savedPositions = positionsCaptor.firstValue
        assertEquals(3, savedPositions.size)

        // Before e4 (startpos)
        val hashStartFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val expectedHashStart = digest.digest(hashStartFen.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        assertTrue(savedPositions.any { it.hash == expectedHashStart }, "Expected start position before e4 not found")

        val occurrencesCaptor = argumentCaptor<List<com.chessecho.domain.PositionOccurrence>>()
        verify(positionOccurrenceRepository, times(1)).saveAll(occurrencesCaptor.capture())

        val savedOccurrences = occurrencesCaptor.firstValue
        assertEquals(3, savedOccurrences.size)

        val ply1 = savedOccurrences.first { it.plyNumber == 1 }
        assertEquals("e4", ply1.movePlayed)
        assertEquals("WHITE", ply1.playerColor)

        val ply5 = savedOccurrences.first { it.plyNumber == 5 }
        assertEquals("Bb5", ply5.movePlayed)
        assertEquals("WHITE", ply5.playerColor)
    }

    @Test
    fun `transpositions generate the same hash`() {
        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.saveAll(any<List<Position>>())).thenAnswer { it.getArgument<List<Position>>(0) }

        val appUser = AppUser(email = "test@example.com")
        val chessAccount = ChessAccount(user = appUser, platform = "CHESS_COM", username = "tester")

        // Game 1: 1. d3 d6 2. e3 e6 (2 White positions)
        val game1 =
            Game(
                chessAccount = chessAccount,
                platformGameId = "test-game-1",
                whiteUsername = "tester",
                blackUsername = "opponent",
                pgn = "1. d3 d6 2. e3 e6 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )
        // Game 2: 1. e3 e6 2. d3 d6 (2 White positions)
        val game2 =
            Game(
                chessAccount = chessAccount,
                platformGameId = "test-game-2",
                whiteUsername = "tester",
                blackUsername = "opponent",
                pgn = "1. e3 e6 2. d3 d6 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(game1, game2))

        val positionsCaptor = argumentCaptor<List<Position>>()
        verify(positionRepository, times(1)).saveAll(positionsCaptor.capture())

        val savedPositions = positionsCaptor.firstValue

        // Game 1 has 2 White positions (before d3, before e3), Game 2 has 2 White positions (before e3, before d3).
        // The start position is shared in both games.
        // Total unique White positions across G1 and G2 = 3.
        assertEquals(3, savedPositions.size, "Transpositions must generate the exact same hash and deduplicate")
    }

    @Test
    fun `different castling rights generate different hashes`() {
        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.saveAll(any<List<Position>>())).thenAnswer { it.getArgument<List<Position>>(0) }

        val appUser = AppUser(email = "test@example.com")
        val chessAccount = ChessAccount(user = appUser, platform = "CHESS_COM", username = "tester")

        // Game 1: King moves and returns, losing castling rights (3 White positions: before e4, before Ke2, before Ke1)
        val game1 =
            Game(
                chessAccount = chessAccount,
                platformGameId = "test-game-1",
                whiteUsername = "tester",
                blackUsername = "opponent",
                pgn = "1. e4 e5 2. Ke2 d6 3. Ke1 d5 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )
        // Game 2: Knight moves and returns, retaining castling rights (3 White positions: before e4, before Nf3, before Ng1)
        val game2 =
            Game(
                chessAccount = chessAccount,
                platformGameId = "test-game-2",
                whiteUsername = "tester",
                blackUsername = "opponent",
                pgn = "1. e4 e5 2. Nf3 d6 3. Ng1 d5 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(game1, game2))

        val positionsCaptor = argumentCaptor<List<Position>>()
        verify(positionRepository, times(1)).saveAll(positionsCaptor.capture())

        val savedPositions = positionsCaptor.firstValue

        // Plies 1 (startpos) and 3 (after 1... e5) are identical across both games.
        // Ply 5 (before 3. Ke1 vs before 3. Ng1) differ due to piece placement & castling rights.
        // Total unique White positions = 2 shared + 1 unique G1 + 1 unique G2 = 4.
        assertEquals(4, savedPositions.size, "Different castling rights must generate different hashes")
    }
}
