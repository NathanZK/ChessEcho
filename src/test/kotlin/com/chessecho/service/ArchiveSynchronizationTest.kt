package com.chessecho.service

import com.chessecho.domain.AsyncJob
import com.chessecho.domain.Platform
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.ImportedArchiveRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.UserPositionStatsRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import java.time.YearMonth
import java.time.ZoneOffset

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ArchiveSynchronizationTest {
    @Autowired
    private lateinit var gameImportService: GameImportService

    @Autowired
    private lateinit var asyncJobRepository: AsyncJobRepository

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var importedArchiveRepository: ImportedArchiveRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    private lateinit var userPositionStatsRepository: UserPositionStatsRepository

    @MockBean
    private lateinit var chessComClient: ChessComClient

    @MockBean
    private lateinit var engineAnalysisOrchestrator: EngineAnalysisOrchestrator

    private val pastMonthUrl = "https://api.chess.com/pub/player/syncuser/games/2020/01"
    private val currentMonthStr = YearMonth.now(ZoneOffset.UTC).toString()
    private val currentMonthParts = currentMonthStr.split("-")
    private val currentMonthUrl = "https://api.chess.com/pub/player/syncuser/games/${currentMonthParts[0]}/${currentMonthParts[1]}"

    @BeforeEach
    fun setup() {
        whenever(chessComClient.fetchArchiveUrls("syncuser")).thenReturn(
            listOf(pastMonthUrl, currentMonthUrl),
        )
    }

    @AfterEach
    fun tearDown() {
        importedArchiveRepository.deleteAll()
        userPositionStatsRepository.deleteAll()
        positionOccurrenceRepository.deleteAll()
        positionRepository.deleteAll()
        gameRepository.deleteAll()
        chessAccountRepository.deleteAll()
        appUserRepository.deleteAll()
        asyncJobRepository.deleteAll()
    }

    @Test
    fun `7 Successfully imported historical archive is skipped on next import and recorded in DB`() {
        val gamePgn = "[Event \"Live Chess\"]\n[White \"syncuser\"]\n[Black \"opp\"]\n1. e4 e5 1-0"
        val gameMap =
            mapOf(
                "url" to "https://www.chess.com/game/live/past100",
                "pgn" to gamePgn,
                "time_class" to "blitz",
                "rules" to "chess",
                "end_time" to 1577836800L,
                "white" to mapOf("username" to "syncuser", "result" to "win"),
                "black" to mapOf("username" to "opp", "result" to "checkmated"),
            )

        whenever(chessComClient.fetchMonthlyGames(pastMonthUrl)).thenReturn(listOf(gameMap))
        whenever(chessComClient.fetchMonthlyGames(currentMonthUrl)).thenReturn(emptyList())

        val request =
            ImportGamesRequest(
                username = "syncuser",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
            )

        val job1 = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job1.id, request)

        val job1Completed = waitForJob(job1.id)
        assertEquals(1, job1Completed.gamesImported)
        assertEquals(0, job1Completed.gamesSkipped)

        val account = chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "syncuser")!!
        val archivesInDb = importedArchiveRepository.findByChessAccount(account)

        assertEquals(1, archivesInDb.size, "Past month archive should be recorded in imported_archive table")
        assertEquals(pastMonthUrl, archivesInDb[0].archiveUrl)
        assertEquals(1, archivesInDb[0].gameCount, "Stored gameCount should equal the 1 imported game")

        // Second import run
        val job2 = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job2.id, request)

        val job2Completed = waitForJob(job2.id)
        assertEquals(0, job2Completed.gamesImported)
        assertEquals(1, job2Completed.gamesSkipped, "Historical archive gameCount must be added to gamesSkipped on second import")

        // Verify that pastMonthUrl was ONLY requested on the first run, and SKIPPED on the second run!
        verify(chessComClient, times(1)).fetchMonthlyGames(pastMonthUrl)
    }

    @Test
    fun `8 Current month is fetched again on subsequent imports and NOT marked imported in DB`() {
        val gamePgn = "[Event \"Live Chess\"]\n[White \"syncuser\"]\n[Black \"opp\"]\n1. e4 e5 1-0"
        val gameMap =
            mapOf(
                "url" to "https://www.chess.com/game/live/curr100",
                "pgn" to gamePgn,
                "time_class" to "blitz",
                "rules" to "chess",
                "end_time" to 1704067200L,
                "white" to mapOf("username" to "syncuser", "result" to "win"),
                "black" to mapOf("username" to "opp", "result" to "checkmated"),
            )

        whenever(chessComClient.fetchMonthlyGames(pastMonthUrl)).thenReturn(emptyList())
        whenever(chessComClient.fetchMonthlyGames(currentMonthUrl)).thenReturn(listOf(gameMap))

        val request =
            ImportGamesRequest(
                username = "syncuser",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
            )

        val job1 = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job1.id, request)
        waitForJob(job1.id)

        val account = chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "syncuser")!!
        val archivesInDb = importedArchiveRepository.findByChessAccount(account)
        val currentMonthRecord = archivesInDb.find { it.archiveUrl == currentMonthUrl }
        assertTrue(currentMonthRecord == null, "Current month must NOT be recorded as permanently imported in DB")

        val job2 = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job2.id, request)
        waitForJob(job2.id)

        // Verify that currentMonthUrl was requested TWICE (on both runs)
        verify(chessComClient, times(2)).fetchMonthlyGames(currentMonthUrl)
    }

    @Test
    fun `9 Failed historical archive is NOT marked imported`() {
        whenever(chessComClient.fetchMonthlyGames(pastMonthUrl)).thenThrow(RuntimeException("Network failure halfway through archive"))

        val request =
            ImportGamesRequest(
                username = "syncuser",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
            )

        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)
        val completedJob = waitForJob(job.id)

        assertEquals("FAILED", completedJob.status)

        val account = chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "syncuser")
        val count = if (account != null) importedArchiveRepository.findByChessAccount(account).size else 0
        assertEquals(0, count, "Failed archive must NOT be marked imported in DB")
    }

    @Test
    fun `10 Retrying a partially or previously processed archive does not create duplicate games`() {
        val gamePgn = "[Event \"Live Chess\"]\n[White \"syncuser\"]\n[Black \"opp\"]\n1. e4 e5 1-0"
        val gameMap =
            mapOf(
                "url" to "https://www.chess.com/game/live/dup100",
                "pgn" to gamePgn,
                "time_class" to "blitz",
                "rules" to "chess",
                "end_time" to 1577836800L,
                "white" to mapOf("username" to "syncuser", "result" to "win"),
                "black" to mapOf("username" to "opp", "result" to "checkmated"),
            )

        whenever(chessComClient.fetchMonthlyGames(pastMonthUrl)).thenReturn(listOf(gameMap))
        whenever(chessComClient.fetchMonthlyGames(currentMonthUrl)).thenReturn(emptyList())

        val request =
            ImportGamesRequest(
                username = "syncuser",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
            )

        // Run 1: process and save game
        val job1 = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job1.id, request)
        waitForJob(job1.id)

        // Clear imported_archive record to simulate retry/restart before completion
        importedArchiveRepository.deleteAll()

        // Run 2: re-run same past month
        val job2 = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job2.id, request)
        val job2Completed = waitForJob(job2.id)

        assertEquals("COMPLETED", job2Completed.status)
        assertEquals(0, job2Completed.gamesImported)
        assertEquals(1, job2Completed.gamesSkipped)

        val savedGames = gameRepository.findAll().filter { it.platformGameId == "https://www.chess.com/game/live/dup100" }
        assertEquals(1, savedGames.size, "Game entity must not be duplicated")
    }

    @Test
    fun `12 UTC month comparison behaves correctly around month boundaries`() {
        val utcNow = YearMonth.now(ZoneOffset.UTC)
        val pastYm = utcNow.minusMonths(1).toString()
        val currYm = utcNow.toString()
        val futureYm = utcNow.plusMonths(1).toString()

        assertTrue(pastYm < currYm, "Past month ($pastYm) must be strictly less than current month ($currYm)")
        assertFalse(currYm < currYm, "Current month ($currYm) must not be less than current month")
        assertFalse(futureYm < currYm, "Future month ($futureYm) must not be less than current month")
    }

    private fun waitForJob(jobId: java.util.UUID): AsyncJob {
        var completedJob: AsyncJob? = null
        var attempts = 0
        while (attempts < 50) {
            val currentJob = asyncJobRepository.findById(jobId).orElse(null)
            if (currentJob != null && currentJob.status in listOf("COMPLETED", "FAILED")) {
                completedJob = currentJob
                break
            }
            Thread.sleep(100)
            attempts++
        }
        assertNotNull(completedJob, "Job did not finish in time")
        return completedJob!!
    }
}
