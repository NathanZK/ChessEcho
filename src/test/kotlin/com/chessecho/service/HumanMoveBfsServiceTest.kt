package com.chessecho.service

import com.chessecho.domain.HumanMoveDistribution
import com.chessecho.domain.Position
import com.chessecho.domain.RatingBand
import com.chessecho.dto.HumanMoveBfsRequest
import com.chessecho.repository.HumanMoveBfsClaimConflictException
import com.chessecho.repository.HumanMoveBfsSeenGameClaimer
import com.chessecho.repository.HumanMoveBfsSeenGameRepository
import com.chessecho.repository.HumanMoveDistributionRepository
import com.chessecho.repository.PositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class HumanMoveBfsServiceTest {
    @Mock
    private lateinit var chessComClient: ChessComClient

    @Mock
    private lateinit var positionRepository: PositionRepository

    @Mock
    private lateinit var humanMoveDistributionRepository: HumanMoveDistributionRepository

    @Mock
    private lateinit var humanMoveBfsSeenGameRepository: HumanMoveBfsSeenGameRepository

    @Mock
    private lateinit var humanMoveBfsSeenGameClaimer: HumanMoveBfsSeenGameClaimer

    private lateinit var service: HumanMoveBfsService

    @BeforeEach
    fun setUp() {
        service =
            HumanMoveBfsService(
                chessComClient,
                positionRepository,
                humanMoveDistributionRepository,
                humanMoveBfsSeenGameRepository,
                humanMoveBfsSeenGameClaimer,
            )
        lenient().whenever(positionRepository.saveAll(any<Iterable<Position>>())).thenAnswer {
            (it.arguments[0] as Iterable<Position>).toList()
        }
        // Default: no URLs are pre-claimed, and the atomic claim step succeeds
        // silently. Individual tests override this via makeRepositoryStateful()
        // when they need cross-batch or cross-invocation memory, or via a
        // per-test doThrow when they need to simulate a claim conflict.
        lenient().whenever(humanMoveBfsSeenGameRepository.findExistingGameUrls(any())).thenReturn(emptyList())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun rapidGame(
        url: String,
        whiteUser: String,
        whiteRating: Int,
        blackUser: String,
        blackRating: Int,
        pgn: String = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0",
    ): Map<String, Any> =
        mapOf(
            "url" to url,
            "rules" to "chess",
            "time_class" to "rapid",
            "white" to mapOf("username" to whiteUser, "rating" to whiteRating),
            "black" to mapOf("username" to blackUser, "rating" to blackRating),
            "pgn" to pgn,
        )

    private fun blitzGame(
        url: String,
        whiteUser: String,
        whiteRating: Int,
        blackUser: String,
        blackRating: Int,
    ): Map<String, Any> =
        mapOf(
            "url" to url,
            "rules" to "chess",
            "time_class" to "blitz",
            "white" to mapOf("username" to whiteUser, "rating" to whiteRating),
            "black" to mapOf("username" to blackUser, "rating" to blackRating),
        )

    // ── Existing behavioural tests ─────────────────────────────────────────

    @Test
    fun `test BFS traversal limits`() {
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val gameData = rapidGame("http://game1", "p1", 1100, "p2", 1150)
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(gameData))
        whenever(chessComClient.fetchArchiveUrls("p2")).thenReturn(emptyList())

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = seedPlayers,
                maxDepth = 1,
                maxPlayers = 10,
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
        // p1 traversed as White; opponent p2 (Black, 1150) is in-band.
        // Longer PGN so opponent contributes 2 moves (e5, Nc6), giving us 2 saved rows to assert on.
        // p2 is also visited at depth 1 but the game URL is already in seenGameUrls, so it is
        // skipped — the observation count must not double.
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
        whenever(chessComClient.fetchArchiveUrls("p2")).thenReturn(listOf(archiveUrl))

        val gameData =
            rapidGame(
                url = "http://game1",
                whiteUser = "p1",
                whiteRating = 1100,
                blackUser = "p2",
                blackRating = 1150,
                pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 2. Nf3 Nc6 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(gameData))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = seedPlayers,
                maxDepth = 1,
            ),
        )

        val captor = argumentCaptor<List<HumanMoveDistribution>>()
        verify(humanMoveDistributionRepository).saveAll(captor.capture())

        // Only the opponent's (Black p2) moves are recorded: e5 and Nc6.
        val saved = captor.firstValue
        assertEquals(2, saved.size)
        assertTrue(saved.any { it.movePlayed == "e5" })
        assertTrue(saved.any { it.movePlayed == "Nc6" })
        assertFalse(saved.any { it.movePlayed == "e4" }, "traversed player's e4 must not be recorded")
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
            ),
        )

        verify(humanMoveDistributionRepository, never()).saveAll(anyList())
    }

    @Test
    fun `test rating band filtering - opponent out of band records no moves`() {
        // p1 traversed as White (1100, in band); opponent p2 is Black (1300, OUT of band).
        // Under the new semantics only the opponent's moves qualify the game, so p2 being
        // out-of-band means ZERO moves are saved.
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val gameData =
            rapidGame(
                url = "http://game1",
                whiteUser = "p1",
                whiteRating = 1100,
                blackUser = "p2",
                blackRating = 1300,
                pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 2. Nf3 Nc6 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(gameData))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = seedPlayers,
                maxDepth = 0,
            ),
        )

        verify(humanMoveDistributionRepository, never()).saveAll(anyList())
    }

    @Test
    fun `test rating band filtering - opponent in band records only opponent moves`() {
        // p1 traversed as White (1300, OUT of band); opponent p2 is Black (1150, in band).
        // The game qualifies because the opponent is in-band, and only Black's moves are saved.
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val gameData =
            rapidGame(
                url = "http://game1",
                whiteUser = "p1",
                whiteRating = 1300,
                blackUser = "p2",
                blackRating = 1150,
                pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 2. Nf3 Nc6 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(gameData))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = seedPlayers,
                maxDepth = 0,
            ),
        )

        val captor = argumentCaptor<List<HumanMoveDistribution>>()
        verify(humanMoveDistributionRepository).saveAll(captor.capture())

        val saved = captor.firstValue
        // Only opponent Black's moves (e5, Nc6); traversed White's moves (e4, Nf3) must not appear.
        assertTrue(saved.any { it.movePlayed == "e5" })
        assertTrue(saved.any { it.movePlayed == "Nc6" })
        assertFalse(saved.any { it.movePlayed == "e4" }, "traversed player's e4 must not be recorded")
        assertFalse(saved.any { it.movePlayed == "Nf3" }, "traversed player's Nf3 must not be recorded")
    }

    @Test
    fun `test aggregation produces expected counts`() {
        // Two games where p1 plays White against different Black opponents, both in-band.
        // Both opponents play 1...e5, so e5 accumulates to 2. Traversed-player's e4 is never saved.
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val gameData1 = rapidGame("http://game1", "p1", 1100, "p2", 1150)
        val gameData2 = rapidGame("http://game2", "p1", 1100, "p3", 1150)
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(gameData1, gameData2))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = seedPlayers,
                maxDepth = 0,
            ),
        )

        val captor = argumentCaptor<List<HumanMoveDistribution>>()
        verify(humanMoveDistributionRepository).saveAll(captor.capture())

        val saved = captor.firstValue
        val e5Dist = saved.find { it.movePlayed == "e5" }
        assertNotNull(e5Dist)
        assertEquals(2, e5Dist!!.observationCount, "e5 (opponent Black's response) must accumulate across 2 games")

        assertFalse(saved.any { it.movePlayed == "e4" }, "traversed player's (White) e4 must not be recorded")
    }

    @Test
    fun `test maxGamesPerPlayer limit`() {
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val games =
            (1..5).map {
                rapidGame("http://game$it", "p1", 1100, "p2", 1150)
            }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        val response =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = seedPlayers,
                    maxDepth = 0,
                    maxGamesPerPlayer = 3,
                ),
            )

        assertEquals(3, response.gamesInspected)
        assertEquals(3, response.qualifyingGames)
    }

    @Test
    fun `maxGamesPerPlayer budget is not consumed by non-rapid games`() {
        // Archive (oldest-first): [rapid1, rapid2, rapid3, blitz1..blitz10]
        // After games.reversed(): [blitz10..blitz1, rapid3, rapid2, rapid1]
        // With maxGamesPerPlayer=3, the OLD code broke after the first 3 blitz games
        // and found 0 rapid. The NEW code skips blitz games without consuming the
        // rapid budget, so all 3 rapid games are eventually found.
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val games =
            (1..3).map { rapidGame("http://rapid$it", "p1", 1100, "p2", 1150) } +
                (1..10).map { blitzGame("http://blitz$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        val response =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("p1"),
                    maxDepth = 0,
                    maxGamesPerPlayer = 3,
                ),
            )

        assertEquals(3, response.rapidGames, "all 3 rapid games must be found despite the 10 intervening blitz games")
        assertEquals(3, response.qualifyingGames)
    }

    @Test
    fun `BFS fetches older archives when newer archive has fewer rapid games than the budget`() {
        // Two archives: newer has 3 rapid + 5 blitz; older has 2 rapid.
        // maxGamesPerPlayer=4 should exhaust the newer archive (3 rapid) and
        // then fetch the older archive to reach game 4.
        val newerArchive = "https://api.chess.com/pub/player/p1/games/2021/02"
        val olderArchive = "https://api.chess.com/pub/player/p1/games/2021/01"
        // fetchArchiveUrls returns archives oldest-first; BFS reverses them
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(olderArchive, newerArchive))

        // Newer archive (oldest-first): [blitz1, rapid1, blitz2, rapid2, blitz3, rapid3, blitz4, blitz5]
        // After reversed(): [blitz5, blitz4, rapid3, blitz3, rapid2, blitz2, rapid1, blitz1]
        val newerGames =
            listOf(
                blitzGame("http://n-blitz1", "p1", 1100, "p2", 1150),
                rapidGame("http://n-rapid1", "p1", 1100, "p2", 1150),
                blitzGame("http://n-blitz2", "p1", 1100, "p2", 1150),
                rapidGame("http://n-rapid2", "p1", 1100, "p2", 1150),
                blitzGame("http://n-blitz3", "p1", 1100, "p2", 1150),
                rapidGame("http://n-rapid3", "p1", 1100, "p2", 1150),
                blitzGame("http://n-blitz4", "p1", 1100, "p2", 1150),
                blitzGame("http://n-blitz5", "p1", 1100, "p2", 1150),
            )
        val olderGames =
            listOf(
                rapidGame("http://o-rapid1", "p1", 1100, "p2", 1150),
                rapidGame("http://o-rapid2", "p1", 1100, "p2", 1150),
            )
        whenever(chessComClient.fetchMonthlyGames(newerArchive)).thenReturn(newerGames)
        whenever(chessComClient.fetchMonthlyGames(olderArchive)).thenReturn(olderGames)

        val response =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("p1"),
                    maxDepth = 0,
                    maxGamesPerPlayer = 4,
                ),
            )

        assertEquals(4, response.rapidGames, "budget of 4 rapid games must span both archives")
        assertEquals(4, response.qualifyingGames)
        verify(chessComClient).fetchMonthlyGames(newerArchive)
        verify(chessComClient).fetchMonthlyGames(olderArchive)
    }

    @Test
    fun `test opponent added even if out of band`() {
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
        whenever(chessComClient.fetchArchiveUrls("p2")).thenReturn(emptyList())

        val gameData = rapidGame("http://game1", "p1", 1100, "p2", 2000)
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

    // ── Global accumulator semantics (no per-batch threshold) ──────────────

    @Test
    fun `all observations are persisted with no batch-local threshold`() {
        // Previously this test asserted that positions below a batch-local
        // minObservations threshold were filtered out. Under the accumulator
        // model every observed (position, move) pair must be persisted
        // regardless of count — thresholding is deferred to finalization.
        val seedPlayers = listOf("p1")
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val gameData1 = rapidGame("http://game1", "p1", 1100, "p2", 1150)
        val gameData2 =
            rapidGame(
                url = "http://game2",
                whiteUser = "p3",
                whiteRating = 1100,
                blackUser = "p4",
                blackRating = 1150,
                pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 c5 1-0",
            )
        val gameData3 =
            rapidGame(
                url = "http://game3",
                whiteUser = "p5",
                whiteRating = 1100,
                blackUser = "p6",
                blackRating = 1150,
                pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. d4 d5 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(gameData1, gameData2, gameData3))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = seedPlayers,
                maxDepth = 0,
            ),
        )

        val captor = argumentCaptor<List<HumanMoveDistribution>>()
        verify(humanMoveDistributionRepository).saveAll(captor.capture())

        val saved = captor.firstValue
        // game1: p1=White, opponent p2 (Black, 1150) in band → e5
        // game2: p1 not White (p3 is), opponent p3 (White, 1100) in band → e4
        // game3: p1 not White (p5 is), opponent p5 (White, 1100) in band → d4
        assertTrue(saved.any { it.movePlayed == "e5" })
        assertTrue(saved.any { it.movePlayed == "e4" })
        assertTrue(
            saved.any { it.movePlayed == "d4" },
            "single-observation moves must survive the gathering phase",
        )
        assertFalse(saved.any { it.movePlayed == "c5" }, "Black p4's c5 is not the opponent of the traversed p1")
        assertFalse(saved.any { it.movePlayed == "d5" }, "Black p6's d5 is not the opponent of the traversed p1")
    }

    // ── Batching tests ─────────────────────────────────────────────────────

    @Test
    fun `batching - collection smaller than batchSize flushes once at end`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val games = (1..3).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                batchSize = 10,
            ),
        )

        verify(humanMoveDistributionRepository, times(1)).saveAll(anyList())
    }

    @Test
    fun `batching - collection exactly equal to batchSize flushes once`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val games = (1..3).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                batchSize = 3,
            ),
        )

        verify(humanMoveDistributionRepository, times(1)).saveAll(anyList())
    }

    @Test
    fun `batching - collection larger than batchSize produces multiple flushes`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val games = (1..5).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                batchSize = 2,
            ),
        )

        // 5 games / batchSize 2 => batches of 2, 2, 1 => 3 flushes
        verify(humanMoveDistributionRepository, times(3)).saveAll(anyList())
    }

    @Test
    fun `batching - final partial batch is persisted`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val games = (1..4).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                batchSize = 3,
            ),
        )

        verify(humanMoveDistributionRepository, times(2)).saveAll(anyList())
    }

    @Test
    fun `sub-threshold observations survive every batch and reach the DB`() {
        // Previously (with a batch-local threshold) a small trailing batch that could
        // not meet minObservations wrote nothing. Under the accumulator model every
        // batch must persist its observations so later batches or later runs can
        // add to them.
        val store = makeRepositoryStateful().distributions

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val game1 =
            rapidGame(
                url = "http://game1",
                whiteUser = "p5",
                whiteRating = 1100,
                blackUser = "p6",
                blackRating = 1150,
                pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. d4 d5 1-0",
            )
        val game2 = rapidGame("http://game2", "p1", 1100, "p2", 1150)
        val game3 = rapidGame("http://game3", "p3", 1100, "p4", 1150)
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(game1, game2, game3))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                batchSize = 2,
            ),
        )

        // Both batches must flush — no observation is dropped for being "small".
        verify(humanMoveDistributionRepository, times(2)).saveAll(anyList())
        // game1 (p5 vs p6): p1 not White → opponent=p5(White,1100) in band → d4
        // game2 (p1 vs p2): p1=White → opponent=p2(Black,1150) in band → e5
        // game3 (p3 vs p4): p1 not White → opponent=p3(White,1100) in band → e4
        assertTrue(store.any { it.movePlayed == "e4" })
        assertTrue(store.any { it.movePlayed == "e5" })
        assertTrue(store.any { it.movePlayed == "d4" }, "d4 was in a singleton trailing batch and must persist")
        assertFalse(store.any { it.movePlayed == "d5" }, "d5 (Black p6's move) is not the opponent of p1 and must not appear")
    }

    @Test
    fun `position occurring in multiple batches accumulates globally`() {
        // Under the old batch-local threshold, a position with 2+2 observations across
        // two batches was discarded because no single batch reached the threshold. Now
        // observations must accumulate across batches: 2 + 1 + 2 = 5.
        val store = makeRepositoryStateful().distributions

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        // 5 identical 1. d4 d5 games, batchSize=2 => batches of 2, 2, 1 across a single run.
        val d4Game: (String) -> Map<String, Any> = { url ->
            rapidGame(
                url = url,
                whiteUser = "p1",
                whiteRating = 1100,
                blackUser = "p2",
                blackRating = 1150,
                pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. d4 d5 1-0",
            )
        }
        val games = (1..5).map { d4Game("http://game$it") }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                batchSize = 2,
            ),
        )

        val d5Rows = store.filter { it.movePlayed == "d5" }
        assertEquals(1, d5Rows.size, "d5 (opponent Black's response) must be represented by a single accumulated row")
        assertEquals(5, d5Rows.first().observationCount, "5 observations across 3 batches must accumulate to 5")
    }

    // ── DB-accumulation semantic tests ─────────────────────────────────────

    /**
     * Backing stores exposed by [makeRepositoryStateful] for assertion access.
     */
    private data class StatefulStores(
        val distributions: MutableList<HumanMoveDistribution>,
        val seenGameUrls: MutableSet<String>,
    )

    /**
     * Configures every repository as a stateful in-memory table so
     * persistObservations, the per-archive pre-check, and the atomic URL claim
     * behave like a real DB across multiple batch calls or multiple runBfs
     * invocations within a single test.
     *
     * The seen-game store uses primary-key semantics: [claimGameUrls] inserts
     * each URL exactly once and throws [HumanMoveBfsClaimConflictException] on
     * any collision, mirroring a plain `INSERT` protected by the `game_url`
     * unique constraint on `human_move_bfs_seen_game`.
     */
    private fun makeRepositoryStateful(): StatefulStores {
        val positionStore = mutableListOf<Position>()
        val distStore = mutableListOf<HumanMoveDistribution>()
        val seenGameStore = mutableSetOf<String>()

        lenient().whenever(positionRepository.saveAll(any<Iterable<Position>>())).thenAnswer { inv ->
            val incoming = (inv.arguments[0] as Iterable<Position>).toList()
            for (pos in incoming) {
                if (positionStore.none { it.hash == pos.hash }) {
                    positionStore.add(pos)
                }
            }
            incoming
        }
        lenient().whenever(positionRepository.findByHashIn(any())).thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            val hashes = inv.arguments[0] as List<String>
            positionStore.filter { it.hash in hashes }
        }

        lenient().whenever(humanMoveDistributionRepository.saveAll(any<Iterable<HumanMoveDistribution>>())).thenAnswer { inv ->
            val incoming = (inv.arguments[0] as Iterable<HumanMoveDistribution>).toList()
            for (row in incoming) {
                val existing = distStore.find { it.id == row.id }
                if (existing != null) {
                    distStore.remove(existing)
                }
                distStore.add(row)
            }
            incoming
        }
        lenient().whenever(
            humanMoveDistributionRepository.findByPositionIdAndRatingBand(any<UUID>(), anyString()),
        ).thenAnswer { inv ->
            val posId = inv.arguments[0] as UUID
            val band = inv.arguments[1] as String
            distStore.filter { it.positionId == posId && it.ratingBand == band }
        }

        // Stateful seen-game repository + claimer: PK-on-game_url semantics.
        lenient().whenever(humanMoveBfsSeenGameRepository.findExistingGameUrls(any())).thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            val urls = inv.arguments[0] as Collection<String>
            urls.filter { it in seenGameStore }
        }
        lenient().whenever(humanMoveBfsSeenGameClaimer.claimGameUrls(any<Collection<String>>())).thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            val urls = inv.arguments[0] as Collection<String>
            for (url in urls) {
                if (!seenGameStore.add(url)) {
                    // Mirror the DB unique-constraint violation: any collision
                    // aborts the batch with HumanMoveBfsClaimConflictException,
                    // which propagates out of persistObservations and would
                    // roll back the enclosing @Transactional in production.
                    throw HumanMoveBfsClaimConflictException(
                        attempted = urls.size,
                        cause = IllegalStateException("URL already claimed: $url"),
                    )
                }
            }
            null // Unit
        }

        return StatefulStores(distStore, seenGameStore)
    }

    @Test
    fun `db accumulation - same position same move accumulates observationCount across batches`() {
        val store = makeRepositoryStateful().distributions

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val games = (1..4).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                batchSize = 2,
            ),
        )

        val e5Rows = store.filter { it.movePlayed == "e5" }
        assertEquals(1, e5Rows.size, "Should be exactly one e5 row, not duplicates")
        assertEquals(4, e5Rows.first().observationCount, "e5 (opponent Black) should accumulate to 4 across both batches")
    }

    @Test
    fun `db accumulation - same position different moves stored as separate rows`() {
        val store = makeRepositoryStateful().distributions

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val e4Game: (String) -> Map<String, Any> = { url -> rapidGame(url, "p1", 1100, "p2", 1150) }
        val d4Game: (String) -> Map<String, Any> = { url ->
            rapidGame(
                url = url,
                whiteUser = "p1",
                whiteRating = 1100,
                blackUser = "p2",
                blackRating = 1150,
                pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. d4 d5 1-0",
            )
        }

        val games =
            listOf(
                e4Game("http://game1"),
                e4Game("http://game2"),
                d4Game("http://game3"),
                d4Game("http://game4"),
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                batchSize = 2,
            ),
        )

        val startingPosRows = store.filter { it.movePlayed == "e5" || it.movePlayed == "d5" }
        assertEquals(2, startingPosRows.size, "Should be exactly two rows: one for e5 and one for d5")
        assertEquals(2, startingPosRows.first { it.movePlayed == "e5" }.observationCount)
        assertEquals(2, startingPosRows.first { it.movePlayed == "d5" }.observationCount)
    }

    @Test
    fun `db accumulation - observations accumulate across separate BFS invocations`() {
        // The canonical "day 1 batch 1 + batch 2, day 2 batch 3 -> total 5" example:
        // three independent runBfs invocations against three different archives, with
        // 2 + 1 + 2 identical games contributing to the same (position, band, move) row.
        val store = makeRepositoryStateful().distributions

        val archive1 = "https://api.chess.com/pub/player/p1/games/2021/01"
        val archive2 = "https://api.chess.com/pub/player/p1/games/2021/02"
        val archive3 = "https://api.chess.com/pub/player/p1/games/2021/03"

        whenever(chessComClient.fetchMonthlyGames(archive1)).thenReturn(
            (1..2).map { rapidGame("http://run1-game$it", "p1", 1100, "p2", 1150) },
        )
        whenever(chessComClient.fetchMonthlyGames(archive2)).thenReturn(
            listOf(rapidGame("http://run2-game1", "p1", 1100, "p2", 1150)),
        )
        whenever(chessComClient.fetchMonthlyGames(archive3)).thenReturn(
            (1..2).map { rapidGame("http://run3-game$it", "p1", 1100, "p2", 1150) },
        )

        fun runAgainst(archiveUrl: String) {
            whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("p1"),
                    maxDepth = 0,
                    batchSize = 5000,
                ),
            )
        }

        runAgainst(archive1) // 2 obs of e5 (opponent Black)
        runAgainst(archive2) // + 1 obs of e5
        runAgainst(archive3) // + 2 obs of e5  => 5 total

        val e5Rows = store.filter { it.movePlayed == "e5" }
        assertEquals(1, e5Rows.size, "e5 must be represented by exactly one accumulated row")
        assertEquals(
            5,
            e5Rows.first().observationCount,
            "2 + 1 + 2 must accumulate to 5 across three separate BFS invocations",
        )
    }

    // ── Persistent game-URL deduplication ─────────────────────────────────

    @Test
    fun `dedup - every processed game URL is recorded in the seen-game store`() {
        val stores = makeRepositoryStateful()

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
        val games = (1..3).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                batchSize = 10,
            ),
        )

        assertEquals(
            setOf("http://game1", "http://game2", "http://game3"),
            stores.seenGameUrls,
            "every processed URL must be persistently claimed",
        )
    }

    @Test
    fun `dedup - two BFS invocations over the same archive do not double-count`() {
        // Under the new persistent dedup, the second runBfs must skip every game
        // the first runBfs already claimed, so observation counts equal the
        // single-run baseline (not 2x).
        val stores = makeRepositoryStateful()

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
        val games = (1..3).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        val request =
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                batchSize = 10,
            )

        service.runBfs(request)
        service.runBfs(request)

        val e5Rows = stores.distributions.filter { it.movePlayed == "e5" }
        assertEquals(1, e5Rows.size)
        assertEquals(
            3,
            e5Rows.first().observationCount,
            "3 games contribute once each; second run must not double-count",
        )
        assertEquals(3, stores.seenGameUrls.size, "seen-game store must contain each URL exactly once")
    }

    @Test
    fun `dedup - overlapping archives across runs only count new games`() {
        // Run 1 processes game1 + game2. Run 2 sees game2 (already claimed) and game3 (new).
        // Result: game1 contributes once, game2 contributes once, game3 contributes once.
        val stores = makeRepositoryStateful()

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val g1 = rapidGame("http://game1", "p1", 1100, "p2", 1150)
        val g2 = rapidGame("http://game2", "p1", 1100, "p2", 1150)
        val g3 = rapidGame("http://game3", "p1", 1100, "p2", 1150)

        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(g1, g2))
        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                batchSize = 10,
            ),
        )

        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(g2, g3))
        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                batchSize = 10,
            ),
        )

        val e5Rows = stores.distributions.filter { it.movePlayed == "e5" }
        assertEquals(1, e5Rows.size)
        assertEquals(
            3,
            e5Rows.first().observationCount,
            "game2 was already claimed by run 1; only game1, game2, game3 should each contribute once",
        )
        assertEquals(
            setOf("http://game1", "http://game2", "http://game3"),
            stores.seenGameUrls,
        )
    }

    @Test
    fun `dedup - within-run duplicate discovery via a second player is not double-counted`() {
        // Same archive URL returned for both p1 and p2 — the classic within-run duplicate
        // discovery path already covered by the in-memory seenGameUrls set. Verify that
        // adding persistent dedup does not regress this: the game contributes exactly once.
        val stores = makeRepositoryStateful()

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
        whenever(chessComClient.fetchArchiveUrls("p2")).thenReturn(listOf(archiveUrl))
        val gameData = rapidGame("http://game1", "p1", 1100, "p2", 1150)
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(gameData))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 1,
                batchSize = 10,
            ),
        )

        val e5Rows = stores.distributions.filter { it.movePlayed == "e5" }
        assertEquals(1, e5Rows.size)
        assertEquals(1, e5Rows.first().observationCount)
        assertEquals(setOf("http://game1"), stores.seenGameUrls)
    }

    @Test
    fun `dedup - claim conflict aborts the batch and no observations are written`() {
        // Simulate a race: the stateful store is normal, but the claim mock reports fewer
        // rows inserted than requested. persistObservations must throw and rollback —
        // no distribution rows may have been persisted for this batch.
        val stores = makeRepositoryStateful()

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
        val games = (1..2).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        // Override: claim throws HumanMoveBfsClaimConflictException to simulate
        // a DB PK collision (e.g., a race under a hypothetical multi-writer scenario).
        org.mockito.Mockito.doThrow(
            HumanMoveBfsClaimConflictException(
                attempted = 2,
                cause = IllegalStateException("simulated collision"),
            ),
        ).whenever(humanMoveBfsSeenGameClaimer).claimGameUrls(any<Collection<String>>())

        assertThrows(HumanMoveBfsClaimConflictException::class.java) {
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("p1"),
                    maxDepth = 0,
                    batchSize = 10,
                ),
            )
        }

        // Note: this unit test cannot verify SQL-level rollback (no real TX manager),
        // but it can and does verify that persistObservations aborts BEFORE any
        // distribution rows are handed to saveAll. The transactional rollback of
        // the claim itself is covered by the @DataJpaTest integration test.
        assertTrue(
            stores.distributions.isEmpty(),
            "distribution store must remain empty when the batch aborts on claim conflict",
        )
    }

    // ── Attribution semantics tests ────────────────────────────────────────

    @Test
    fun `attribution - traversed player out of band opponent in band records opponent moves`() {
        // p1 (White, 1300 — out of band) vs p2 (Black, 1150 — in band).
        // Game qualifies because the OPPONENT (p2) is in-band; only p2's (Black's) moves are recorded.
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val game =
            rapidGame(
                url = "http://game1",
                whiteUser = "p1",
                whiteRating = 1300,
                blackUser = "p2",
                blackRating = 1150,
                pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(game))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
            ),
        )

        val captor = argumentCaptor<List<HumanMoveDistribution>>()
        verify(humanMoveDistributionRepository).saveAll(captor.capture())
        val saved = captor.firstValue

        assertTrue(saved.any { it.movePlayed == "e5" }, "opponent Black's e5 must be recorded")
        assertFalse(saved.any { it.movePlayed == "e4" }, "traversed White's e4 must not be recorded")
    }

    @Test
    fun `attribution - traversed player in band opponent out of band records no moves`() {
        // p1 (White, 1100 — in band) vs p2 (Black, 1300 — out of band).
        // Opponent is out of band → game does not qualify → nothing saved.
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val game =
            rapidGame(
                url = "http://game1",
                whiteUser = "p1",
                whiteRating = 1100,
                blackUser = "p2",
                blackRating = 1300,
                pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(game))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
            ),
        )

        verify(humanMoveDistributionRepository, never()).saveAll(anyList())
    }

    @Test
    fun `attribution - both players in band records only opponent moves`() {
        // p1 (White, 1100 — in band) vs p2 (Black, 1150 — in band).
        // Both are in-band, but only the OPPONENT's (Black's) moves are saved.
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val game =
            rapidGame(
                url = "http://game1",
                whiteUser = "p1",
                whiteRating = 1100,
                blackUser = "p2",
                blackRating = 1150,
                pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 2. Nf3 Nc6 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(game))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
            ),
        )

        val captor = argumentCaptor<List<HumanMoveDistribution>>()
        verify(humanMoveDistributionRepository).saveAll(captor.capture())
        val saved = captor.firstValue

        assertTrue(saved.any { it.movePlayed == "e5" }, "opponent Black's e5 must be recorded")
        assertTrue(saved.any { it.movePlayed == "Nc6" }, "opponent Black's Nc6 must be recorded")
        assertFalse(saved.any { it.movePlayed == "e4" }, "traversed White's e4 must not be recorded")
        assertFalse(saved.any { it.movePlayed == "Nf3" }, "traversed White's Nf3 must not be recorded")
    }

    @Test
    fun `attribution - both players out of band records no moves`() {
        // p1 (White, 800) vs p2 (Black, 850) — both below the 1000-1200 band.
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val game =
            rapidGame(
                url = "http://game1",
                whiteUser = "p1",
                whiteRating = 800,
                blackUser = "p2",
                blackRating = 850,
                pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(game))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
            ),
        )

        verify(humanMoveDistributionRepository, never()).saveAll(anyList())
    }

    @Test
    fun `attribution - traversed player as Black opponent White in band records White moves`() {
        // p1 plays Black (1150 — in band) vs p2 White (1100 — in band).
        // Traversed player is Black; opponent is White → only White's (p2's) moves are saved.
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val game =
            rapidGame(
                url = "http://game1",
                whiteUser = "p2",
                whiteRating = 1100,
                blackUser = "p1",
                blackRating = 1150,
                pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. d4 d5 2. c4 c6 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(game))

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
            ),
        )

        val captor = argumentCaptor<List<HumanMoveDistribution>>()
        verify(humanMoveDistributionRepository).saveAll(captor.capture())
        val saved = captor.firstValue

        assertTrue(saved.any { it.movePlayed == "d4" }, "opponent White's d4 must be recorded")
        assertTrue(saved.any { it.movePlayed == "c4" }, "opponent White's c4 must be recorded")
        assertFalse(saved.any { it.movePlayed == "d5" }, "traversed Black's d5 must not be recorded")
        assertFalse(saved.any { it.movePlayed == "c6" }, "traversed Black's c6 must not be recorded")
    }

    @Test
    fun `dedup - claim step happens strictly before distribution writes`() {
        // Verify order: claimGameUrls must be invoked at least once before any saveAll of
        // HumanMoveDistribution rows, so a claim failure cannot leave observations behind.
        val stores = makeRepositoryStateful()

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
        val games = (1..2).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                batchSize = 10,
            ),
        )

        val order = org.mockito.Mockito.inOrder(humanMoveBfsSeenGameClaimer, humanMoveDistributionRepository)
        order.verify(humanMoveBfsSeenGameClaimer).claimGameUrls(any<Collection<String>>())
        order.verify(humanMoveDistributionRepository).saveAll(anyList())
        assertTrue(stores.distributions.isNotEmpty(), "successful batch must persist distribution rows")
        assertEquals(2, stores.seenGameUrls.size)
    }
}
