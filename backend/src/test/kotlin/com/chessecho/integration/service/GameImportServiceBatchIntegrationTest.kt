package com.chessecho.integration.service

import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.service.GameImportService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GameImportServiceBatchIntegrationTest : GameImportServiceBaseIntegrationTest() {
    @Test
    @org.springframework.transaction.annotation.Transactional
    fun `updateUserPositionStats handles empty set, sub-1000 set, and multi-batch over 1000 positions correctly`() {
        val appUser = appUserRepository.save(com.chessecho.domain.AppUser(email = "batch@test.com"))
        val account =
            chessAccountRepository.save(
                com.chessecho.domain.ChessAccount(platform = "CHESS_COM", username = "batchuser"),
            )
        val dummyGame = gameRepository.save(com.chessecho.domain.Game(chessAccount = account, platformGameId = "dummy1", pgn = "1. e4"))

        val targetService = org.springframework.test.util.AopTestUtils.getUltimateTargetObject<GameImportService>(gameImportService)
        val method =
            GameImportService::class.java.getDeclaredMethod(
                "updateUserPositionStats",
                com.chessecho.domain.ChessAccount::class.java,
                Set::class.java,
            )
        method.isAccessible = true

        // 1. Empty position-ID set
        method.invoke(targetService, account, emptySet<UUID>())
        var stats = userPositionStatsRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertEquals(0, stats.size)

        // 2. Sub-1000 positions
        val smallIds = seedPositionsAndOccurrences(dummyGame, account, "small", 50)
        method.invoke(targetService, account, smallIds)

        stats = userPositionStatsRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertEquals(50, stats.size)
        stats.forEach { assertEquals(1, it.timesReached) }

        // 3. Multi-batch (> 1000 positions: 1050 positions)
        userPositionStatsRepository.deleteAll()
        positionOccurrenceRepository.deleteAll()
        positionRepository.deleteAll()

        val largeIds = seedPositionsAndOccurrences(dummyGame, account, "large", 1050)
        method.invoke(targetService, account, largeIds)

        stats = userPositionStatsRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertEquals(1050, stats.size, "Expected exactly 1050 UserPositionStats across 2 batches")
        stats.forEach { assertEquals(1, it.timesReached) }
    }

    private fun seedPositionsAndOccurrences(
        dummyGame: com.chessecho.domain.Game,
        account: com.chessecho.domain.ChessAccount,
        prefix: String,
        count: Int,
    ): Set<UUID> {
        val positions =
            (1..count).map { idx ->
                positionRepository.save(com.chessecho.domain.Position(hash = "${prefix}_hash_$idx", fen = "fen_${prefix}_$idx"))
            }
        positions.forEach { pos ->
            positionOccurrenceRepository.save(
                com.chessecho.domain.PositionOccurrence(
                    game = dummyGame,
                    position = pos,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "e4",
                    playerColor = "WHITE",
                ),
            )
        }
        return positions.map { it.id }.toSet()
    }

    @Test
    fun `cap enforcement limits eligible games selected within a single archive month`() {
        val archiveUrl = "https://api.chess.com/pub/player/capuser1/games/2020/01"
        whenever(chessComClient.fetchArchiveUrls("capuser1")).thenReturn(listOf(archiveUrl))
        val games = (1..5).map { importGame("https://www.chess.com/game/live/cap1_$it", "capuser1") }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        val request =
            ImportGamesRequest(
                username = "capuser1",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
                maxEligibleGames = 3,
            )

        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)
        val completed = waitForJob(job.id)

        assertEquals("COMPLETED", completed.status)
        assertEquals(3, completed.gamesImported)
        assertEquals(3, completed.eligibleGamesSelected)

        val savedGames = gameRepository.findAll().filter { it.whiteUsername == "capuser1" }
        assertEquals(3, savedGames.size, "Only the capped number of eligible games should be persisted")
    }

    @Test
    fun `cap applies across archive months and stops selection once reached`() {
        val month1Url = "https://api.chess.com/pub/player/capuser2/games/2020/01"
        val month2Url = "https://api.chess.com/pub/player/capuser2/games/2020/02"
        whenever(chessComClient.fetchArchiveUrls("capuser2")).thenReturn(listOf(month1Url, month2Url))

        val month1Games = (1..2).map { importGame("https://www.chess.com/game/live/capm1_$it", "capuser2") }
        val month2Games = (1..5).map { importGame("https://www.chess.com/game/live/capm2_$it", "capuser2") }
        whenever(chessComClient.fetchMonthlyGames(month1Url)).thenReturn(month1Games)
        whenever(chessComClient.fetchMonthlyGames(month2Url)).thenReturn(month2Games)

        val request =
            ImportGamesRequest(
                username = "capuser2",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
                maxEligibleGames = 4,
            )

        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)
        val completed = waitForJob(job.id)

        assertEquals("COMPLETED", completed.status)
        assertEquals(4, completed.gamesImported)
        assertEquals(4, completed.eligibleGamesSelected)

        val savedGames = gameRepository.findAll().filter { it.whiteUsername == "capuser2" }
        assertEquals(4, savedGames.size)

        val account = chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "capuser2")!!
        val month1Archive = importedArchiveRepository.findByChessAccountAndArchiveUrl(account, month1Url)
        val month2Archive = importedArchiveRepository.findByChessAccountAndArchiveUrl(account, month2Url)
        assertNotNull(month1Archive, "Fully-consumed month should be recorded as fully imported")
        assertEquals(null, month2Archive, "Cap-truncated month must not be marked as fully imported")
    }

    @Test
    fun `fewer eligible games than the cap imports everything available`() {
        val archiveUrl = "https://api.chess.com/pub/player/capuser3/games/2020/01"
        whenever(chessComClient.fetchArchiveUrls("capuser3")).thenReturn(listOf(archiveUrl))
        val games = (1..2).map { importGame("https://www.chess.com/game/live/capu3_$it", "capuser3") }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        val request =
            ImportGamesRequest(
                username = "capuser3",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
                maxEligibleGames = 10,
            )

        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)
        val completed = waitForJob(job.id)

        assertEquals("COMPLETED", completed.status)
        assertEquals(2, completed.gamesImported)
        assertEquals(2, completed.eligibleGamesSelected)
    }

    @Test
    fun `omitting the cap preserves current import behavior and persists the archive`() {
        val archiveUrl = "https://api.chess.com/pub/player/capuser4/games/2020/01"
        whenever(chessComClient.fetchArchiveUrls("capuser4")).thenReturn(listOf(archiveUrl))
        val games = (1..3).map { importGame("https://www.chess.com/game/live/capu4_$it", "capuser4") }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        val request =
            ImportGamesRequest(
                username = "capuser4",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
            )

        val job = gameImportService.createImportJob(request)
        assertEquals(null, job.maxEligibleGames)
        gameImportService.executeImportJob(job.id, request)
        val completed = waitForJob(job.id)

        assertEquals("COMPLETED", completed.status)
        assertEquals(3, completed.gamesImported)
        assertEquals(3, completed.eligibleGamesSelected)

        val account = chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "capuser4")!!
        val archive = importedArchiveRepository.findByChessAccountAndArchiveUrl(account, archiveUrl)
        assertNotNull(archive, "Uncapped past month must still be recorded as fully imported, exactly as today")
        assertEquals(3, archive!!.gameCount)
    }

    @Test
    fun `ineligible and duplicate games do not consume the eligible-game cap`() {
        val archiveUrl = "https://api.chess.com/pub/player/capuser5/games/2020/01"
        whenever(chessComClient.fetchArchiveUrls("capuser5")).thenReturn(listOf(archiveUrl))

        val eligible1 = importGame("https://www.chess.com/game/live/capu5_e1", "capuser5")
        val duplicate = importGame("https://www.chess.com/game/live/capu5_e1", "capuser5")
        val ineligible = importGame("https://www.chess.com/game/live/capu5_i1", "capuser5", timeClass = "rapid")
        val eligible2 = importGame("https://www.chess.com/game/live/capu5_e2", "capuser5")
        val eligible3 = importGame("https://www.chess.com/game/live/capu5_e3", "capuser5")

        whenever(chessComClient.fetchMonthlyGames(archiveUrl))
            .thenReturn(listOf(eligible1, duplicate, ineligible, eligible2, eligible3))

        val request =
            ImportGamesRequest(
                username = "capuser5",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
                maxEligibleGames = 2,
            )

        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)
        val completed = waitForJob(job.id)

        assertEquals("COMPLETED", completed.status)
        assertEquals(2, completed.gamesImported)
        assertEquals(2, completed.eligibleGamesSelected)
        assertEquals(
            1,
            completed.gamesSkipped,
            "Only the in-batch duplicate should count as skipped; the ineligible rapid game is neither imported nor skipped",
        )
        assertEquals(
            5,
            completed.gamesProcessed,
            "Every raw entry in the month, including the one past the cap boundary, is still processed",
        )

        val savedUrls = gameRepository.findAll().filter { it.whiteUsername == "capuser5" }.map { it.platformGameId }.toSet()
        assertEquals(
            setOf("https://www.chess.com/game/live/capu5_e1", "https://www.chess.com/game/live/capu5_e2"),
            savedUrls,
        )

        val account = chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "capuser5")!!
        val archive = importedArchiveRepository.findByChessAccountAndArchiveUrl(account, archiveUrl)
        assertEquals(
            null,
            archive,
            "A month with an additional eligible, non-duplicate game past the cap boundary must not be marked fully imported",
        )
    }

    @Test
    fun `full-month scan continues past the cap boundary so skipped and processed counters reflect a full-month scan`() {
        val archiveUrl = "https://api.chess.com/pub/player/capuser5b/games/2020/01"
        whenever(chessComClient.fetchArchiveUrls("capuser5b")).thenReturn(listOf(archiveUrl))

        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(capBoundaryMonthlyGames("capuser5b"))

        val request =
            ImportGamesRequest(
                username = "capuser5b",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
                maxEligibleGames = 2,
            )

        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)
        val completed = waitForJob(job.id)

        assertEquals("COMPLETED", completed.status)
        assertEquals(2, completed.gamesImported)
        assertEquals(2, completed.eligibleGamesSelected)
        assertEquals(
            2,
            completed.gamesSkipped,
            "Both duplicates (before and after the cap boundary) must be counted as skipped, proving the scan continues past the boundary",
        )
        assertEquals(
            7,
            completed.gamesProcessed,
            "Every raw entry in the month, including ineligible/duplicate ones past the cap boundary, must still be processed",
        )

        val savedUrls = gameRepository.findAll().filter { it.whiteUsername == "capuser5b" }.map { it.platformGameId }.toSet()
        assertEquals(
            setOf("https://www.chess.com/game/live/capu5b_e1", "https://www.chess.com/game/live/capu5b_e2"),
            savedUrls,
        )

        val account = chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "capuser5b")!!
        val archive = importedArchiveRepository.findByChessAccountAndArchiveUrl(account, archiveUrl)
        assertEquals(
            null,
            archive,
            "eligible3 is an additional eligible, non-duplicate game past the cap boundary, so this month " +
                "must not be marked fully imported",
        )
    }

    private fun capBoundaryMonthlyGames(
        username: String,
        prefix: String = "capu5b",
    ): List<Map<String, Any>> =
        listOf(
            importGame("https://www.chess.com/game/live/${prefix}_e1", username),
            importGame("https://www.chess.com/game/live/${prefix}_e1", username),
            importGame("https://www.chess.com/game/live/${prefix}_i1", username, timeClass = "rapid"),
            importGame("https://www.chess.com/game/live/${prefix}_e2", username),
            importGame("https://www.chess.com/game/live/${prefix}_e3", username),
            importGame("https://www.chess.com/game/live/${prefix}_e2", username),
            importGame("https://www.chess.com/game/live/${prefix}_i2", username, timeClass = "rapid"),
        )

    @Test
    fun `cap exactly matching a months true eligible count still persists the archive as fully imported`() {
        val archiveUrl = "https://api.chess.com/pub/player/capuser6/games/2020/01"
        whenever(chessComClient.fetchArchiveUrls("capuser6")).thenReturn(listOf(archiveUrl))
        val games = (1..3).map { importGame("https://www.chess.com/game/live/capu6_$it", "capuser6") }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        val request =
            ImportGamesRequest(
                username = "capuser6",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
                maxEligibleGames = 3,
            )

        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)
        val completed = waitForJob(job.id)

        assertEquals(3, completed.gamesImported)
        assertEquals(3, completed.eligibleGamesSelected)

        val account = chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "capuser6")!!
        val archive = importedArchiveRepository.findByChessAccountAndArchiveUrl(account, archiveUrl)
        assertNotNull(archive, "A cap that exactly matches the month's true eligible count is not a truncation")
        assertEquals(3, archive!!.gameCount)

        // A later job for the same account must take the skip-download path for this month.
        val secondRequest = request.copy(maxEligibleGames = null)
        val secondJob = gameImportService.createImportJob(secondRequest)
        gameImportService.executeImportJob(secondJob.id, secondRequest)
        waitForJob(secondJob.id)

        verify(chessComClient, org.mockito.kotlin.times(1)).fetchMonthlyGames(archiveUrl)
    }

    @Test
    fun `import workers enforce the cap reconstructed from persisted job configuration`() {
        val archiveUrl = "https://api.chess.com/pub/player/capuser7/games/2020/01"
        whenever(chessComClient.fetchArchiveUrls("capuser7")).thenReturn(listOf(archiveUrl))
        val games = (1..5).map { importGame("https://www.chess.com/game/live/capu7_$it", "capuser7") }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        val request =
            ImportGamesRequest(
                username = "capuser7",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
                maxEligibleGames = 2,
            )

        val job = gameImportService.createImportJob(request)
        assertEquals(2, job.maxEligibleGames)

        // No request object passed: the worker must reconstruct the cap from the
        // persisted AsyncJob, not from a transient request.
        gameImportService.executeImportJob(job.id)
        val completed = waitForJob(job.id)

        assertEquals(2, completed.gamesImported)
        assertEquals(2, completed.eligibleGamesSelected)
    }

    @Test
    fun `cap selection is deterministic across repeated runs of the same fixture`() {
        fun runOnce(username: String): Set<String> {
            val archiveUrl = "https://api.chess.com/pub/player/$username/games/2020/01"
            whenever(chessComClient.fetchArchiveUrls(username)).thenReturn(listOf(archiveUrl))
            val games = (1..6).map { importGame("https://www.chess.com/game/live/${username}_$it", username) }
            whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

            val request =
                ImportGamesRequest(
                    username = username,
                    platform = Platform.CHESS_COM,
                    timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                    playerColor = PlayerColor.BOTH,
                    maxEligibleGames = 4,
                )
            val job = gameImportService.createImportJob(request)
            gameImportService.executeImportJob(job.id, request)
            waitForJob(job.id)
            return gameRepository.findAll().filter { it.whiteUsername == username }.map { it.platformGameId }.toSet()
        }

        val firstSelection = runOnce("capuser8a")
        val secondSelection = runOnce("capuser8b")

        val normalize: (Set<String>) -> Set<String> = { urls -> urls.map { it.substringAfterLast("_") }.toSet() }
        assertEquals(
            normalize(firstSelection),
            normalize(secondSelection),
            "The same fixture must select the same relative games across independent runs",
        )
        assertEquals(4, firstSelection.size)
        assertEquals(4, secondSelection.size)
    }

    @Test
    fun `an unrelated prior jobs persisted archive does not inflate this jobs cap accounting`() {
        val staleMonthUrl = "https://api.chess.com/pub/player/capuser9/games/2019/12"
        val newMonthUrl = "https://api.chess.com/pub/player/capuser9/games/2020/01"
        whenever(chessComClient.fetchArchiveUrls("capuser9")).thenReturn(listOf(staleMonthUrl, newMonthUrl))

        val account =
            chessAccountRepository.save(
                com.chessecho.domain.ChessAccount(platform = "CHESS_COM", username = "capuser9"),
            )
        importedArchiveRepository.saveAndFlush(
            com.chessecho.domain.ImportedArchive(
                chessAccount = account,
                archiveUrl = staleMonthUrl,
                yearMonth = "2019-12",
                gameCount = 999,
            ),
        )

        val newMonthGames = (1..5).map { importGame("https://www.chess.com/game/live/capu9_$it", "capuser9") }
        whenever(chessComClient.fetchMonthlyGames(newMonthUrl)).thenReturn(newMonthGames)

        val request =
            ImportGamesRequest(
                username = "capuser9",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
                maxEligibleGames = 3,
            )

        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)
        val completed = waitForJob(job.id)

        assertEquals(3, completed.gamesImported, "Only this job's own new-month selection should count toward gamesImported")
        assertEquals(
            3,
            completed.eligibleGamesSelected,
            "The pre-seeded unrelated archive's gameCount must not inflate this job's cap counter",
        )
    }

    @Test
    fun `a resumed job continues cap accounting from its own persisted progress rather than restarting at zero`() {
        val month1Url = "https://api.chess.com/pub/player/capuser10/games/2019/12"
        val month2Url = "https://api.chess.com/pub/player/capuser10/games/2020/01"
        whenever(chessComClient.fetchArchiveUrls("capuser10")).thenReturn(listOf(month1Url, month2Url))

        val month2Games = (1..5).map { importGame("https://www.chess.com/game/live/capu10_$it", "capuser10") }
        whenever(chessComClient.fetchMonthlyGames(month2Url)).thenReturn(month2Games)
        whenever(chessComClient.fetchMonthlyGames(month1Url)).thenReturn(emptyList())

        val request =
            ImportGamesRequest(
                username = "capuser10",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
                maxEligibleGames = 3,
            )

        val job = gameImportService.createImportJob(request)
        val account = job.chessAccount!!

        // Simulate a crash after month1 was fully (non-truncated) committed and
        // this job's own eligibleGamesSelected progress was durably checkpointed,
        // but before month2 was attempted.
        importedArchiveRepository.saveAndFlush(
            com.chessecho.domain.ImportedArchive(
                chessAccount = account,
                archiveUrl = month1Url,
                yearMonth = "2019-12",
                gameCount = 2,
            ),
        )
        job.eligibleGamesSelected = 2
        job.gamesImported = 2
        asyncJobRepository.saveAndFlush(job)

        gameImportService.executeImportJob(job.id)
        val completed = waitForJob(job.id)

        assertEquals(3, completed.eligibleGamesSelected, "Resumed run must continue the cap spend from its own persisted progress")
        assertEquals(1, completed.gamesImported, "Only the newly-imported month2 game from this resumed invocation should be counted")

        val month2SavedGames = gameRepository.findAll().filter { it.whiteUsername == "capuser10" }
        assertEquals(1, month2SavedGames.size, "Only one additional eligible game should be selected to reach the cap of 3")
    }

    @Test
    fun `a cap-truncated month is not marked fully imported so a later independent job recovers the remainder`() {
        val archiveUrl = "https://api.chess.com/pub/player/capuser11/games/2020/01"
        whenever(chessComClient.fetchArchiveUrls("capuser11")).thenReturn(listOf(archiveUrl))
        val games = (1..5).map { importGame("https://www.chess.com/game/live/capu11_$it", "capuser11") }
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(games)

        val cappedRequest =
            ImportGamesRequest(
                username = "capuser11",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
                maxEligibleGames = 2,
            )

        val jobA = gameImportService.createImportJob(cappedRequest)
        gameImportService.executeImportJob(jobA.id, cappedRequest)
        val completedA = waitForJob(jobA.id)
        assertEquals(2, completedA.gamesImported)

        val account = chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "capuser11")!!
        assertEquals(null, importedArchiveRepository.findByChessAccountAndArchiveUrl(account, archiveUrl))
        assertEquals(2, gameRepository.findAll().count { it.whiteUsername == "capuser11" })

        // Job B: same account, uncapped, should recover exactly the remaining eligible games.
        val uncappedRequest = cappedRequest.copy(maxEligibleGames = null)
        val jobB = gameImportService.createImportJob(uncappedRequest)
        gameImportService.executeImportJob(jobB.id, uncappedRequest)
        val completedB = waitForJob(jobB.id)

        assertEquals(3, completedB.gamesImported, "Job B must import exactly the remaining eligible games job A did not select")
        assertEquals(
            5,
            gameRepository.findAll().count { it.whiteUsername == "capuser11" },
            "No duplicate rows should be created across job A and job B",
        )

        val archiveAfterB = importedArchiveRepository.findByChessAccountAndArchiveUrl(account, archiveUrl)
        assertNotNull(archiveAfterB, "Job B's own uncapped scan of the month should now mark it fully imported")
        assertEquals(5, archiveAfterB!!.gameCount)
    }
}
