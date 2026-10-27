package com.chessecho.service

import com.chessecho.domain.Position
import com.chessecho.domain.RatingBand
import com.chessecho.dto.HumanMoveBfsRequest
import com.chessecho.dto.HumanMovePopulationDiscoveryRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory

class HumanMoveBfsTraversalStateTest : HumanMoveBfsBaseTest() {
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
    fun `excluded discovered opponent contributes no observations or claimed game`() {
        val stores = makeRepositoryStateful()
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(
            listOf(
                rapidGame(
                    url = "http://excluded-game",
                    whiteUser = "p1",
                    whiteRating = 1100,
                    blackUser = "ExcludedPlayer",
                    blackRating = 1150,
                    pgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 2. Nf3 Nc6 1-0",
                ),
            ),
        )

        val response =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("p1"),
                    excludedPlayers = listOf("eXcLuDeDpLaYeR"),
                    maxDepth = 1,
                ),
            )

        assertEquals(0, response.qualifyingGames)
        assertEquals(0, response.totalObservations)
        assertEquals(0, response.uniqueGamesProcessed)
        assertTrue(stores.distributions.isEmpty())
        assertTrue(stores.seenGameUrls.isEmpty())
        verify(humanMoveBfsSeenGameClaimer, never()).claimGameUrls(any<Collection<String>>())
        verify(humanMoveDistributionRepository, never()).saveAll(anyList())
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
    fun `maxGamesPerPlayer - repeated run with same budget processes zero additional games once all are seen`() {
        // Player has exactly 3 rapid games total. Run 1 with budget 3 claims all
        // of them. Run 2 with the same budget must find 0 new eligible games,
        // because maxGamesPerPlayer bounds *new* games, not games merely
        // inspected/already-claimed.
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
                maxGamesPerPlayer = 3,
            )

        val firstRun = service.runBfs(request)
        assertEquals(3, firstRun.qualifyingGames, "first run must process all 3 available games")
        assertEquals(3, stores.seenGameUrls.size)

        val secondRun = service.runBfs(request)
        assertEquals(
            0,
            secondRun.qualifyingGames,
            "second run with the same budget must contribute 0 new games once all games are already seen",
        )
        assertEquals(3, stores.seenGameUrls.size, "seen-game store must not grow when no new games exist")
    }

    @Test
    fun `maxGamesPerPlayer - increasing the budget between runs processes up to the new budget in new games`() {
        // Traversal order (newest to oldest, i.e. games.reversed()) is:
        //   claim1, claim2, claim3, new1, new2, new3, new4, new5
        // Run 1 (budget 3) claims claim1..claim3, matching the first 3 games
        // in traversal order.
        // Run 2 (budget 5) must find 5 NEW games in its own right, because
        // maxGamesPerPlayer bounds new games per run, not games merely
        // inspected. The already-claimed games earlier in traversal order
        // must not consume any of run 2's budget.
        val stores = makeRepositoryStateful()

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))

        val claimedGames = (1..3).map { rapidGame("http://claim$it", "p1", 1100, "p2", 1150) }
        val newGames = (1..5).map { rapidGame("http://new$it", "p1", 1100, "p2", 1150) }
        val traversalOrder = claimedGames + newGames
        // The service iterates games.reversed(), so feed it pre-reversed to get
        // the intended traversal order above.
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(traversalOrder.reversed())

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                maxGamesPerPlayer = 3,
            ),
        )
        assertEquals(
            setOf("http://claim1", "http://claim2", "http://claim3"),
            stores.seenGameUrls,
            "run 1 must claim exactly the first 3 games in traversal order",
        )

        val secondRun =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("p1"),
                    maxDepth = 0,
                    maxGamesPerPlayer = 5,
                ),
            )

        assertEquals(
            5,
            secondRun.qualifyingGames,
            "run 2 must find all 5 new games up to its own budget of 5, unaffected by the " +
                "3 already-claimed games ahead of them in traversal order",
        )
        assertEquals(
            8,
            stores.seenGameUrls.size,
            "3 previously-claimed + 5 newly-claimed games = 8 distinct games total",
        )
    }

    @Test
    fun `maxGamesPerPlayer - a player with fewer new games than the requested budget contributes only the available new games`() {
        // Player has 5 rapid games total. Run 1 with budget 3 claims 3 of them,
        // leaving only 2 new games available. Run 2 requests a much larger
        // budget (10) than the number of remaining new games; only the 2
        // available new games should be processed, without error or hanging.
        val stores = makeRepositoryStateful()

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
        val games = (1..5).map { rapidGame("http://game$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        service.runBfs(
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
                maxDepth = 0,
                maxGamesPerPlayer = 3,
            ),
        )
        assertEquals(3, stores.seenGameUrls.size)

        val secondRun =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("p1"),
                    maxDepth = 0,
                    maxGamesPerPlayer = 10,
                ),
            )

        assertEquals(
            2,
            secondRun.qualifyingGames,
            "only the 2 remaining new games must be processed even though the budget (10) is much larger",
        )
        assertEquals(5, stores.seenGameUrls.size, "all 5 available games must eventually be claimed across both runs")
    }

    @Test
    fun `maxGamesPerPlayer - BFS continues into an older archive when the newer archive is entirely already seen`() {
        // The newer archive's 3 rapid games were already claimed by a previous
        // run. The older archive has 2 unseen rapid games. A budget of 2 must
        // not be exhausted by re-inspecting the fully-claimed newer archive;
        // the BFS must continue into the older archive to find the 2 new games.
        val stores = makeRepositoryStateful()

        val newerArchive = "https://api.chess.com/pub/player/p1/games/2021/02"
        val olderArchive = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(olderArchive, newerArchive))

        val newerGames = (1..3).map { rapidGame("http://n-rapid$it", "p1", 1100, "p2", 1150) }
        val olderGames = (1..2).map { rapidGame("http://o-rapid$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(newerArchive)).thenReturn(newerGames)
        whenever(chessComClient.fetchMonthlyGames(olderArchive)).thenReturn(olderGames)

        // Simulate a previous run having already claimed all 3 newer-archive games.
        stores.seenGameUrls.addAll(newerGames.mapNotNull { it["url"] as? String })

        val response =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("p1"),
                    maxDepth = 0,
                    maxGamesPerPlayer = 2,
                ),
            )

        assertEquals(
            2,
            response.qualifyingGames,
            "the 2 new games in the older archive must be found despite the newer archive being fully already-seen",
        )
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

    @Test
    fun `population discovery traverses out of band players and returns distinct qualifying opponents without persistence`() {
        val firstArchive = "https://api.chess.com/pub/player/p1/games/2021/01"
        val secondArchive = "https://api.chess.com/pub/player/p2/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(firstArchive))
        whenever(chessComClient.fetchArchiveUrls("p2")).thenReturn(listOf(secondArchive))
        whenever(chessComClient.fetchMonthlyGames(firstArchive)).thenReturn(
            listOf(rapidGame("http://out-of-band", "p1", 1100, "p2", 2000)),
        )
        whenever(chessComClient.fetchMonthlyGames(secondArchive)).thenReturn(
            listOf(
                rapidGame("http://qualifying-1", "p2", 2000, "p3", 1150),
                rapidGame("http://qualifying-1-duplicate", "p2", 2000, "p3", 1150),
            ),
        )
        whenever(chessComClient.fetchArchiveUrls("p3")).thenReturn(emptyList())

        val response =
            service.discoverPopulation(
                HumanMovePopulationDiscoveryRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("p1"),
                    targetQualifyingPlayers = 1,
                    maxPlayers = 10,
                    maxDepth = 2,
                ),
            )

        assertEquals(listOf("p3"), response.qualifyingPlayers)
        assertEquals(1, response.qualifyingPlayerCount)
        assertEquals("TARGET_QUALIFYING_PLAYERS", response.stopReason)
        assertEquals(3, response.playersVisited)
        verify(humanMoveDistributionRepository, never()).saveAll(anyList())
        verify(positionRepository, never()).saveAll(any<Iterable<Position>>())
        verify(humanMoveBfsSeenGameRepository, never()).findExistingGameUrls(any())
        verify(humanMoveBfsSeenGameClaimer, never()).claimGameUrls(any())
    }

    @Test
    fun `population discovery excludes players and does not count out of band or non rapid games`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(
            listOf(
                rapidGame("http://excluded", "p1", 1100, "excluded", 1150),
                rapidGame("http://out-of-band", "p1", 1100, "p2", 1300),
                blitzGame("http://blitz", "p1", 1100, "p3", 1150),
            ),
        )

        val response =
            service.discoverPopulation(
                HumanMovePopulationDiscoveryRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("p1"),
                    excludedPlayers = listOf("excluded"),
                    targetQualifyingPlayers = 5,
                    maxDepth = 0,
                ),
            )

        assertTrue(response.qualifyingPlayers.isEmpty())
        assertEquals("MAX_DEPTH", response.stopReason)
        assertEquals(1, response.playersVisited)
        verify(chessComClient, never()).fetchArchiveUrls("excluded")
        verify(humanMoveDistributionRepository, never()).saveAll(anyList())
    }

    @Test
    fun `traversal returns exact uniqueGamesProcessed metric`() {
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
        val games =
            listOf(
                rapidGame("http://game1", "p1", 1100, "p2", 1150),
                rapidGame("http://game2", "p1", 1100, "p3", 1150),
                rapidGame("http://game3", "p1", 1100, "p4", 1150),
            )
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        val result = traversal.traverse(bounds, sink)

        assertEquals(3, result.uniqueGamesProcessed)
    }
}
