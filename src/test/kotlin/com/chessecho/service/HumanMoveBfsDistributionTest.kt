package com.chessecho.service

import com.chessecho.domain.HumanMoveDistribution
import com.chessecho.domain.RatingBand
import com.chessecho.dto.HumanMoveBfsRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory

class HumanMoveBfsDistributionTest : HumanMoveBfsBaseTest() {
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
    fun `processGamePgn handles corrupt PGN gracefully returning PARSE_FAILED`() {
        val traversal = HumanMoveBfsTraversal(chessComClient, LoggerFactory.getLogger(HumanMoveBfsTraversal::class.java))
        val observations = mutableMapOf<Pair<String, String>, Int>()
        val fenByHash = mutableMapOf<String, String>()
        val occurrences = mutableListOf<HumanMoveCorpusOccurrence>()

        val corruptPgn =
            """
            [Event "Corrupt Move Syntax"]
            [Result "*"]

            1. e9 1-0
            """.trimIndent()

        val outcome =
            traversal.processGamePgn(
                pgn = corruptPgn,
                isWhiteInBand = true,
                isBlackInBand = true,
                observations = observations,
                fenByHash = fenByHash,
                occurrences = occurrences,
            )

        assertEquals(HumanMoveBfsPgnOutcome.PARSE_FAILED, outcome)
        assertTrue(observations.isEmpty())
        assertTrue(fenByHash.isEmpty())
        assertTrue(occurrences.isEmpty())
    }

    @Test
    fun `processGamePgn detects non-standard initial FEN returning NON_STANDARD_START`() {
        val traversal = HumanMoveBfsTraversal(chessComClient, LoggerFactory.getLogger(HumanMoveBfsTraversal::class.java))
        val observations = mutableMapOf<Pair<String, String>, Int>()
        val fenByHash = mutableMapOf<String, String>()
        val occurrences = mutableListOf<HumanMoveCorpusOccurrence>()

        val nonStandardPgn =
            "[Event \"Custom FEN Game\"]\n[SetUp \"1\"]\n[FEN \"4k3/8/8/8/8/8/8/4K3 w - - 0 1\"]\n[Result \"*\"]\n\n1. Kd2 Kd7 *"

        val outcome =
            traversal.processGamePgn(
                pgn = nonStandardPgn,
                isWhiteInBand = true,
                isBlackInBand = true,
                observations = observations,
                fenByHash = fenByHash,
                occurrences = occurrences,
            )

        assertEquals(HumanMoveBfsPgnOutcome.NON_STANDARD_START, outcome)
        assertTrue(observations.isEmpty())
        assertTrue(fenByHash.isEmpty())
        assertTrue(occurrences.isEmpty())
    }

    @Test
    fun `processGamePgn parses valid game and correctly populates observations and occurrences with preMovePly`() {
        val traversal = HumanMoveBfsTraversal(chessComClient, LoggerFactory.getLogger(HumanMoveBfsTraversal::class.java))
        val observations = mutableMapOf<Pair<String, String>, Int>()
        val fenByHash = mutableMapOf<String, String>()
        val occurrences = mutableListOf<HumanMoveCorpusOccurrence>()

        val standardPgn =
            """
            [Event "Live Chess"]
            [Result "1-0"]

            1. e4 e5 2. Nf3 Nc6 1-0
            """.trimIndent()

        val outcome =
            traversal.processGamePgn(
                pgn = standardPgn,
                isWhiteInBand = true,
                isBlackInBand = true,
                observations = observations,
                fenByHash = fenByHash,
                occurrences = occurrences,
            )

        assertEquals(HumanMoveBfsPgnOutcome.PARSED, outcome)
        assertEquals(4, occurrences.size)
        assertEquals(1, occurrences[0].preMovePly)
        assertEquals("e4", occurrences[0].movePlayed)
        assertEquals(2, occurrences[1].preMovePly)
        assertEquals("e5", occurrences[1].movePlayed)
        assertEquals(3, occurrences[2].preMovePly)
        assertEquals("Nf3", occurrences[2].movePlayed)
        assertEquals(4, occurrences[3].preMovePly)
        assertEquals("Nc6", occurrences[3].movePlayed)
        assertTrue(observations.isNotEmpty())
        assertTrue(fenByHash.isNotEmpty())
    }
}
