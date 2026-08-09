package com.chessecho.integration.service

import com.chessecho.domain.AsyncJob
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

                val gamesBody: Map<String, Any> = mapOf("games" to listOf(game1Map))
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
                platform = "CHESS_COM",
                timeControls = listOf("blitz"),
                playerColor = "both",
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
        assertEquals(0, completedJob.gamesSkipped)

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
}
