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
import java.io.File
import java.util.UUID

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

        val gameData = rapidGame("http://game1", "p1", 1100, "p2", 1150)
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
                rapidGame("http://game$it", "p1", 1100, "p2", 1150)
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

    @Test
    fun `test minObservations threshold filters out rare positions`() {
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

    // ── Batching tests ─────────────────────────────────────────────────────

    @Test
    fun `batching - collection smaller than batchSize flushes once at end`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        // 3 qualifying games, batchSize = 10  => should produce exactly 1 saveAll call
        val games = (1..3).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                minObservations = 1,
                batchSize = 10,
            ),
        )

        verify(humanMoveDistributionRepository, times(1)).saveAll(anyList())
    }

    @Test
    fun `batching - collection exactly equal to batchSize flushes once`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        // 3 qualifying games, batchSize = 3 => flush exactly at the boundary, no trailing partial flush
        val games = (1..3).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                minObservations = 1,
                batchSize = 3,
            ),
        )

        verify(humanMoveDistributionRepository, times(1)).saveAll(anyList())
    }

    @Test
    fun `batching - collection larger than batchSize produces multiple flushes`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        // 5 qualifying games, batchSize = 2 => 2 mid-run flushes + 1 trailing = 3 total saveAll calls
        val games = (1..5).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                minObservations = 1,
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

        // 4 games with batchSize 3 => 1 full flush + 1 partial flush
        val games = (1..4).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                minObservations = 1,
                batchSize = 3,
            ),
        )

        verify(humanMoveDistributionRepository, times(2)).saveAll(anyList())
    }

    @Test
    fun `batching - minObservations applied independently per batch`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        // Games are processed in reversed() order: game3, game2, game1.
        // batchSize=2, minObservations=2.
        //
        // Batch 1 (first 2 qualifying games = game3 + game2):
        //   game3: 1. e4 e5  (white p3 1100, black p4 1150 — both in band)
        //   game2: 1. e4 e5  (white p1 1100, black p2 1150 — both in band)
        //   Starting pos: e4 appears twice -> total 2 >= 2 -> persisted (e4 row)
        //   After-e4 pos: e5 appears twice -> total 2 >= 2 -> persisted (e5 row)
        //   => saveAll called with both e4 and e5
        //
        // Batch 2 (trailing partial = game1):
        //   game1: 1. d4 d5  (white p5 1100, black p6 1150 — both in band)
        //   d4: 1 obs < 2 -> NOT persisted
        //   d5: 1 obs < 2 -> NOT persisted
        //   => saveAll is NOT called for batch 2

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
                minObservations = 2,
                batchSize = 2,
            ),
        )

        val captor = argumentCaptor<List<HumanMoveDistribution>>()
        // Only batch 1 produces rows; batch 2's partial (1 game, d4/d5 each 1 obs < 2) has nothing to save
        verify(humanMoveDistributionRepository, times(1)).saveAll(captor.capture())

        val batch1Saved = captor.firstValue

        // Batch 1: e4 (2 obs at start) and e5 (2 obs at after-e4) both meet threshold
        assertTrue(batch1Saved.any { it.movePlayed == "e4" })
        assertTrue(batch1Saved.any { it.movePlayed == "e5" })

        // d4 and d5 were in the partial batch 2 (only 1 obs each) — never persisted
        assertFalse(batch1Saved.any { it.movePlayed == "d4" })
        assertFalse(batch1Saved.any { it.movePlayed == "d5" })
    }

    @Test
    fun `batching - position occurring in two batches is NOT combined across batches`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        // 4 games all playing 1. d4 d5, batchSize=2, minObservations=3
        // Each batch sees d4 with 2 obs and d5 with 2 obs — both below threshold of 3
        // Cross-batch total would be 4, but batches are intentionally independent
        // => saveAll is never called because no batch meets the threshold
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
        val games = (1..4).map { d4Game("http://game$it") }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                minObservations = 3,
                batchSize = 2,
            ),
        )

        // Neither batch meets minObservations=3, so saveAll is never called at all
        verify(humanMoveDistributionRepository, never()).saveAll(anyList())
    }

    // ── DB-accumulation semantic tests ─────────────────────────────────────
    //
    // These tests make humanMoveDistributionRepository stateful so that
    // persistObservations' findByPositionIdAndRatingBand call returns what was
    // previously written by an earlier batch within the same run.
    // This exercises the existing != null (UPDATE) and existing == null (INSERT)
    // branches of persistObservations directly.

    /**
     * Configures both repositories as stateful in-memory tables so that
     * persistObservations behaves like a real DB across multiple batch calls
     * within a single test:
     *
     * - positionRepository.saveAll stores positions by hash; findByHashIn
     *   returns the previously stored entities so the same positionId is
     *   reused in every batch.
     * - humanMoveDistributionRepository.saveAll stores rows; findByPositionIdAndRatingBand
     *   returns matching rows so that the existing != null (UPDATE) branch in
     *   persistObservations is exercised correctly on subsequent batches.
     *
     * Returns the HumanMoveDistribution backing store for test assertions.
     */
    private fun makeRepositoryStateful(): MutableList<HumanMoveDistribution> {
        val positionStore = mutableListOf<Position>()
        val distStore = mutableListOf<HumanMoveDistribution>()

        // Position repository — stateful
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

        // Distribution repository — stateful
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

        return distStore
    }

    @Test
    fun `db accumulation - same position same move accumulates observationCount across batches`() {
        // Batch 1: position X played e4 twice  (2 games, batchSize=2)
        // Batch 2: position X played e4 twice  (2 more games, batchSize=2)
        // Expected: DB ends up with a single e4 row for position X with observationCount=4
        val store = makeRepositoryStateful()

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        // All 4 games play 1. e4 e5; both players in band.
        // Reversed order: game4, game3, game2, game1.
        // Batch 1 (games 4+3): e4 x2 at start-pos -> saved.
        // Batch 2 (games 2+1): e4 x2 again -> findByPositionIdAndRatingBand returns batch-1 rows -> UPDATE to x4.
        val games = (1..4).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                minObservations = 1,
                batchSize = 2,
            ),
        )

        // Starting position should have a single e4 row with observationCount = 4 (2 per batch × 2 batches)
        val e4Rows = store.filter { it.movePlayed == "e4" }
        assertEquals(1, e4Rows.size, "Should be exactly one e4 row, not duplicates")
        assertEquals(4, e4Rows.first().observationCount, "e4 should accumulate to 4 across both batches")
    }

    @Test
    fun `db accumulation - same position different moves stored as separate rows`() {
        // Batch 1: position X -> e4 × 2 (persisted as a new row)
        // Batch 2: position X -> d4 × 2 (persisted as a NEW row, not an update to e4)
        // Expected DB: two rows — e4=2, d4=2
        val store = makeRepositoryStateful()

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        // Reversed order: game4 (d4), game3 (d4), game2 (e4), game1 (e4)
        // Batch 1 (game4+game3, both d4 games): d4 x2 -> INSERTed
        // Batch 2 (game2+game1, both e4 games): e4 x2 -> INSERTed (different key)
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
                minObservations = 1,
                batchSize = 2,
            ),
        )

        val startingPosRows = store.filter { it.movePlayed == "e4" || it.movePlayed == "d4" }
        assertEquals(2, startingPosRows.size, "Should be exactly two rows: one for e4 and one for d4")
        assertEquals(2, startingPosRows.first { it.movePlayed == "e4" }.observationCount)
        assertEquals(2, startingPosRows.first { it.movePlayed == "d4" }.observationCount)
    }

    @Test
    fun `db accumulation - position crossing minObservations in batch 1 continues accumulating in batch 2`() {
        // Batch 1: e4 × 3 at start-pos (>= minObservations=3) -> row inserted with observationCount=3
        // Batch 2: e4 × 2 at start-pos (only 2 obs in this batch alone, below threshold) ->
        //   BUT: totalObsPerPosition = DB existing (3) + batch new (2) = 5 >= 3 -> UPDATE to 5
        // This validates that once a position crosses the threshold it keeps accumulating.
        val store = makeRepositoryStateful()

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        // 5 identical games, batchSize=3, minObservations=3.
        // Reversed: game5, game4, game3, game2, game1.
        // Batch 1 (games 5+4+3): e4 x3 -> total 3 >= 3 -> INSERT observationCount=3
        // Batch 2 (games 2+1):   e4 x2 -> DB existing=3 -> total 5 >= 3 -> UPDATE to 5
        val games = (1..5).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                minObservations = 3,
                batchSize = 3,
            ),
        )

        val e4Rows = store.filter { it.movePlayed == "e4" }
        assertEquals(1, e4Rows.size, "Should be one e4 row")
        assertEquals(5, e4Rows.first().observationCount, "e4 should accumulate to 5 (3 from batch 1 + 2 from batch 2)")
    }

    @Test
    fun `db accumulation - sub-threshold position is never written regardless of cross-batch totals`() {
        // 4 games, batchSize=2, minObservations=3.
        // Batch 1: e4 x2 — totalObs = 0 (DB) + 2 = 2 < 3 -> NOT written
        // Batch 2: e4 x2 — totalObs = 0 (DB, still empty) + 2 = 2 < 3 -> NOT written
        // Cross-batch total would be 4, but since each batch only sees its own 2 obs + 0 from DB,
        // the position never enters the database.
        val store = makeRepositoryStateful()

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val games = (1..4).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                minObservations = 3,
                batchSize = 2,
            ),
        )

        assertTrue(store.isEmpty(), "No rows should be written: each batch has only 2 obs, below minObservations=3")
        verify(humanMoveDistributionRepository, never()).saveAll(anyList())
    }

    // ── Instrumentation tests (batch-threshold analysis) ─────────────────────

    @Test
    fun `instrumentation - observations before first flush belong to batch 1`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        // 2 qualifying games, batchSize = 2 => should flush exactly once at batch boundary
        val games = (1..2).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        // Clean up any existing instrumentation directory
        val instrumentationDir = File("instrumentation")
        if (instrumentationDir.exists()) {
            instrumentationDir.deleteRecursively()
        }

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                minObservations = 1,
                batchSize = 2,
            ),
        )

        // Verify instrumentation files were created
        assertTrue(instrumentationDir.exists(), "Instrumentation directory should exist")
        val batchPositionsFile = instrumentationDir.listFiles()?.firstOrNull { file -> file.name.startsWith("batch_positions_") }
        assertNotNull(batchPositionsFile, "batch_positions file should exist")

        // All observations should be in batch 1 (only one batch was flushed)
        val batchPositionsLines = batchPositionsFile!!.readLines()
        assertTrue(batchPositionsLines.isNotEmpty(), "batch_positions file should have content")
        for (line in batchPositionsLines) {
            val batch = line.split(",")[0].toInt()
            assertEquals(1, batch, "All observations should be in batch 1")
        }

        // Clean up
        instrumentationDir.deleteRecursively()
    }

    @Test
    fun `instrumentation - observations after first flush belong to batch 2`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        // 4 qualifying games, batchSize = 2 => should flush twice (batch 1 and batch 2)
        val games = (1..4).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        // Clean up any existing instrumentation directory
        val instrumentationDir = File("instrumentation")
        if (instrumentationDir.exists()) {
            instrumentationDir.deleteRecursively()
        }

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                minObservations = 1,
                batchSize = 2,
            ),
        )

        // Verify instrumentation files were created
        assertTrue(instrumentationDir.exists(), "Instrumentation directory should exist")
        val batchPositionsFile = instrumentationDir.listFiles()?.firstOrNull { file -> file.name.startsWith("batch_positions_") }
        assertNotNull(batchPositionsFile, "batch_positions file should exist")

        // Should have observations in both batch 1 and batch 2
        val batchPositionsLines = batchPositionsFile!!.readLines()
        val batches = batchPositionsLines.map { line -> line.split(",")[0].toInt() }.toSet()
        assertTrue(batches.contains(1) == true, "Should have batch 1 observations")
        assertTrue(batches.contains(2) == true, "Should have batch 2 observations")

        // Clean up
        instrumentationDir.deleteRecursively()
    }

    @Test
    fun `instrumentation - final partial batch is represented correctly`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        // 3 qualifying games, batchSize = 2 => batch 1 (2 games) + partial batch 2 (1 game)
        val games = (1..3).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        // Clean up any existing instrumentation directory
        val instrumentationDir = File("instrumentation")
        if (instrumentationDir.exists()) {
            instrumentationDir.deleteRecursively()
        }

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                minObservations = 1,
                batchSize = 2,
            ),
        )

        // Verify instrumentation files were created
        assertTrue(instrumentationDir.exists(), "Instrumentation directory should exist")
        val batchPositionsFile = instrumentationDir.listFiles()?.firstOrNull { it.name.startsWith("batch_positions_") }
        assertNotNull(batchPositionsFile, "batch_positions file should exist")

        // Should have observations in both batch 1 and batch 2 (partial)
        val batchPositionsLines = batchPositionsFile!!.readLines()
        val batches = batchPositionsLines.map { line -> line.split(",")[0].toInt() }.toSet()
        assertTrue(batches.contains(1) == true, "Should have batch 1 observations")
        assertTrue(batches.contains(2) == true, "Should have batch 2 observations (partial)")

        // Clean up
        instrumentationDir.deleteRecursively()
    }

    @Test
    fun `instrumentation - global counts equal sum of batch counts`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        // 4 qualifying games, batchSize = 2 => 2 batches
        val games = (1..4).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        // Clean up any existing instrumentation directory
        val instrumentationDir = File("instrumentation")
        if (instrumentationDir.exists()) {
            instrumentationDir.deleteRecursively()
        }

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                minObservations = 1,
                batchSize = 2,
            ),
        )

        // Verify instrumentation files were created
        val globalPositionsFile = instrumentationDir.listFiles()?.firstOrNull { file -> file.name.startsWith("global_positions_") }
        val batchPositionsFile = instrumentationDir.listFiles()?.firstOrNull { file -> file.name.startsWith("batch_positions_") }
        assertNotNull(globalPositionsFile, "global_positions file should exist")
        assertNotNull(batchPositionsFile, "batch_positions file should exist")

        // Parse global counts
        val globalCounts = mutableMapOf<String, Int>()
        globalPositionsFile!!.readLines().forEach { line: String ->
            val parts = line.split(",")
            globalCounts[parts[0]] = parts[1].toInt()
        }

        // Parse batch counts and sum by position
        val batchCounts = mutableMapOf<String, Int>()
        batchPositionsFile!!.readLines().forEach { line: String ->
            val parts = line.split(",")
            val hash = parts[1]
            val count = parts[2].toInt()
            batchCounts[hash] = (batchCounts[hash] ?: 0) + count
        }

        // Verify totals match
        assertEquals(globalCounts.size, batchCounts.size, "Should have same number of positions")
        for ((hash, globalCount) in globalCounts) {
            val batchCount = batchCounts[hash] ?: 0
            assertEquals(globalCount, batchCount, "Global count for $hash should equal sum of batch counts")
        }

        // Clean up
        instrumentationDir.deleteRecursively()
    }

    @Test
    fun `instrumentation - position totals equal sum of color totals`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        // 2 qualifying games
        val games = (1..2).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        // Clean up any existing instrumentation directory
        val instrumentationDir = File("instrumentation")
        if (instrumentationDir.exists()) {
            instrumentationDir.deleteRecursively()
        }

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                minObservations = 1,
                batchSize = 10,
            ),
        )

        // Verify instrumentation files were created
        val globalPositionsFile = instrumentationDir.listFiles()?.firstOrNull { file -> file.name.startsWith("global_positions_") }
        val globalColorsFile = instrumentationDir.listFiles()?.firstOrNull { file -> file.name.startsWith("global_colors_") }
        assertNotNull(globalPositionsFile, "global_positions file should exist")
        assertNotNull(globalColorsFile, "global_colors file should exist")

        // Parse global position counts
        val globalPositionCounts = mutableMapOf<String, Int>()
        globalPositionsFile!!.readLines().forEach { line: String ->
            val parts = line.split(",")
            globalPositionCounts[parts[0]] = parts[1].toInt()
        }

        // Parse global color counts and aggregate by position
        val positionTotalsFromColors = mutableMapOf<String, Int>()
        globalColorsFile!!.readLines().forEach { line: String ->
            val parts = line.split(",")
            val hash = parts[0]
            val count = parts[3].toInt()
            positionTotalsFromColors[hash] = (positionTotalsFromColors[hash] ?: 0) + count
        }

        // Verify totals match
        for ((hash, globalCount) in globalPositionCounts) {
            val colorSum = positionTotalsFromColors[hash] ?: 0
            assertEquals(globalCount, colorSum, "Position $hash: global count should equal sum of color counts")
        }

        // Clean up
        instrumentationDir.deleteRecursively()
    }
}
