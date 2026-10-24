package com.chessecho.service

import com.chessecho.domain.RatingBand
import com.chessecho.dto.HumanMoveBfsRequest
import com.chessecho.repository.HumanMoveBfsClaimConflictException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory

class HumanMoveBfsSingletonTest : HumanMoveBfsBaseTest() {
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
    fun `optional traversal bound preserves per-player budget and batch flushing`() {
        val archiveUrl = "https://api.chess.com/pub/player/optional-bound/games/2021/01"
        whenever(chessComClient.fetchArchiveUrls("optional-bound")).thenReturn(listOf(archiveUrl))
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(
            (1..5).map {
                rapidGame("http://optional-bound-game-$it", "optional-bound", 1100, "opponent$it", 1150)
            },
        )

        val response =
            service.runBfs(
                HumanMoveBfsRequest(
                    ratingBand = RatingBand.BAND_1000_1200.value,
                    seedPlayers = listOf("optional-bound"),
                    maxDepth = 0,
                    maxGamesPerPlayer = 3,
                    batchSize = 2,
                ),
            )

        assertEquals(3, response.gamesInspected)
        assertEquals(3, response.rapidGames)
        assertEquals(3, response.qualifyingGames)
        assertEquals("MAX_DEPTH", response.stopReason)
        verify(humanMoveDistributionRepository, times(2)).saveAll(anyList())
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

    @Test
    fun `sink error notification on archive list failure proceeds to subsequent seeds`() {
        val traversal = HumanMoveBfsTraversal(chessComClient, LoggerFactory.getLogger(HumanMoveBfsTraversal::class.java))
        val sink = mock<HumanMoveBfsSink>()
        val bounds =
            HumanMoveBfsBounds(
                targetBand = RatingBand.BAND_1000_1200,
                seedPlayers = listOf("seedPlayer1", "seedPlayer2"),
                excludedPlayers = emptyList(),
                maxQualifyingGames = null,
                maxGamesPerPlayer = 10,
                maxPlayers = null,
                maxDepth = 0,
            )

        val timeoutException = RuntimeException("archive connection timeout")
        whenever(chessComClient.fetchArchiveUrls("seedplayer1")).thenThrow(timeoutException)
        whenever(chessComClient.fetchArchiveUrls("seedplayer2")).thenReturn(emptyList())

        val result = traversal.traverse(bounds, sink)

        verify(sink).onArchiveListFailure(eq("seedplayer1"), eq(timeoutException))
        verify(chessComClient).fetchArchiveUrls("seedplayer2")
        assertEquals(2, result.playersVisited)
    }

    @Test
    fun `sink error notification on monthly games failure proceeds to subsequent archives`() {
        val traversal = HumanMoveBfsTraversal(chessComClient, LoggerFactory.getLogger(HumanMoveBfsTraversal::class.java))
        val sink = mock<HumanMoveBfsSink>()
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

        val olderArchive = "https://api.chess.com/pub/player/p1/games/2024/01"
        val newerArchive = "https://api.chess.com/pub/player/p1/games/2024/02"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(olderArchive, newerArchive))

        val http500Exception = RuntimeException("games HTTP 500")
        // Traversal reverses archiveUrls, so newerArchive is fetched first
        whenever(chessComClient.fetchMonthlyGames(newerArchive)).thenThrow(http500Exception)
        whenever(chessComClient.fetchMonthlyGames(olderArchive)).thenReturn(emptyList())

        traversal.traverse(bounds, sink)

        verify(sink).onMonthlyGamesFailure(eq(newerArchive), eq(http500Exception))
        verify(chessComClient).fetchMonthlyGames(olderArchive)
    }
}
