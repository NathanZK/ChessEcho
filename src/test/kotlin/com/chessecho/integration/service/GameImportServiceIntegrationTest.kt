package com.chessecho.integration.service

import com.chessecho.domain.AsyncJob
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.UserPositionStatsRepository
import com.chessecho.service.EngineAnalysisOrchestrator
import com.chessecho.service.GameImportService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

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
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    private lateinit var userPositionStatsRepository: UserPositionStatsRepository

    @MockBean
    private lateinit var restClient: RestClient

    @MockBean
    private lateinit var engineAnalysisOrchestrator: EngineAnalysisOrchestrator

    @BeforeEach
    @Suppress("UNCHECKED_CAST")
    fun setup() {
        val requestHeadersUriSpec = mock<RestClient.RequestHeadersUriSpec<*>>()

        whenever(restClient.get()).thenReturn(requestHeadersUriSpec)
        whenever(requestHeadersUriSpec.uri(any<String>())).thenAnswer { invocation ->
            val uriString = invocation.getArgument<String>(0)
            val mockHeadersSpec = mock<RestClient.RequestHeadersSpec<*>>()
            val mockResponseSpec = mock<RestClient.ResponseSpec>()

            whenever(mockHeadersSpec.retrieve()).thenReturn(mockResponseSpec)

            if (uriString.endsWith("/archives")) {
                val archivesBody: Map<String, Any> = mapOf("archives" to listOf("https://api.chess.com/pub/player/hikaru/games/2024/01"))
                org.mockito.kotlin.doReturn(archivesBody).whenever(mockResponseSpec).body(Map::class.java)
            } else if (uriString.endsWith("/2024/01")) {
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
                        "end_time" to 1704067200L,
                        "white" to mapOf("username" to "hikaru", "result" to "win"),
                        "black" to mapOf("username" to "opponent1", "result" to "checkmated"),
                    )

                val dailyGameMap =
                    mapOf(
                        "url" to "https://www.chess.com/game/daily/20001",
                        "pgn" to game1Pgn,
                        "time_class" to "daily",
                        "end_time" to 1704067200L,
                        "white" to mapOf("username" to "hikaru", "result" to "win"),
                        "black" to mapOf("username" to "opponent2", "result" to "resigned"),
                    )

                val gamesBody: Map<String, Any> = mapOf("games" to listOf(game1Map, dailyGameMap))
                org.mockito.kotlin.doReturn(gamesBody).whenever(mockResponseSpec).body(Map::class.java)
            }

            mockHeadersSpec
        }
    }

    @AfterEach
    fun tearDown() {
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

        // Wait for @Async job to reach terminal state
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
        assertEquals("COMPLETED", completedJob.status, "Expected job status COMPLETED, got errorMessage: ${completedJob.errorMessage}")
        assertEquals(1, completedJob.gamesImported)
        assertEquals(1, completedJob.gamesSkipped, "Daily game should be safely skipped")

        // Verify account created
        val account = chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "hikaru")
        assertNotNull(account)

        // Verify position occurrences parsed and saved
        val occurrences = positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertFalse(occurrences.isEmpty(), "Position occurrences should be populated")

        // Verify UserPositionStats updated
        val stats = userPositionStatsRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertFalse(stats.isEmpty(), "UserPositionStats should be updated for account")

        // Verify orchestrator received persisted affected position IDs
        val affectedCaptor = argumentCaptor<Set<UUID>>()
        verify(engineAnalysisOrchestrator).analyzeAffectedPositions(affectedCaptor.capture())

        val affectedIds = affectedCaptor.firstValue
        assertFalse(affectedIds.isEmpty(), "Affected position IDs set should not be empty")

        // Ensure all affected IDs correspond to positions present in DB
        val positionsInDb = positionRepository.findAllById(affectedIds)
        assertEquals(affectedIds.size, positionsInDb.size)
    }

    @Test
    fun `executeImportJob logs and skips 404 archive URL and processes remaining archives`() {
        val requestHeadersUriSpec = mock<RestClient.RequestHeadersUriSpec<*>>()
        whenever(restClient.get()).thenReturn(requestHeadersUriSpec)

        whenever(requestHeadersUriSpec.uri(any<String>())).thenAnswer { invocation ->
            val uriString = invocation.getArgument<String>(0)
            val mockHeadersSpec = mock<RestClient.RequestHeadersSpec<*>>()
            val mockResponseSpec = mock<RestClient.ResponseSpec>()

            whenever(mockHeadersSpec.retrieve()).thenReturn(mockResponseSpec)

            if (uriString.endsWith("/archives")) {
                val archivesBody: Map<String, Any> =
                    mapOf(
                        "archives" to
                            listOf(
                                "https://api.chess.com/pub/player/test404/games/2024/01",
                                "https://api.chess.com/pub/player/test404/games/2024/02",
                            ),
                    )
                org.mockito.kotlin.doReturn(archivesBody).whenever(mockResponseSpec).body(Map::class.java)
            } else if (uriString.endsWith("/2024/01")) {
                // Return 404 for first archive
                whenever(mockResponseSpec.body(Map::class.java)).thenThrow(
                    org.springframework.web.client.HttpClientErrorException.NotFound.create(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "Not Found",
                        org.springframework.http.HttpHeaders.EMPTY,
                        ByteArray(0),
                        null,
                    ),
                )
            } else if (uriString.endsWith("/2024/02")) {
                val game1Pgn = "1. e4 e5 2. Nf3 Nc6 3. Bb5 1-0"
                val game1Map =
                    mapOf(
                        "url" to "https://www.chess.com/game/live/99001",
                        "pgn" to game1Pgn,
                        "time_class" to "blitz",
                        "end_time" to 1704067200L,
                        "white" to mapOf("username" to "test404", "result" to "win"),
                        "black" to mapOf("username" to "opp1", "result" to "checkmated"),
                    )
                val gamesBody: Map<String, Any> = mapOf("games" to listOf(game1Map))
                org.mockito.kotlin.doReturn(gamesBody).whenever(mockResponseSpec).body(Map::class.java)
            }
            mockHeadersSpec
        }

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

        val account = chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "test404")
        assertNotNull(account)

        val stats = userPositionStatsRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertFalse(stats.isEmpty(), "UserPositionStats should still be updated after 404 archive skipping")

        verify(engineAnalysisOrchestrator).analyzeAffectedPositions(any())
    }

    @Test
    fun `executeImportJob fails when restClient throws genuine 500 Internal Server Error`() {
        val requestHeadersUriSpec = mock<RestClient.RequestHeadersUriSpec<*>>()
        whenever(restClient.get()).thenReturn(requestHeadersUriSpec)

        whenever(requestHeadersUriSpec.uri(any<String>())).thenAnswer { invocation ->
            val uriString = invocation.getArgument<String>(0)
            val mockHeadersSpec = mock<RestClient.RequestHeadersSpec<*>>()
            val mockResponseSpec = mock<RestClient.ResponseSpec>()

            whenever(mockHeadersSpec.retrieve()).thenReturn(mockResponseSpec)

            if (uriString.endsWith("/archives")) {
                val archivesBody: Map<String, Any> = mapOf("archives" to listOf("https://api.chess.com/pub/player/test500/games/2024/01"))
                org.mockito.kotlin.doReturn(archivesBody).whenever(mockResponseSpec).body(Map::class.java)
            } else if (uriString.endsWith("/2024/01")) {
                whenever(mockResponseSpec.body(Map::class.java)).thenThrow(
                    org.springframework.web.client.HttpServerErrorException.create(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "Internal Server Error",
                        org.springframework.http.HttpHeaders.EMPTY,
                        ByteArray(0),
                        null,
                    ),
                )
            }
            mockHeadersSpec
        }

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
        val requestHeadersUriSpec = mock<RestClient.RequestHeadersUriSpec<*>>()
        whenever(restClient.get()).thenReturn(requestHeadersUriSpec)

        whenever(requestHeadersUriSpec.uri(any<String>())).thenAnswer { invocation ->
            val uriString = invocation.getArgument<String>(0)
            val mockHeadersSpec = mock<RestClient.RequestHeadersSpec<*>>()
            val mockResponseSpec = mock<RestClient.ResponseSpec>()
            whenever(mockHeadersSpec.retrieve()).thenReturn(mockResponseSpec)

            if (uriString.endsWith("/archives")) {
                val archivesBody: Map<String, Any> = mapOf("archives" to listOf("https://api.chess.com/pub/player/hikaru/games/2024/02"))
                org.mockito.kotlin.doReturn(archivesBody).whenever(mockResponseSpec).body(Map::class.java)
            } else if (uriString.endsWith("/2024/02")) {
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

                val gamesBody: Map<String, Any> = mapOf("games" to listOf(standardGameMap, chess960GameMap))
                org.mockito.kotlin.doReturn(gamesBody).whenever(mockResponseSpec).body(Map::class.java)
            }

            mockHeadersSpec
        }

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
        assertEquals(0, completedJob.gamesSkipped, "Non-standard variant games should be filtered out without incrementing gamesSkipped")

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
}
