package com.chessecho.integration.service

import com.chessecho.domain.AsyncJob
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ArchiveDerivedProcessingRepository
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.ImportedArchiveRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.UserPositionStatsRepository
import com.chessecho.repository.UserPositionWeaknessRepository
import com.chessecho.service.ChessComClient
import com.chessecho.service.EngineAnalysisOrchestrator
import com.chessecho.service.GameImportService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class GameImportServiceBaseIntegrationTest {
    @Autowired
    protected lateinit var gameImportService: GameImportService

    @Autowired
    protected lateinit var asyncJobRepository: AsyncJobRepository

    @Autowired
    protected lateinit var gameRepository: GameRepository

    @Autowired
    protected lateinit var appUserRepository: AppUserRepository

    @Autowired
    protected lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    protected lateinit var importedArchiveRepository: ImportedArchiveRepository

    @Autowired
    protected lateinit var archiveDerivedProcessingRepository: ArchiveDerivedProcessingRepository

    @Autowired
    protected lateinit var positionRepository: PositionRepository

    @Autowired
    protected lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    protected lateinit var userPositionStatsRepository: UserPositionStatsRepository

    @Autowired
    protected lateinit var userPositionWeaknessRepository: UserPositionWeaknessRepository

    @Autowired
    protected lateinit var puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository

    @Autowired
    protected lateinit var engineAnalysisRepository: com.chessecho.repository.EngineAnalysisRepository

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    protected lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    @MockBean
    protected lateinit var chessComClient: ChessComClient

    @MockBean
    protected lateinit var engineAnalysisOrchestrator: EngineAnalysisOrchestrator

    @SpyBean
    protected lateinit var gameParserService: com.chessecho.service.GameParserService

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
        puzzleSchedulingEventRepository.deleteAll()
        engineAnalysisRepository.deleteAll()
        userPositionWeaknessRepository.deleteAll()
        userPositionStatsRepository.deleteAll()
        positionOccurrenceRepository.deleteAll()
        positionRepository.deleteAll()
        gameRepository.deleteAll()
        archiveDerivedProcessingRepository.deleteAll()
        importedArchiveRepository.deleteAll()
        asyncJobRepository.deleteAll()
        chessAccountRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    protected fun markJobAbandoned(jobId: UUID) {
        transactionTemplate.executeWithoutResult {
            val stale = asyncJobRepository.findAnyByIdForUpdate(jobId) ?: error("missing job")
            stale.status = "PROCESSING"
            stale.workerToken = UUID.randomUUID()
            stale.leaseExpiresAt = java.time.Instant.now().minusSeconds(60)
            stale.updatedAt = java.time.Instant.now().minusSeconds(60)
            asyncJobRepository.save(stale)
        }
    }

    protected fun persistImportProgressMethod(): java.lang.reflect.Method =
        GameImportService::class.java.getDeclaredMethod(
            "persistImportProgress",
            Class.forName("com.chessecho.service.GameImportService\$AsyncJobClaim"),
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }

    protected fun asyncJobClaim(
        job: AsyncJob,
        workerToken: UUID,
    ): Any {
        val constructor =
            Class.forName("com.chessecho.service.GameImportService\$AsyncJobClaim").declaredConstructors.single()
        constructor.isAccessible = true
        return constructor.newInstance(job, workerToken)
    }

    protected fun importRequest(username: String): ImportGamesRequest =
        ImportGamesRequest(
            username = "hikaru",
            platform = Platform.CHESS_COM,
            timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
            playerColor = PlayerColor.BOTH,
        )

    protected fun waitForJob(jobId: UUID): AsyncJob {
        repeat(50) {
            val job = asyncJobRepository.findById(jobId).orElse(null)
            if (job != null && job.status in listOf("COMPLETED", "FAILED")) {
                return job
            }
            Thread.sleep(100)
        }
        throw AssertionError("Import job did not complete within timeout")
    }

    protected fun importGame(
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

    protected fun awaitTerminalJob(jobId: UUID): AsyncJob {
        repeat(50) {
            val current = asyncJobRepository.findById(jobId).orElse(null)
            if (current != null && current.status in listOf("COMPLETED", "FAILED")) {
                return current
            }
            Thread.sleep(100)
        }
        error("Import job $jobId did not complete within timeout")
    }

    protected fun awaitAnalysisJob(jobId: UUID): AsyncJob {
        repeat(50) {
            val current = asyncJobRepository.findById(jobId).orElse(null)
            if (current != null && current.analysisStatus in listOf("COMPLETED", "FAILED")) {
                return current
            }
            Thread.sleep(100)
        }
        error("Analysis job $jobId did not complete within timeout")
    }
}
