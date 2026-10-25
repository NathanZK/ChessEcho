package com.chessecho.service

import com.chessecho.domain.RatingBand
import com.chessecho.dto.HumanMoveBfsRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory

class HumanMoveBfsBoundsTest : HumanMoveBfsBaseTest() {
    @Test
    fun `every non-empty combination of BFS bounds is accepted`() {
        assertSingleBoundCombinationsAccepted()
        assertMultiBoundCombinationsAccepted()
    }

    private fun mockQualifyingPlayer(player: String) {
        val archive = "https://api.chess.com/pub/player/$player/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls(player)).thenReturn(listOf(archive))
        whenever(chessComClient.fetchMonthlyGames(archive)).thenReturn(
            listOf(rapidGame("http://$player-game", player, 1100, "$player-opponent", 1150)),
        )
    }

    private fun assertSingleBoundCombinationsAccepted() {
        val maxPlayersOnly =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("players-single-a", "players-single-b"),
                    maxPlayers = 1,
                ),
            )
        assertEquals("MAX_PLAYERS", maxPlayersOnly.stopReason)

        val maxDepthOnly =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("depth-single"),
                    maxDepth = 0,
                ),
            )
        assertEquals("MAX_DEPTH", maxDepthOnly.stopReason)

        mockQualifyingPlayer("qual-single")
        val maxQualifyingGamesOnly =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("qual-single"),
                    maxQualifyingGames = 1,
                ),
            )
        assertEquals("MAX_QUALIFYING_GAMES", maxQualifyingGamesOnly.stopReason)
    }

    private fun assertMultiBoundCombinationsAccepted() {
        val maxPlayersAndDepth =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("players-depth-a", "players-depth-b"),
                    maxPlayers = 1,
                    maxDepth = 0,
                ),
            )
        assertEquals("MAX_PLAYERS", maxPlayersAndDepth.stopReason)

        mockQualifyingPlayer("players-qual")
        val maxPlayersAndQualifyingGames =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("players-qual"),
                    maxPlayers = 10,
                    maxQualifyingGames = 1,
                ),
            )
        assertEquals("MAX_QUALIFYING_GAMES", maxPlayersAndQualifyingGames.stopReason)

        mockQualifyingPlayer("depth-qual")
        val maxDepthAndQualifyingGames =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("depth-qual"),
                    maxDepth = 1,
                    maxQualifyingGames = 1,
                ),
            )
        assertEquals("MAX_QUALIFYING_GAMES", maxDepthAndQualifyingGames.stopReason)

        mockQualifyingPlayer("all-bounds")
        val allBounds =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("all-bounds"),
                    maxPlayers = 10,
                    maxDepth = 1,
                    maxQualifyingGames = 1,
                ),
            )
        assertEquals("MAX_QUALIFYING_GAMES", allBounds.stopReason)
    }

    @Test
    fun `all omitted BFS bounds are rejected before fetching or persisting`() {
        val request =
            HumanMoveBfsRequest(
                ratingBand = RatingBand.BAND_1000_1200.value,
                seedPlayers = listOf("p1"),
            )

        assertThrows(IllegalArgumentException::class.java) { service.runBfs(request) }

        verify(chessComClient, never()).fetchArchiveUrls(anyString())
        verify(humanMoveBfsSeenGameRepository, never()).findExistingGameUrls(any())
        verify(humanMoveBfsSeenGameClaimer, never()).claimGameUrls(any<Collection<String>>())
        verify(positionRepository, never()).saveAll(anyList())
        verify(humanMoveDistributionRepository, never()).saveAll(anyList())
    }

    @Test
    fun `depth-only bound does not apply former player-count default`() {
        whenever(chessComClient.fetchArchiveUrls(anyString())).thenReturn(emptyList())

        val response =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = (1..101).map { "player$it" },
                    maxDepth = 0,
                ),
            )

        assertEquals(101, response.playersVisited)
        assertEquals("MAX_DEPTH", response.stopReason)
    }

    @Test
    fun `depth-only bound does not apply former qualifying-game default`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(
            (1..2001).map { rapidGame("http://qualifying-$it", "p1", 1100, "p2", 1150) },
        )

        val response =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("p1"),
                    maxDepth = 0,
                    maxGamesPerPlayer = 2001,
                    batchSize = 5000,
                ),
            )

        assertEquals(2001, response.qualifyingGames)
        assertEquals("MAX_DEPTH", response.stopReason)
    }

    @Test
    fun `maxPlayers-only bound does not apply former depth default`() {
        for (index in 1..5) {
            val player = "depth-chain-$index"
            val archiveUrl = "https://api.chess.com/pub/player/$player/games/2021/01"
            whenever(chessComClient.fetchArchiveUrls(player)).thenReturn(
                if (index == 5) emptyList() else listOf(archiveUrl),
            )
            if (index < 5) {
                whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(
                    listOf(
                        rapidGame(
                            "http://depth-chain-game-$index",
                            player,
                            1100,
                            "depth-chain-${index + 1}",
                            1150,
                        ),
                    ),
                )
            }
        }

        val response =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("depth-chain-1"),
                    maxPlayers = 10,
                ),
            )

        assertEquals(5, response.playersVisited)
        assertEquals(5, response.maxDepthReached)
        assertEquals("EMPTY_FRONTIER", response.stopReason)
    }

    @Test
    fun `supplied maxPlayers bound returns frontier exhaustion stop reason`() {
        whenever(chessComClient.fetchArchiveUrls("empty-frontier")).thenReturn(emptyList())

        val response =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("empty-frontier"),
                    maxPlayers = 10,
                ),
            )

        assertEquals(1, response.playersVisited)
        assertEquals("EMPTY_FRONTIER", response.stopReason)
    }

    @Test
    fun `excluded seed is not visited or fetched`() {
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(emptyList())

        val response =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("ExcludedPlayer", "p1"),
                    excludedPlayers = listOf("eXcLuDeDpLaYeR"),
                    maxDepth = 0,
                ),
            )

        verify(chessComClient, never()).fetchArchiveUrls("excludedplayer")
        verify(chessComClient).fetchArchiveUrls("p1")
        assertEquals(1, response.playersVisited)
    }

    @Test
    fun `excluded discovered opponent is not added to the frontier`() {
        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(
            listOf(rapidGame("http://game1", "p1", 1100, "ExcludedPlayer", 1150)),
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

        verify(chessComClient, never()).fetchArchiveUrls("excludedplayer")
        assertEquals(1, response.playersVisited)
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
                maxPlayers = 10,
            ),
        )

        for (seed in seeds) {
            verify(chessComClient).fetchArchiveUrls(seed)
        }
    }

    @Test
    fun `isRatingInBand verifies boundaries across all rating bands`() {
        // Specific contracts required by issue #563
        assertFalse(HumanMoveBfsTraversal.isRatingInBand(999, RatingBand.BAND_1000_1200))
        assertTrue(HumanMoveBfsTraversal.isRatingInBand(1000, RatingBand.BAND_1000_1200))
        assertTrue(HumanMoveBfsTraversal.isRatingInBand(1200, RatingBand.BAND_1000_1200))
        assertFalse(HumanMoveBfsTraversal.isRatingInBand(1201, RatingBand.BAND_1000_1200))

        assertFalse(HumanMoveBfsTraversal.isRatingInBand(2199, RatingBand.BAND_2200_PLUS))
        assertTrue(HumanMoveBfsTraversal.isRatingInBand(2200, RatingBand.BAND_2200_PLUS))
        assertTrue(HumanMoveBfsTraversal.isRatingInBand(2500, RatingBand.BAND_2200_PLUS))

        // Exhaustive boundary testing across all declared rating bands
        for (band in RatingBand.entries) {
            val parts = band.value.split("-")
            if (parts.size == 2) {
                val min = parts[0].toInt()
                val max = parts[1].toInt()
                assertFalse(HumanMoveBfsTraversal.isRatingInBand(min - 1, band), "Rating ${min - 1} must be out of band $band")
                assertTrue(HumanMoveBfsTraversal.isRatingInBand(min, band), "Rating $min must be in band $band")
                assertTrue(HumanMoveBfsTraversal.isRatingInBand(max, band), "Rating $max must be in band $band")
                assertFalse(HumanMoveBfsTraversal.isRatingInBand(max + 1, band), "Rating ${max + 1} must be out of band $band")
            } else if (band.value.endsWith("+")) {
                val min = band.value.dropLast(1).toInt()
                assertFalse(HumanMoveBfsTraversal.isRatingInBand(min - 1, band), "Rating ${min - 1} must be out of band $band")
                assertTrue(HumanMoveBfsTraversal.isRatingInBand(min, band), "Rating $min must be in band $band")
                assertTrue(HumanMoveBfsTraversal.isRatingInBand(min + 300, band), "Rating ${min + 300} must be in band $band")
            }
        }

        // Malformed band strings return false
        val malformedBand = mock<RatingBand>()
        whenever(malformedBand.value).thenReturn("foo-bar")
        assertFalse(HumanMoveBfsTraversal.isRatingInBand(1000, malformedBand))
        whenever(malformedBand.value).thenReturn("1000-bar")
        assertFalse(HumanMoveBfsTraversal.isRatingInBand(1000, malformedBand))
        whenever(malformedBand.value).thenReturn("foo+")
        assertFalse(HumanMoveBfsTraversal.isRatingInBand(1000, malformedBand))
        whenever(malformedBand.value).thenReturn("invalid")
        assertFalse(HumanMoveBfsTraversal.isRatingInBand(1000, malformedBand))
    }

    @Test
    fun `traversal terminates at exact maxGamesPerPlayer boundary and skips remaining games and archives`() {
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
                maxGamesPerPlayer = 2,
                maxPlayers = null,
                maxDepth = 0,
            )

        val olderArchive = "https://api.chess.com/pub/player/p1/games/2024/01"
        val newerArchive = "https://api.chess.com/pub/player/p1/games/2024/02"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(olderArchive, newerArchive))

        val gamesNewer = (1..5).map { rapidGame("http://newer-$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(newerArchive)).thenReturn(gamesNewer.reversed())

        val result = traversal.traverse(bounds, sink)

        assertEquals(2, result.gamesInspected)
        assertEquals(2, result.rapidGames)
        assertEquals(2, result.qualifyingGames)
        verify(sink, times(2)).accept(any())
        verify(chessComClient, never()).fetchMonthlyGames(olderArchive)
    }

    @Test
    fun `traversal terminates at exact maxQualifyingGames boundary and halts subsequent game and archive processing`() {
        val traversal = HumanMoveBfsTraversal(chessComClient, LoggerFactory.getLogger(HumanMoveBfsTraversal::class.java))
        val sink = mock<HumanMoveBfsSink>()
        whenever(sink.alreadyContributed(any())).thenReturn(emptySet())
        whenever(sink.accept(any())).thenReturn(true)

        val bounds =
            HumanMoveBfsBounds(
                targetBand = RatingBand.BAND_1000_1200,
                seedPlayers = listOf("p1", "p2"),
                excludedPlayers = emptyList(),
                maxQualifyingGames = 3,
                maxGamesPerPlayer = 10,
                maxPlayers = null,
                maxDepth = 0,
            )

        val olderArchive = "https://api.chess.com/pub/player/p1/games/2024/01"
        val newerArchive = "https://api.chess.com/pub/player/p1/games/2024/02"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(olderArchive, newerArchive))

        val gamesNewer = (1..10).map { rapidGame("http://game-$it", "p1", 1100, "p2", 1150) }
        whenever(chessComClient.fetchMonthlyGames(newerArchive)).thenReturn(gamesNewer.reversed())

        val result = traversal.traverse(bounds, sink)

        assertEquals(3, result.qualifyingGames)
        assertEquals("MAX_QUALIFYING_GAMES", result.stopReason)
        verify(sink, times(3)).accept(any())
        verify(chessComClient, never()).fetchMonthlyGames(olderArchive)
        verify(chessComClient, never()).fetchArchiveUrls("p2")
    }

    @Test
    fun `traversal terminates at maxQualifyingGames before starting next player`() {
        val traversal = HumanMoveBfsTraversal(chessComClient, LoggerFactory.getLogger(HumanMoveBfsTraversal::class.java))
        val sink = mock<HumanMoveBfsSink>()
        whenever(sink.alreadyContributed(any())).thenReturn(emptySet())
        whenever(sink.accept(any())).thenReturn(true)

        val bounds =
            HumanMoveBfsBounds(
                targetBand = RatingBand.BAND_1000_1200,
                seedPlayers = listOf("p1", "p2"),
                excludedPlayers = emptyList(),
                maxQualifyingGames = 2,
                maxGamesPerPlayer = 10,
                maxPlayers = null,
                maxDepth = 0,
            )

        val archiveUrl = "https://api.chess.com/pub/player/p1/games/2024/01"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveUrl))
        val games = (1..2).map { rapidGame("http://p1-game-$it", "p1", 1100, "opp", 1150) }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        val result = traversal.traverse(bounds, sink)

        assertEquals(1, result.playersVisited)
        assertEquals(2, result.qualifyingGames)
        assertEquals("MAX_QUALIFYING_GAMES", result.stopReason)
        verify(chessComClient, never()).fetchArchiveUrls("p2")
    }

    @Test
    fun `traversal terminates at exact maxPlayers boundary`() {
        val traversal = HumanMoveBfsTraversal(chessComClient, LoggerFactory.getLogger(HumanMoveBfsTraversal::class.java))
        val sink = mock<HumanMoveBfsSink>()

        val bounds =
            HumanMoveBfsBounds(
                targetBand = RatingBand.BAND_1000_1200,
                seedPlayers = listOf("p1", "p2", "p3"),
                excludedPlayers = emptyList(),
                maxQualifyingGames = null,
                maxGamesPerPlayer = 10,
                maxPlayers = 1,
                maxDepth = 0,
            )

        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(emptyList())

        val result = traversal.traverse(bounds, sink)

        assertEquals(1, result.playersVisited)
        assertEquals("MAX_PLAYERS", result.stopReason)
        verify(chessComClient, never()).fetchArchiveUrls("p2")
        verify(chessComClient, never()).fetchArchiveUrls("p3")
    }
}
