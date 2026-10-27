package com.chessecho.service

import com.chessecho.domain.HumanMoveDistribution
import com.chessecho.domain.RatingBand
import com.chessecho.dto.HumanMoveBfsRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory

class HumanMoveBfsCandidatePersistenceTest : HumanMoveBfsBaseTest() {
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
    fun `variant chess games are filtered out and do not increment rapidGames or submit to sink`() {
        val traversal = HumanMoveBfsTraversal(chessComClient, LoggerFactory.getLogger(HumanMoveBfsTraversal::class.java))
        val sink = mock<HumanMoveBfsSink>()
        whenever(sink.alreadyContributed(any())).thenReturn(emptySet())
        whenever(sink.accept(any())).thenReturn(true)

        val bounds =
            HumanMoveBfsBounds(
                targetBand = RatingBand.BAND_1000_1200,
                seedPlayers = listOf("p1"),
                excludedPlayers = emptyList(),
                maxQualifyingGames = null,
                maxGamesPerPlayer = 10,
                maxPlayers = null,
                maxDepth = 0,
            )

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2024/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val standardGame = rapidGame("http://standard", "p1", 1100, "p2", 1150)
        val chess960Game =
            mapOf(
                "url" to "http://chess960",
                "rules" to "chess960",
                "time_class" to "rapid",
                "white" to mapOf("username" to "p1", "rating" to 1100),
                "black" to mapOf("username" to "p2", "rating" to 1150),
                "pgn" to "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0",
            )
        val nullRulesGame =
            mapOf(
                "url" to "http://nullrules",
                "time_class" to "rapid",
                "white" to mapOf("username" to "p1", "rating" to 1100),
                "black" to mapOf("username" to "p2", "rating" to 1150),
                "pgn" to "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0",
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(chess960Game, standardGame, nullRulesGame))

        val result = traversal.traverse(bounds, sink)

        assertEquals(3, result.gamesInspected)
        assertEquals(2, result.rapidGames, "chess960 must be skipped, standard and null rules must count")
        assertEquals(2, result.qualifyingGames)
        val captor = argumentCaptor<HumanMoveBfsQualifyingGame>()
        verify(sink, times(2)).accept(captor.capture())
        assertTrue(captor.allValues.none { it.rules == "chess960" })
    }
}
