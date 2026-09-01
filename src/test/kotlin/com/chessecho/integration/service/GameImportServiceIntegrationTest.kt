package com.chessecho.integration.service

import com.chessecho.domain.AsyncJob
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.ImportedArchiveRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.UserPositionStatsRepository
import com.chessecho.service.ChessComClient
import com.chessecho.service.EngineAnalysisOrchestrator
import com.chessecho.service.GameImportService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GameImportServiceIntegrationTest {
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

    @BeforeEach
    fun setup() {
        whenever(chessComClient.fetchArchiveUrls(any())).thenAnswer { invocation ->
            val username = invocation.getArgument<String>(0)
            listOf("https://api.chess.com/pub/player/$username/games/2024/01")
        }

        whenever(chessComClient.fetchMonthlyGames(any())).thenAnswer { invocation ->
            val uriString = invocation.getArgument<String>(0)
            if (uriString.endsWith("/2024/01")) {
                val game1Pgn =
                    """
                    [Event "Live Chess"]
                    [Site "Chess.com"]
                    [Date "2024.01.01"]
                    [White "hikaru"]
                    [Black "opponent1"]
                    [Result "1-0"]

                    1. e4 e5 2. Nf3 Nc6 3. Bb5 1-0
                    """.trimIndent()

                val game1Map =
                    mapOf(
                        "url" to "https://www.chess.com/game/live/10001",
                        "pgn" to game1Pgn,
                        "time_class" to "blitz",
                        "rules" to "chess",
                        "end_time" to 1704067200L,
                        "white" to mapOf("username" to "hikaru", "result" to "win"),
                        "black" to mapOf("username" to "opponent1", "result" to "checkmated"),
                    )

                val dailyGameMap =
                    mapOf(
                        "url" to "https://www.chess.com/game/daily/20001",
                        "pgn" to game1Pgn,
                        "time_class" to "daily",
                        "rules" to "chess",
                        "end_time" to 1704067200L,
                        "white" to mapOf("username" to "hikaru", "result" to "win"),
                        "black" to mapOf("username" to "opponent2", "result" to "resigned"),
                    )

                listOf(game1Map, dailyGameMap)
            } else {
                null
            }
        }
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

        verify(engineAnalysisOrchestrator).analyzeAffectedPositions(any())
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

    @Test
    fun `persistImportProgress records non-final counter checkpoints`() {
        val job =
            asyncJobRepository.save(
                AsyncJob(
                    username = "checkpoint-user",
                    platform = "CHESS_COM",
                    status = "PROCESSING",
                ),
            )
        val targetService = org.springframework.test.util.AopTestUtils.getUltimateTargetObject<GameImportService>(gameImportService)
        val method =
            GameImportService::class.java.getDeclaredMethod(
                "persistImportProgress",
                AsyncJob::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true

        method.invoke(targetService, job, 3, 1, 6)

        val checkpoint = asyncJobRepository.findById(job.id).orElseThrow()
        assertEquals("PROCESSING", checkpoint.status)
        assertEquals(3, checkpoint.gamesImported)
        assertEquals(1, checkpoint.gamesSkipped)
        assertEquals(6, checkpoint.gamesProcessed)
    }

    @Test
    fun `gamesProcessed checkpoints and aggregates every examined entry once across archives`() {
        val firstArchive = "https://api.chess.com/pub/player/checkpointuser/games/2024/01"
        val secondArchive = "https://api.chess.com/pub/player/checkpointuser/games/2024/02"
        whenever(chessComClient.fetchArchiveUrls("checkpointuser")).thenReturn(listOf(firstArchive, secondArchive))

        val firstAccepted =
            importGame(
                url = "https://www.chess.com/game/live/checkpoint-1",
                username = "checkpointuser",
            )
        val excludedDaily =
            importGame(
                url = "https://www.chess.com/game/daily/checkpoint-2",
                username = "checkpointuser",
                timeClass = "daily",
            )
        val secondAccepted =
            importGame(
                url = "https://www.chess.com/game/live/checkpoint-3",
                username = "checkpointuser",
            )
        val malformedWithoutParticipants =
            mapOf<String, Any>(
                "url" to "https://www.chess.com/game/live/checkpoint-4",
                "pgn" to "[Event \"Live Chess\"]\n1. e4 e5",
                "time_class" to "blitz",
                "rules" to "chess",
            )
        val malformedWithoutUrl =
            mapOf<String, Any>(
                "pgn" to "[Event \"Live Chess\"]\n1. d4 d5",
                "time_class" to "blitz",
                "rules" to "chess",
                "white" to mapOf("username" to "checkpointuser", "result" to "win"),
                "black" to mapOf("username" to "opponent", "result" to "checkmated"),
            )
        whenever(chessComClient.fetchMonthlyGames(firstArchive)).thenReturn(listOf(firstAccepted, excludedDaily))

        val secondArchiveRequested = CountDownLatch(1)
        val releaseSecondArchive = CountDownLatch(1)
        doAnswer {
            secondArchiveRequested.countDown()
            assertTrue(releaseSecondArchive.await(5, TimeUnit.SECONDS), "Second archive was not released")
            listOf(secondAccepted, malformedWithoutParticipants, malformedWithoutUrl)
        }.whenever(chessComClient).fetchMonthlyGames(secondArchive)

        val request =
            ImportGamesRequest(
                username = "checkpointuser",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
            )
        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)

        try {
            assertTrue(secondArchiveRequested.await(5, TimeUnit.SECONDS), "Second archive request did not start")
            val checkpoint = asyncJobRepository.findById(job.id).orElseThrow()
            assertEquals("PROCESSING", checkpoint.status)
            assertEquals(1, checkpoint.gamesImported)
            assertEquals(0, checkpoint.gamesSkipped)
            assertEquals(2, checkpoint.gamesProcessed)
        } finally {
            releaseSecondArchive.countDown()
        }

        val completedJob = waitForJob(job.id)
        assertEquals("COMPLETED", completedJob.status)
        assertEquals(2, completedJob.gamesImported)
        assertEquals(0, completedJob.gamesSkipped)
        assertEquals(5, completedJob.gamesProcessed)
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
    @org.springframework.transaction.annotation.Transactional
    fun `updateUserPositionStats handles empty set, sub-1000 set, and multi-batch over 1000 positions correctly`() {
        val appUser = appUserRepository.save(com.chessecho.domain.AppUser(email = "batch@test.com"))
        val account =
            chessAccountRepository.save(
                com.chessecho.domain.ChessAccount(user = appUser, platform = "CHESS_COM", username = "batchuser"),
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
        val smallPositions =
            (1..50).map { idx ->
                positionRepository.save(com.chessecho.domain.Position(hash = "small_hash_$idx", fen = "fen_small_$idx"))
            }
        smallPositions.forEach { pos ->
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

        val smallIds = smallPositions.map { it.id }.toSet()
        method.invoke(targetService, account, smallIds)

        stats = userPositionStatsRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertEquals(50, stats.size)
        stats.forEach { assertEquals(1, it.timesReached) }

        // 3. Multi-batch (> 1000 positions: 1050 positions)
        userPositionStatsRepository.deleteAll()
        positionOccurrenceRepository.deleteAll()
        positionRepository.deleteAll()

        val largePositions =
            (1..1050).map { idx ->
                positionRepository.save(com.chessecho.domain.Position(hash = "large_hash_$idx", fen = "fen_large_$idx"))
            }
        largePositions.forEach { pos ->
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

        val largeIds = largePositions.map { it.id }.toSet()
        method.invoke(targetService, account, largeIds)

        stats = userPositionStatsRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertEquals(1050, stats.size, "Expected exactly 1050 UserPositionStats across 2 batches")
        stats.forEach { assertEquals(1, it.timesReached) }
    }

    @Test
    fun `executeImportJob skips chess960 variant games and imports standard games`() {
        whenever(chessComClient.fetchArchiveUrls("variantuser")).thenReturn(
            listOf("https://api.chess.com/pub/player/variantuser/games/2024/02"),
        )

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

        val standardGameMap =
            mapOf(
                "url" to "https://www.chess.com/game/live/standard100",
                "pgn" to standardGamePgn,
                "time_class" to "blitz",
                "rules" to "chess",
                "end_time" to 1704067200L,
                "white" to mapOf("username" to "variantuser", "result" to "win"),
                "black" to mapOf("username" to "opponent1", "result" to "checkmated"),
            )

        val chess960GameMap =
            mapOf(
                "url" to "https://www.chess.com/game/live/chess960_200",
                "pgn" to chess960Pgn,
                "time_class" to "blitz",
                "rules" to "chess960",
                "end_time" to 1704067200L,
                "white" to mapOf("username" to "variantuser", "result" to "resigned"),
                "black" to mapOf("username" to "opponent2", "result" to "win"),
            )

        whenever(chessComClient.fetchMonthlyGames("https://api.chess.com/pub/player/variantuser/games/2024/02")).thenReturn(
            listOf(standardGameMap, chess960GameMap),
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
        assertEquals(0, completedJob.gamesSkipped, "Non-standard variant games should be ignored without incrementing gamesSkipped")

        // Clear imported_archive record to simulate re-running the past month
        importedArchiveRepository.deleteAll()

        // Re-run import for the same archive: the already-imported standard game MUST increment gamesSkipped
        val secondJob = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(secondJob.id, request)

        var completedSecondJob: AsyncJob? = null
        attempts = 0
        while (attempts < 50) {
            val currentJob = asyncJobRepository.findById(secondJob.id).orElse(null)
            if (currentJob != null && currentJob.status in listOf("COMPLETED", "FAILED")) {
                completedSecondJob = currentJob
                break
            }
            Thread.sleep(100)
            attempts++
        }

        assertNotNull(completedSecondJob)
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

        var completedJob1: AsyncJob? = null
        var attempts = 0
        while (attempts < 50) {
            val currentJob = asyncJobRepository.findById(job1.id).orElse(null)
            if (currentJob != null && currentJob.status in listOf("COMPLETED", "FAILED")) {
                completedJob1 = currentJob
                break
            }
            Thread.sleep(100)
            attempts++
        }

        assertNotNull(completedJob1)
        assertEquals("COMPLETED", completedJob1.status)
        assertEquals(1, completedJob1.gamesImported)
        assertEquals(0, completedJob1.gamesSkipped)
        assertEquals(2, completedJob1.gamesProcessed)

        // Clear imported_archive record to force re-evaluation of month games
        importedArchiveRepository.deleteAll()

        val job2 = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job2.id, request)

        var completedJob2: AsyncJob? = null
        attempts = 0
        while (attempts < 50) {
            val currentJob = asyncJobRepository.findById(job2.id).orElse(null)
            if (currentJob != null && currentJob.status in listOf("COMPLETED", "FAILED")) {
                completedJob2 = currentJob
                break
            }
            Thread.sleep(100)
            attempts++
        }

        assertNotNull(completedJob2)
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

    private fun waitForJob(jobId: UUID): AsyncJob {
        repeat(50) {
            val job = asyncJobRepository.findById(jobId).orElse(null)
            if (job != null && job.status in listOf("COMPLETED", "FAILED")) {
                return job
            }
            Thread.sleep(100)
        }
        throw AssertionError("Import job did not complete within timeout")
    }

    private fun importGame(
        url: String,
        username: String,
        timeClass: String = "blitz",
    ): Map<String, Any> =
        mapOf(
            "url" to url,
            "pgn" to "[Event \"Live Chess\"]\n[White \"$username\"]\n[Black \"opponent\"]\n1. e4 e5 1-0",
            "time_class" to timeClass,
            "rules" to "chess",
            "end_time" to 1704067200L,
            "white" to mapOf("username" to username, "result" to "win"),
            "black" to mapOf("username" to "opponent", "result" to "checkmated"),
        )
}
