package com.chessecho.integration.service

import com.chessecho.domain.AsyncJob
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.dto.ImportGamesRequest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class GameImportServiceArchiveIntegrationTest : GameImportServiceBaseIntegrationTest() {
    @Test
    fun `executeImportJob logs and skips 404 archive URL and processes remaining archives`() {
        whenever(chessComClient.fetchArchiveUrls("test404")).thenReturn(
            listOf(
                "https://api.chess.com/pub/player/test404/games/2024/01",
                "https://api.chess.com/pub/player/test404/games/2024/02",
            ),
        )
        whenever(chessComClient.fetchMonthlyGames("https://api.chess.com/pub/player/test404/games/2024/01")).thenReturn(null)

        val game1Pgn = "1. e4 e5 2. Nf3 Nc6 3. Bb5 1-0"
        val game1Map =
            mapOf(
                "url" to "https://www.chess.com/game/live/99001",
                "pgn" to game1Pgn,
                "time_class" to "blitz",
                "rules" to "chess",
                "end_time" to 1704067200L,
                "white" to mapOf("username" to "test404", "result" to "win"),
                "black" to mapOf("username" to "opp1", "result" to "checkmated"),
            )
        whenever(chessComClient.fetchMonthlyGames("https://api.chess.com/pub/player/test404/games/2024/02")).thenReturn(listOf(game1Map))

        val request =
            ImportGamesRequest(
                username = "test404",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
            )

        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)

        val completedJob = awaitTerminalJob(job.id)
        assertEquals(
            "COMPLETED",
            completedJob.status,
            "Expected COMPLETED status despite 404 archive, got error: ${completedJob.errorMessage}",
        )
        assertEquals(1, completedJob.gamesImported)
        assertEquals(0, completedJob.gamesSkipped)
        assertEquals(1, completedJob.gamesProcessed, "The null archive must contribute zero processed entries")

        val account = chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "test404")
        assertNotNull(account)

        val stats = userPositionStatsRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertFalse(stats.isEmpty(), "UserPositionStats should still be updated after 404 archive skipping")

        val analysisJob = awaitAnalysisJob(job.id)
        verify(engineAnalysisOrchestrator).analyzeAffectedPositions(any())
    }

    @Test
    fun `executeImportJob fails when chessComClient throws exception`() {
        whenever(chessComClient.fetchArchiveUrls("test500")).thenReturn(
            listOf("https://api.chess.com/pub/player/test500/games/2024/01"),
        )
        whenever(chessComClient.fetchMonthlyGames("https://api.chess.com/pub/player/test500/games/2024/01")).thenThrow(
            org.springframework.web.client.HttpServerErrorException.create(
                org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                org.springframework.http.HttpHeaders.EMPTY,
                ByteArray(0),
                null,
            ),
        )

        val request =
            ImportGamesRequest(
                username = "test500",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
            )

        val job = gameImportService.createImportJob(request)
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

        assertNotNull(completedJob)
        assertEquals("FAILED", completedJob.status, "Expected FAILED status on 500 error")
    }

    @Test
    fun `executeImportJob skips chess960 variant games and imports standard games`() {
        whenever(chessComClient.fetchArchiveUrls("variantuser")).thenReturn(
            listOf("https://api.chess.com/pub/player/variantuser/games/2024/02"),
        )
        whenever(chessComClient.fetchMonthlyGames("https://api.chess.com/pub/player/variantuser/games/2024/02")).thenReturn(
            listOf(standardVariantGameMap(), chess960VariantGameMap()),
        )

        val request =
            ImportGamesRequest(
                username = "variantuser",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
            )

        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)

        val completedJob = awaitTerminalJob(job.id)
        assertEquals("COMPLETED", completedJob.status)
        assertEquals(1, completedJob.gamesImported)
        assertEquals(0, completedJob.gamesSkipped, "Non-standard variant games should be ignored without incrementing gamesSkipped")

        // Clear imported_archive record to simulate re-running the past month
        importedArchiveRepository.deleteAll()

        // Re-run import for the same archive: the already-imported standard game MUST increment gamesSkipped
        val secondJob = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(secondJob.id, request)

        val completedSecondJob = awaitTerminalJob(secondJob.id)
        assertEquals("COMPLETED", completedSecondJob.status)
        assertEquals(0, completedSecondJob.gamesImported)
        assertEquals(1, completedSecondJob.gamesSkipped, "Already imported standard game should increment gamesSkipped")

        val savedGames = gameRepository.findAll()
        val userGames = savedGames.filter { it.whiteUsername == "variantuser" }
        assertEquals(1, userGames.size)
        assertEquals("https://www.chess.com/game/live/standard100", userGames[0].platformGameId)

        val occurrences = positionOccurrenceRepository.findAll()
        val chess960Occurrences = occurrences.filter { it.game.platformGameId.contains("chess960_200") }
        assertEquals(0, chess960Occurrences.size)
    }

    private fun standardVariantGameMap(): Map<String, Any> {
        val standardGamePgn =
            """
            [Event "Live Chess"]
            [Site "Chess.com"]
            [Date "2024.02.01"]
            [White "variantuser"]
            [Black "opponent1"]
            [Result "1-0"]

            1. e4 e5 2. Nf3 Nc6 3. Bb5 1-0
            """.trimIndent()
        return mapOf(
            "url" to "https://www.chess.com/game/live/standard100",
            "pgn" to standardGamePgn,
            "time_class" to "blitz",
            "rules" to "chess",
            "end_time" to 1704067200L,
            "white" to mapOf("username" to "variantuser", "result" to "win"),
            "black" to mapOf("username" to "opponent1", "result" to "checkmated"),
        )
    }

    private fun chess960VariantGameMap(): Map<String, Any> {
        val chess960Pgn =
            """
            [Event "Live Chess - Chess960"]
            [Site "Chess.com"]
            [Date "2024.02.01"]
            [White "variantuser"]
            [Black "opponent2"]
            [Result "0-1"]
            [Variant "Chess960"]
            [SetUp "1"]
            [FEN "rkrbbnnq/pppppppp/8/8/8/8/PPPPPPPP/RKRBBNNQ w CAca - 0 1"]

            1. d4 d5 2. c4 dxc4 3. Rxc4 g5 0-1
            """.trimIndent()
        return mapOf(
            "url" to "https://www.chess.com/game/live/chess960_200",
            "pgn" to chess960Pgn,
            "time_class" to "blitz",
            "rules" to "chess960",
            "end_time" to 1704067200L,
            "white" to mapOf("username" to "variantuser", "result" to "resigned"),
            "black" to mapOf("username" to "opponent2", "result" to "win"),
        )
    }

    @Test
    fun `non matching games are ignored and do not increment gamesSkipped`() {
        whenever(chessComClient.fetchArchiveUrls("sumuser")).thenReturn(
            listOf("https://api.chess.com/pub/player/sumuser/games/2024/01"),
        )

        val rapidGameMap =
            mapOf(
                "url" to "https://www.chess.com/game/live/sum101",
                "pgn" to "[Event \"Live Chess\"]\n1. e4 e5 2. Nf3 Nc6",
                "time_class" to "rapid",
                "rules" to "chess",
                "end_time" to 1704067200L,
                "white" to mapOf("username" to "sumuser", "result" to "win"),
                "black" to mapOf("username" to "opponent1", "result" to "checkmated"),
            )
        val blitzGameMap =
            mapOf(
                "url" to "https://www.chess.com/game/live/sum102",
                "pgn" to "[Event \"Live Chess\"]\n1. d4 d5",
                "time_class" to "blitz",
                "rules" to "chess",
                "end_time" to 1704067300L,
                "white" to mapOf("username" to "sumuser", "result" to "win"),
                "black" to mapOf("username" to "opponent2", "result" to "resigned"),
            )

        whenever(chessComClient.fetchMonthlyGames("https://api.chess.com/pub/player/sumuser/games/2024/01")).thenReturn(
            listOf(rapidGameMap, blitzGameMap),
        )

        val request =
            ImportGamesRequest(
                username = "sumuser",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.RAPID),
                playerColor = PlayerColor.BOTH,
            )

        val job1 = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job1.id, request)

        val completedJob1 = awaitTerminalJob(job1.id)
        assertEquals("COMPLETED", completedJob1.status)
        assertEquals(1, completedJob1.gamesImported)
        assertEquals(0, completedJob1.gamesSkipped)
        assertEquals(2, completedJob1.gamesProcessed)

        // Clear imported_archive record to force re-evaluation of month games
        importedArchiveRepository.deleteAll()

        val job2 = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job2.id, request)

        val completedJob2 = awaitTerminalJob(job2.id)
        assertEquals("COMPLETED", completedJob2.status)
        assertEquals(0, completedJob2.gamesImported)
        assertEquals(1, completedJob2.gamesSkipped)
        assertEquals(2, completedJob2.gamesProcessed)
    }

    @Test
    fun `duplicate entries within the same archive batch increment gamesSkipped`() {
        val archiveUrl = "https://api.chess.com/pub/player/batchdupuser/games/2024/01"
        whenever(chessComClient.fetchArchiveUrls("batchdupuser")).thenReturn(listOf(archiveUrl))

        val rapidGameMap =
            mapOf(
                "url" to "https://www.chess.com/game/live/batchdup1",
                "pgn" to "[Event \"Live Chess\"]\n1. e4 e5 2. Nf3 Nc6",
                "time_class" to "rapid",
                "rules" to "chess",
                "end_time" to 1704067200L,
                "white" to mapOf("username" to "batchdupuser", "result" to "win"),
                "black" to mapOf("username" to "opponent1", "result" to "checkmated"),
            )

        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(listOf(rapidGameMap, rapidGameMap))

        val request =
            ImportGamesRequest(
                username = "batchdupuser",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.RAPID),
                playerColor = PlayerColor.BOTH,
            )

        val job = gameImportService.createImportJob(request)
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

        assertNotNull(completedJob)
        assertEquals("COMPLETED", completedJob.status)
        assertEquals(1, completedJob.gamesImported)
        assertEquals(1, completedJob.gamesSkipped, "Duplicate URL in same batch should increment gamesSkipped")
        assertEquals(2, completedJob.gamesProcessed)

        val savedGames = gameRepository.findAll().filter { it.platformGameId == "https://www.chess.com/game/live/batchdup1" }
        assertEquals(1, savedGames.size, "Duplicate entry in batch must not create duplicate Game entity")
    }
}
