package com.chessecho.service

import com.chessecho.domain.HumanMoveDistribution
import com.chessecho.domain.Position
import com.chessecho.domain.RatingBand
import com.chessecho.dto.HumanMoveBfsRequest
import com.chessecho.repository.HumanMoveDistributionRepository
import com.chessecho.repository.PositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class HumanMoveBfsServiceTest {
    @Mock
    private lateinit var chessComClient: ChessComClient

    @Mock
    private lateinit var positionRepository: PositionRepository

    @Mock
    private lateinit var humanMoveDistributionRepository: HumanMoveDistributionRepository

    private lateinit var service: HumanMoveBfsService

    @BeforeEach
    fun setUp() {
        service =
            HumanMoveBfsService(
                chessComClient,
                positionRepository,
                humanMoveDistributionRepository,
            )
        lenient().whenever(positionRepository.saveAll(any<Iterable<Position>>())).thenAnswer {
            (it.arguments[0] as Iterable<Position>).toList()
        }
    }

    @Test
    fun `test BFS traversal limits`() {
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val gameData =
            mapOf(
                "url" to "http://game1",
                "rules" to "chess",
                "time_class" to "rapid",
                "white" to mapOf("username" to "p1", "rating" to 1100),
                "black" to mapOf("username" to "p2", "rating" to 1150),
                "pgn" to "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(gameData))
        whenever(chessComClient.fetchArchiveUrls("p2")).thenReturn(emptyList())

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = seedPlayers,
                maxDepth = 1,
                maxPlayers = 10,
                minObservations = 1,
            ),
        )

        verify(chessComClient).fetchArchiveUrls("p1")
        verify(chessComClient).fetchArchiveUrls("p2")
        verify(humanMoveDistributionRepository).saveAll(anyList())
    }

    @Test
    fun `test exact 10 seed players accepted`() {
        val seeds = (1..10).map { "player$it" }
        for (seed in seeds) {
            whenever(chessComClient.fetchArchiveUrls(seed)).thenReturn(emptyList())
        }

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = seeds,
            ),
        )

        for (seed in seeds) {
            verify(chessComClient).fetchArchiveUrls(seed)
        }
    }

    @Test
    fun `test duplicate game discovery is skipped`() {
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
        whenever(chessComClient.fetchArchiveUrls("p2")).thenReturn(listOf(archiveUrl))

        val gameData =
            mapOf(
                "url" to "http://game1",
                "rules" to "chess",
                "time_class" to "rapid",
                "white" to mapOf("username" to "p1", "rating" to 1100),
                "black" to mapOf("username" to "p2", "rating" to 1150),
                "pgn" to "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(gameData))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = seedPlayers,
                maxDepth = 1,
                minObservations = 1,
            ),
        )

        val captor = argumentCaptor<List<HumanMoveDistribution>>()
        verify(humanMoveDistributionRepository).saveAll(captor.capture())

        val saved = captor.firstValue
        assertEquals(2, saved.size)
    }

    @Test
    fun `test non-rapid games are skipped`() {
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val gameData =
            mapOf(
                "url" to "http://game1",
                "rules" to "chess",
                "time_class" to "blitz",
                "white" to mapOf("username" to "p1", "rating" to 1100),
                "black" to mapOf("username" to "p2", "rating" to 1150),
                "pgn" to "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(gameData))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = seedPlayers,
                maxDepth = 0,
                minObservations = 1,
            ),
        )

        verify(humanMoveDistributionRepository, never()).saveAll(anyList())
    }

    @Test
    fun `test rating band filtering`() {
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val gameData =
            mapOf(
                "url" to "http://game1",
                "rules" to "chess",
                "time_class" to "rapid",
                "white" to mapOf("username" to "p1", "rating" to 1100),
                "black" to mapOf("username" to "p2", "rating" to 1300),
                "pgn" to "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 2. Nf3 Nc6 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(gameData))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = seedPlayers,
                maxDepth = 0,
                minObservations = 1,
            ),
        )

        val captor = argumentCaptor<List<HumanMoveDistribution>>()
        verify(humanMoveDistributionRepository).saveAll(captor.capture())

        val saved = captor.firstValue
        assertEquals(2, saved.size)
        assertTrue(saved.any { it.movePlayed == "e4" })
        assertTrue(saved.any { it.movePlayed == "Nf3" })
        assertFalse(saved.any { it.movePlayed == "e5" })
    }

    @Test
    fun `test aggregation produces expected counts`() {
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val gameData1 =
            mapOf(
                "url" to "http://game1",
                "rules" to "chess",
                "time_class" to "rapid",
                "white" to mapOf("username" to "p1", "rating" to 1100),
                "black" to mapOf("username" to "p2", "rating" to 1150),
                "pgn" to "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0",
            )
        val gameData2 =
            mapOf(
                "url" to "http://game2",
                "rules" to "chess",
                "time_class" to "rapid",
                "white" to mapOf("username" to "p3", "rating" to 1100),
                "black" to mapOf("username" to "p4", "rating" to 1150),
                "pgn" to "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 c5 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(gameData1, gameData2))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = seedPlayers,
                maxDepth = 0,
                minObservations = 1,
            ),
        )

        val captor = argumentCaptor<List<HumanMoveDistribution>>()
        verify(humanMoveDistributionRepository).saveAll(captor.capture())

        val saved = captor.firstValue
        val e4Dist = saved.find { it.movePlayed == "e4" }
        assertNotNull(e4Dist)
        assertEquals(2, e4Dist!!.observationCount)

        val e5Dist = saved.find { it.movePlayed == "e5" }
        assertNotNull(e5Dist)
        assertEquals(1, e5Dist!!.observationCount)

        val c5Dist = saved.find { it.movePlayed == "c5" }
        assertNotNull(c5Dist)
        assertEquals(1, c5Dist!!.observationCount)
    }

    @Test
    fun `test maxGamesPerPlayer limit`() {
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val games =
            (1..5).map {
                mapOf(
                    "url" to "http://game$it",
                    "rules" to "chess",
                    "time_class" to "rapid",
                    "white" to mapOf("username" to "p1", "rating" to 1100),
                    "black" to mapOf("username" to "p2", "rating" to 1150),
                    "pgn" to "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0",
                )
            }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        val response =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = seedPlayers,
                    maxDepth = 0,
                    minObservations = 1,
                    maxGamesPerPlayer = 3,
                ),
            )

        assertEquals(3, response.gamesInspected)
        assertEquals(3, response.qualifyingGames)
    }

    @Test
    fun `test opponent added even if out of band`() {
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
        whenever(chessComClient.fetchArchiveUrls("p2")).thenReturn(emptyList())

        val gameData =
            mapOf(
                "url" to "http://game1",
                "rules" to "chess",
                "time_class" to "rapid",
                "white" to mapOf("username" to "p1", "rating" to 1100),
                "black" to mapOf("username" to "p2", "rating" to 2000),
                "pgn" to "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(gameData))

        val response =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = seedPlayers,
                    maxDepth = 1,
                ),
            )

        assertEquals(2, response.playersVisited)
    }

    @Test
    fun `test minObservations threshold filters out rare positions`() {
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val gameData1 =
            mapOf(
                "url" to "http://game1",
                "rules" to "chess",
                "time_class" to "rapid",
                "white" to mapOf("username" to "p1", "rating" to 1100),
                "black" to mapOf("username" to "p2", "rating" to 1150),
                "pgn" to "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0",
            )
        val gameData2 =
            mapOf(
                "url" to "http://game2",
                "rules" to "chess",
                "time_class" to "rapid",
                "white" to mapOf("username" to "p3", "rating" to 1100),
                "black" to mapOf("username" to "p4", "rating" to 1150),
                "pgn" to "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 c5 1-0",
            )
        val gameData3 =
            mapOf(
                "url" to "http://game3",
                "rules" to "chess",
                "time_class" to "rapid",
                "white" to mapOf("username" to "p5", "rating" to 1100),
                "black" to mapOf("username" to "p6", "rating" to 1150),
                "pgn" to "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. d4 d5 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(gameData1, gameData2, gameData3))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = seedPlayers,
                maxDepth = 0,
                minObservations = 2,
            ),
        )

        val captor = argumentCaptor<List<HumanMoveDistribution>>()
        verify(humanMoveDistributionRepository).saveAll(captor.capture())

        val saved = captor.firstValue

        // Starting position has 3 obs total (e4:2, d4:1) >= 2, so it IS kept.
        assertTrue(saved.any { it.movePlayed == "e4" })
        assertTrue(saved.any { it.movePlayed == "d4" })

        // Position after e4 has 2 obs total (e5:1, c5:1) >= 2, so it IS kept.
        assertTrue(saved.any { it.movePlayed == "e5" })
        assertTrue(saved.any { it.movePlayed == "c5" })

        // Position after d4 has 1 obs total (d5:1) < 2, so it is FILTERED OUT.
        assertFalse(saved.any { it.movePlayed == "d5" })
    }
}
