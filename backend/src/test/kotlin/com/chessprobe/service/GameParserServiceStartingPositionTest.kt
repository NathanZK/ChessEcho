package com.chessprobe.service

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.Game
import com.chessprobe.domain.Position
import com.chessprobe.domain.PositionOccurrence
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.repository.PositionRepository
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
import kotlin.test.assertFalse

class GameParserServiceStartingPositionTest {
    private lateinit var positionRepository: PositionRepository
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository
    private lateinit var gameParserService: GameParserService

    @BeforeEach
    fun setUp() {
        positionRepository = mock()
        positionOccurrenceRepository = mock()
        gameParserService = GameParserService(positionRepository, positionOccurrenceRepository)
    }

    @Test
    fun `parseAndSavePositions excludes standard initial starting position from persisted positions and occurrences when user is White`() {
        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.saveAll(any<List<Position>>())).thenAnswer { it.getArgument<List<Position>>(0) }

        val account = ChessAccount(platform = "CHESS_COM", username = "tester")
        val game =
            Game(
                chessAccount = account,
                platformGameId = "white-start-pos-game",
                whiteUsername = "tester",
                blackUsername = "opponent",
                pgn =
                    """
                    [Event "Live Chess"]
                    [White "tester"]
                    [Black "opponent"]

                    1. e4 e5 2. Nf3 Nc6 3. Bb5 1/2-1/2
                    """.trimIndent(),
                timeControl = "600",
                playedAt = Instant.now(),
            )

        val affected = gameParserService.parseAndSavePositions(listOf(game))

        val positionsCaptor = argumentCaptor<List<Position>>()
        verify(positionRepository, times(1)).saveAll(positionsCaptor.capture())
        val savedPositions = positionsCaptor.firstValue

        val occurrencesCaptor = argumentCaptor<List<PositionOccurrence>>()
        verify(positionOccurrenceRepository, times(1)).saveAll(occurrencesCaptor.capture())
        val savedOccurrences = occurrencesCaptor.firstValue

        // Standard start FEN must NOT be persisted in Position table
        assertFalse(savedPositions.any { GameParserService.isStandardStartFen(it.fen) }, "Start FEN must not be persisted")
        // Standard start FEN ply (ply 1) must NOT be persisted in PositionOccurrence table
        assertFalse(savedOccurrences.any { it.plyNumber == 1 }, "Ply 1 start occurrence must not be persisted")

        // Subsequent positions for White (ply 3: Nf3, ply 5: Bb5) must be preserved
        assertEquals(2, savedPositions.size)
        assertEquals(2, affected.size)
        assertEquals(listOf(3, 5), savedOccurrences.map { it.plyNumber })
        assertEquals(listOf("Nf3", "Bb5"), savedOccurrences.map { it.movePlayed })
    }

    @Test
    fun `parseAndSavePositions indexes first move position for Black user while starting position remains excluded`() {
        whenever(positionRepository.findByHashIn(any())).thenReturn(emptyList())
        whenever(positionRepository.saveAll(any<List<Position>>())).thenAnswer { it.getArgument<List<Position>>(0) }

        val account = ChessAccount(platform = "CHESS_COM", username = "tester")
        val game =
            Game(
                chessAccount = account,
                platformGameId = "black-start-pos-game",
                whiteUsername = "opponent",
                blackUsername = "tester",
                pgn =
                    """
                    [Event "Live Chess"]
                    [White "opponent"]
                    [Black "tester"]

                    1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 1/2-1/2
                    """.trimIndent(),
                timeControl = "600",
                playedAt = Instant.now(),
            )

        val affected = gameParserService.parseAndSavePositions(listOf(game))

        val positionsCaptor = argumentCaptor<List<Position>>()
        verify(positionRepository, times(1)).saveAll(positionsCaptor.capture())
        val savedPositions = positionsCaptor.firstValue

        val occurrencesCaptor = argumentCaptor<List<PositionOccurrence>>()
        verify(positionOccurrenceRepository, times(1)).saveAll(occurrencesCaptor.capture())
        val savedOccurrences = occurrencesCaptor.firstValue

        // Standard start FEN must NOT be in Position table
        assertFalse(savedPositions.any { GameParserService.isStandardStartFen(it.fen) }, "Start FEN must not be persisted")

        // Black's first turn is ply 2 (after 1. e4), which is an authentic opening position and MUST be indexed
        assertEquals(3, savedPositions.size)
        assertEquals(3, affected.size)
        assertEquals(listOf(2, 4, 6), savedOccurrences.map { it.plyNumber })
        assertEquals(listOf("e5", "Nc6", "a6"), savedOccurrences.map { it.movePlayed })
    }
}
