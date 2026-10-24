package com.chessecho.integration.service

import com.chessecho.domain.AsyncJob
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.dto.ImportGamesRequest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GameImportServiceAnalysisIntegrationTest : GameImportServiceBaseIntegrationTest() {
    @Test
    fun `executeImportJob imports games, updates UserPositionStats, and triggers engine analysis`() {
        val request =
            ImportGamesRequest(
                username = "hikaru",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
            )

        val job = gameImportService.createImportJob(request)
        assertEquals("QUEUED", job.status)

        gameImportService.executeImportJob(job.id, request)

        var completedJob: AsyncJob? = null
        var attempts = 0
        while (attempts < 50) {
            val currentJob = asyncJobRepository.findById(job.id).orElse(null)
            if (currentJob != null && currentJob.status in listOf("COMPLETED", "FAILED")) {
                completedJob = currentJob
                break
            }

            Thread.sleep(100)
            attempts++
        }

        assertNotNull(completedJob, "Import job did not complete within timeout")
        assertEquals(1, completedJob.gamesImported)
        assertEquals(0, completedJob.gamesSkipped, "No duplicate games on fresh import")
        assertEquals(2, completedJob.gamesProcessed)

        val account = chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "hikaru")
        assertNotNull(account)

        val occurrences = positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertFalse(occurrences.isEmpty(), "Position occurrences should be populated")

        val stats = userPositionStatsRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertFalse(stats.isEmpty(), "UserPositionStats should be updated for account")

        var analysisJob: AsyncJob? = null
        attempts = 0
        while (attempts < 50) {
            val currentJob = asyncJobRepository.findById(job.id).orElse(null)
            if (currentJob != null && currentJob.analysisStatus in listOf("COMPLETED", "FAILED")) {
                analysisJob = currentJob
                break
            }
            Thread.sleep(100)
            attempts++
        }
        assertNotNull(analysisJob, "Analysis did not reach a terminal state within timeout")

        val affectedCaptor = argumentCaptor<Set<UUID>>()
        verify(engineAnalysisOrchestrator).analyzeAffectedPositions(affectedCaptor.capture())

        val affectedIds = affectedCaptor.firstValue
        assertFalse(affectedIds.isEmpty(), "Affected position IDs set should not be empty")

        val positionsInDb = positionRepository.findAllById(affectedIds)
        assertEquals(affectedIds.size, positionsInDb.size)
    }

    @Test
    fun `created import jobs expose durable analysis MultiPV configuration`() {
        val request =
            objectMapper.readValue(
                """{"username":"multi-pv-player","platform":"CHESS_COM","timeControls":["BLITZ"],"playerColor":"BOTH","multiPv":1}""",
                ImportGamesRequest::class.java,
            )

        val job = gameImportService.createImportJob(request)

        val analysisMultiPv =
            requireNotNull(AsyncJob::class.memberProperties.singleOrNull { it.name == "analysisMultiPv" }) {
                "AsyncJob must retain the requested MultiPV value across repository reload"
            }
        assertEquals(1, analysisMultiPv.get(job))
        val reloaded = assertNotNull(asyncJobRepository.findById(job.id).orElse(null))
        assertEquals(1, analysisMultiPv.get(reloaded))
    }

    @Test
    fun `Black import preserves raw White result contract and persists Black occurrences`() {
        val archiveUrl = "https://api.chess.com/pub/player/blackaccount/games/2024/03"
        val pgn =
            """
            [Event "Live Chess"]
            [Site "Chess.com"]
            [Date "2024.03.01"]
            [White "white-opponent"]
            [Black "blackaccount"]
            [Result "1-0"]

            1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 1-0
            """.trimIndent()
        whenever(chessComClient.fetchArchiveUrls("blackaccount")).thenReturn(listOf(archiveUrl))
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(
            listOf(
                mapOf(
                    "url" to "https://www.chess.com/game/live/black-contract",
                    "pgn" to pgn,
                    "time_class" to "blitz",
                    "rules" to "chess",
                    "end_time" to 1709251200L,
                    "white" to mapOf("username" to "white-opponent", "result" to "win"),
                    "black" to mapOf("username" to "blackaccount", "result" to "checkmated"),
                ),
            ),
        )
        val request =
            ImportGamesRequest(
                username = "blackaccount",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BLACK,
            )

        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)
        val completed = awaitTerminalJob(job.id)

        assertEquals("COMPLETED", completed.status, completed.errorMessage)
        assertEquals(1, completed.gamesImported)
        val persisted =
            gameRepository.findAll().single {
                it.platformGameId == "https://www.chess.com/game/live/black-contract"
            }
        assertEquals("win", persisted.result, "Game.result must remain the raw White-side Chess.com token")
        assertEquals("white-opponent", persisted.whiteUsername)
        assertEquals("blackaccount", persisted.blackUsername)

        val account = chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "blackaccount")
        assertNotNull(account)
        val blackOccurrences = positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, "BLACK")
        assertFalse(blackOccurrences.isEmpty())
        assertEquals(0, positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE").size)
        assertEquals(true, blackOccurrences.all { it.game.id == persisted.id && it.playerColor == "BLACK" })
    }

    @Test
    fun `import is completed while slow analysis remains in progress`() {
        val analysisStarted = CountDownLatch(1)
        val releaseAnalysis = CountDownLatch(1)
        doAnswer {
            analysisStarted.countDown()
            releaseAnalysis.await(5, TimeUnit.SECONDS)
            null
        }.whenever(engineAnalysisOrchestrator).analyzeAffectedPositions(any())

        val request =
            ImportGamesRequest(
                username = "hikaru",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
            )

        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)

        try {
            assertTrue(analysisStarted.await(5, TimeUnit.SECONDS), "Analysis did not start within timeout")

            var completedDuringAnalysis: AsyncJob? = null
            var attempts = 0
            while (attempts < 50) {
                val currentJob = asyncJobRepository.findById(job.id).orElse(null)
                if (currentJob != null && currentJob.status == "COMPLETED" && currentJob.analysisStatus == "ANALYZING") {
                    completedDuringAnalysis = currentJob
                    break
                }
                Thread.sleep(100)
                attempts++
            }

            assertNotNull(completedDuringAnalysis, "Import did not complete before the blocked analysis returned")
        } finally {
            releaseAnalysis.countDown()
        }
    }

    @Test
    fun `analysis failure status is recorded separately after import completes`() {
        doThrow(IllegalStateException())
            .whenever(engineAnalysisOrchestrator).analyzeAffectedPositions(any())

        val request =
            ImportGamesRequest(
                username = "hikaru",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
            )

        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)

        var terminalAnalysisJob: AsyncJob? = null
        var attempts = 0
        while (attempts < 50) {
            val currentJob = asyncJobRepository.findById(job.id).orElse(null)
            if (currentJob != null && currentJob.analysisStatus in listOf("COMPLETED", "FAILED")) {
                terminalAnalysisJob = currentJob
                break
            }
            Thread.sleep(100)
            attempts++
        }

        assertNotNull(terminalAnalysisJob, "Analysis did not reach a terminal state within timeout")
        assertEquals("COMPLETED", terminalAnalysisJob.status)
        assertEquals(null, terminalAnalysisJob.errorMessage)
        assertEquals("FAILED", terminalAnalysisJob.analysisStatus)
    }
}
