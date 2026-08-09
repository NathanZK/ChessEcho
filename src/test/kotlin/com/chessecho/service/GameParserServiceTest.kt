package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.Position
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.UserPositionStatsRepository
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
    private lateinit var userPositionStatsRepository: UserPositionStatsRepository
    private lateinit var gameParserService: GameParserService

    @BeforeEach
    fun setup() {
        positionRepository = mock()
        positionOccurrenceRepository = mock()
        userPositionStatsRepository = mock()
        gameParserService = GameParserService(positionRepository, positionOccurrenceRepository, userPositionStatsRepository)
    }

    @Test
    fun `parseAndSavePositions extracts positions correctly`() {
        // Mock DB dependencies
        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.saveAll(any<List<Position>>())).thenAnswer {
            val list = it.getArgument<List<Position>>(0)
            list
        }
        whenever(userPositionStatsRepository.findByChessAccountIdAndPositionIdAndPlayerColor(any(), any(), any()))
            .thenReturn(null)

        val appUser = AppUser(email = "test@example.com")
        val chessAccount = ChessAccount(user = appUser, platform = "CHESS_COM", username = "tester")
        val game =
            Game(
                chessAccount = chessAccount,
                platformGameId = "test-game-1",
                pgn =
                    """
                    [Event "FIDE World Cup 2017"]
                    [White "Ivanchuk, V."]
                    [Black "Giri, A."]

                    1. e4 e5 2. Nf3 Nc6 3. Bb5 1/2-1/2
                    """.trimIndent(),
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(game))

        // 5 plies: e4, e5, Nf3, Nc6, Bb5
        val positionsCaptor = argumentCaptor<List<Position>>()
        verify(positionRepository, times(1)).saveAll(positionsCaptor.capture())

        val savedPositions = positionsCaptor.firstValue
        assertEquals(5, savedPositions.size)

        val fenAfterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        // After e4
        val hashE4CleanedFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val expectedHashE4 = digest.digest(hashE4CleanedFen.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        assertTrue(savedPositions.any { it.hash == expectedHashE4 }, "Expected position after e4 not found")

        val occurrencesCaptor = argumentCaptor<List<com.chessecho.domain.PositionOccurrence>>()
        verify(positionOccurrenceRepository, times(1)).saveAll(occurrencesCaptor.capture())

        val savedOccurrences = occurrencesCaptor.firstValue
        assertEquals(5, savedOccurrences.size)

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
        whenever(userPositionStatsRepository.findByChessAccountIdAndPositionIdAndPlayerColor(any(), any(), any()))
            .thenReturn(null)

        val appUser = AppUser(email = "test@example.com")
        val chessAccount = ChessAccount(user = appUser, platform = "CHESS_COM", username = "tester")

        // Game 1: 1. d3 d6 2. e3 e6 (4 positions)
        val game1 =
            Game(
                chessAccount = chessAccount,
                platformGameId = "test-game-1",
                pgn = "1. d3 d6 2. e3 e6 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )
        // Game 2: 1. e3 e6 2. d3 d6 (4 positions)
        val game2 =
            Game(
                chessAccount = chessAccount,
                platformGameId = "test-game-2",
                pgn = "1. e3 e6 2. d3 d6 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(game1, game2))

        val positionsCaptor = argumentCaptor<List<Position>>()
        verify(positionRepository, times(1)).saveAll(positionsCaptor.capture())

        val savedPositions = positionsCaptor.firstValue

        // Game 1 has 4 positions, Game 2 has 4 positions.
        // The 4th position in both games is identical (transposition).
        // So out of 8 total positions, 1 is shared, resulting in 7 unique positions.
        println("Transposition Size: " + savedPositions.size)
        assertEquals(7, savedPositions.size, "Transpositions must generate the exact same hash and deduplicate")
    }

    @Test
    fun `different castling rights generate different hashes`() {
        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.saveAll(any<List<Position>>())).thenAnswer { it.getArgument<List<Position>>(0) }
        whenever(userPositionStatsRepository.findByChessAccountIdAndPositionIdAndPlayerColor(any(), any(), any()))
            .thenReturn(null)

        val appUser = AppUser(email = "test@example.com")
        val chessAccount = ChessAccount(user = appUser, platform = "CHESS_COM", username = "tester")

        // Game 1: King moves and returns, losing castling rights (6 positions)
        val game1 =
            Game(
                chessAccount = chessAccount,
                platformGameId = "test-game-1",
                pgn = "1. e4 e5 2. Ke2 d6 3. Ke1 d5 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )
        // Game 2: Knight moves and returns, retaining castling rights (6 positions)
        val game2 =
            Game(
                chessAccount = chessAccount,
                platformGameId = "test-game-2",
                pgn = "1. e4 e5 2. Nf3 d6 3. Ng1 d5 1/2-1/2",
                timeControl = "600",
                playedAt = Instant.now(),
            )

        gameParserService.parseAndSavePositions(listOf(game1, game2))

        val positionsCaptor = argumentCaptor<List<Position>>()
        verify(positionRepository, times(1)).saveAll(positionsCaptor.capture())

        val savedPositions = positionsCaptor.firstValue

        // Plies 1 and 2 are identical (1. e4 e5).
        // Plies 3-6 are different because in Game 1 the King moves, and in Game 2 the Knight moves.
        // Even though piece placement is identical after ply 5 (Ke1 vs Ng1) and ply 6 (d5),
        // castling rights are different!
        // Total positions = 3 (shared: startpos, after e4, after e5) + 3 (unique to G1) + 3 (unique to G2) = 9.
        // If castling rights were ignored, the position before the final d5 would be shared too, resulting in 8.
        assertEquals(9, savedPositions.size, "Different castling rights must generate different hashes")
    }
}
