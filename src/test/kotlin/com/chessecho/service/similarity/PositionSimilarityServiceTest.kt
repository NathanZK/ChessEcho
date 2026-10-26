package com.chessecho.service.similarity

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.similarity.PositionSimilarityScorer
import com.chessecho.similarity.RelationalPawnTokenExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID

class PositionSimilarityServiceTest {
    private val occurrenceRepository: PositionOccurrenceRepository = mock()
    private val corpusProvider: PersonalPositionCorpusProvider = mock()
    private val tokenExtractor = RelationalPawnTokenExtractor()
    private val scorer = PositionSimilarityScorer()
    private val urlBuilder = ChessComUrlBuilder()
    private val validator = FenPositionValidator()
    private val cache = PositionSimilarityCache()

    private lateinit var service: PositionSimilarityService

    private val account =
        ChessAccount(
            id = UUID.randomUUID(),
            platform = "CHESS_COM",
            username = "testplayer",
        )

    private val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val candidateFen1 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
    private val candidateFen2 = "8/5pk1/4p1p1/7p/4P3/5PP1/5K1P/8 w - - 0 40"

    @BeforeEach
    fun setUp() {
        service =
            PositionSimilarityService(
                corpusProvider = corpusProvider,
                occurrenceRepository = occurrenceRepository,
                tokenExtractor = tokenExtractor,
                scorer = scorer,
                urlBuilder = urlBuilder,
                validator = validator,
                cache = cache,
            )
    }

    @Test
    fun `findSimilarPositions returns ranked candidates and occurrences for account`() {
        val posId1 = UUID.randomUUID()
        val posId2 = UUID.randomUUID()

        val doc1 = tokenExtractor.extractDocument(posId1, candidateFen1, includeReachability = true)
        val doc2 = tokenExtractor.extractDocument(posId2, candidateFen2, includeReachability = true)

        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc1, doc2))

        val game =
            Game(
                id = UUID.randomUUID(),
                chessAccount = account,
                platformGameId = "https://www.chess.com/game/live/12345",
                pgn = "1. e4",
                timeControl = "600",
                playedAt = Instant.parse("2026-01-10T12:00:00Z"),
                whiteUsername = "testplayer",
                blackUsername = "opponent1",
                result = "win",
            )
        val occurrence =
            PositionOccurrence(
                id = UUID.randomUUID(),
                game = game,
                position = Position(id = posId1, hash = "hash1", fen = candidateFen1),
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "e4",
                playerColor = "WHITE",
            )

        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occurrence))

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                minScore = 0.0,
                limit = 10,
            )

        assertEquals(queryFen, result.queryFen)
        assertFalse(result.results.isEmpty())
        val topMatch = result.results[0]
        assertEquals(posId1, topMatch.positionId)
        assertEquals(1, topMatch.occurrences.size)
        val occDto = topMatch.occurrences[0]
        assertEquals("e4", occDto.playedMove)
        assertEquals(1, occDto.plyNumber)
        assertEquals("https://www.chess.com/game/live/12345?move=1", occDto.chessComUrl)
    }

    @Test
    fun `findSimilarPositions returns empty results without allocating index for zero-game accounts`() {
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(emptyList())

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                minScore = 0.0,
                limit = 10,
            )

        assertEquals(0, result.candidatesEvaluated)
        assertTrue(result.results.isEmpty())
    }
}
