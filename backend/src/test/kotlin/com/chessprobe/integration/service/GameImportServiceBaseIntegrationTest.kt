package com.chessprobe.integration.service

import com.chessprobe.AbstractIntegrationTest
import com.chessprobe.domain.AsyncJob
import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.Platform
import com.chessprobe.domain.PlayerColor
import com.chessprobe.dto.ImportGamesRequest
import com.chessprobe.repository.ArchiveDerivedProcessingRepository
import com.chessprobe.repository.GameRepository
import com.chessprobe.repository.ImportedArchiveRepository
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.repository.PositionRepository
import com.chessprobe.repository.PuzzleSchedulingEventRepository
import com.chessprobe.repository.UserPositionStatsRepository
import com.chessprobe.repository.UserPositionWeaknessRepository
import com.chessprobe.service.ChessComClient
import com.chessprobe.service.EngineAnalysisOrchestrator
import com.chessprobe.service.GameImportService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.mock.mockito.SpyBean
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class GameImportServiceBaseIntegrationTest : AbstractIntegrationTest() {
    @Autowired
    protected lateinit var gameImportService: GameImportService

    @Autowired
    protected lateinit var gameRepository: GameRepository

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
    protected lateinit var engineAnalysisRepository: com.chessprobe.repository.EngineAnalysisRepository

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    @MockBean
    protected lateinit var chessComClient: ChessComClient

    @MockBean
    protected lateinit var engineAnalysisOrchestrator: EngineAnalysisOrchestrator

    @SpyBean
    protected lateinit var gameParserService: com.chessprobe.service.GameParserService

    protected val currentTestAccounts = mutableListOf<ChessAccount>()
    protected val currentTestUsernames = mutableSetOf<String>()

    protected fun trackTestAccount(account: ChessAccount): ChessAccount {
        if (currentTestAccounts.none { it.id == account.id }) {
            currentTestAccounts.add(account)
        }
        return account
    }

    protected fun trackTestAccountId(accountId: UUID): UUID {
        chessAccountRepository.findById(accountId).ifPresent { trackTestAccount(it) }
        return accountId
    }

    protected fun trackTestUsername(username: String) {
        currentTestUsernames.add(username)
    }

    protected fun createTrackedTestAccount(
        platform: Platform = Platform.CHESS_COM,
        username: String = "test-user-${UUID.randomUUID().toString().take(8)}",
    ): ChessAccount {
        val account =
            chessAccountRepository.save(
                ChessAccount(
                    platform = platform.name,
                    username = username,
                ),
            )
        return trackTestAccount(account)
    }

    @BeforeEach
    fun setup() {
        currentTestAccounts.clear()
        currentTestUsernames.clear()
        trackTestUsername("hikaru")
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
    open fun tearDown() {
        collectTrackedTestAccounts()
        currentTestAccounts.forEach { account -> account.id?.let(::cancelAbandonedJobs) }
        currentTestAccounts.forEach { account -> account.id?.let(::drainAccountQuiescence) }
        currentTestAccounts.forEach { account -> account.id?.let(::cleanAndQuarantineAccount) }
        currentTestAccounts.clear()
        currentTestUsernames.clear()
    }

    private fun collectTrackedTestAccounts() {
        for (username in currentTestUsernames) {
            chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", username)?.let {
                trackTestAccount(it)
            }
        }
    }

    private fun cancelAbandonedJobs(accountId: UUID) {
        val now = java.time.Instant.now()
        val activeJobs =
            asyncJobRepository.findAll().filter {
                it.chessAccount?.id == accountId && it.status in setOf("QUEUED", "PROCESSING")
            }
        for (job in activeJobs) {
            if (job.status == "QUEUED" || job.leaseExpiresAt?.isBefore(now) == true) {
                job.status = "FAILED"
                job.errorMessage = "TEST_TEARDOWN_ABANDONED"
                asyncJobRepository.saveAndFlush(job)
            }
        }
    }

    private fun drainAccountQuiescence(accountId: UUID) {
        drainAsyncWorkerQuiescence(accountId = accountId, timeoutMs = 5000)
    }

    private fun cleanAndQuarantineAccount(accountId: UUID) {
        val context = cleanTenantData(accountId = accountId)
        assertTenantQuarantined(context)
    }

    protected fun markJobAbandoned(jobId: UUID) {
        transactionTemplate.executeWithoutResult {
            val stale = asyncJobRepository.findAnyByIdForUpdate(jobId) ?: error("missing job")
            stale.status = "PROCESSING"
            stale.workerToken = UUID.randomUUID()
            stale.leaseExpiresAt =
                java.time.Instant
                    .now()
                    .minusSeconds(60)
            stale.updatedAt =
                java.time.Instant
                    .now()
                    .minusSeconds(60)
            asyncJobRepository.save(stale)
        }
    }

    protected fun persistImportProgressMethod(): java.lang.reflect.Method =
        GameImportService::class.java
            .getDeclaredMethod(
                "persistImportProgress",
                Class.forName("com.chessprobe.service.GameImportService\$AsyncJobClaim"),
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }

    protected fun asyncJobClaim(
        job: AsyncJob,
        workerToken: UUID,
    ): Any {
        val constructor =
            Class.forName("com.chessprobe.service.GameImportService\$AsyncJobClaim").declaredConstructors.single()
        constructor.isAccessible = true
        return constructor.newInstance(job, workerToken)
    }

    protected fun importRequest(username: String): ImportGamesRequest {
        trackTestUsername(username)
        return ImportGamesRequest(
            username = "hikaru",
            platform = Platform.CHESS_COM,
            timeControls = listOf(com.chessprobe.domain.TimeControl.BLITZ),
            playerColor = PlayerColor.BOTH,
        )
    }

    protected fun waitForJob(jobId: UUID): AsyncJob {
        repeat(50) {
            val job = asyncJobRepository.findById(jobId).orElse(null)
            if (job != null && job.status in listOf("COMPLETED", "FAILED")) {
                trackTestUsername(job.username)
                job.chessAccount?.let { trackTestAccount(it) }
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
                trackTestUsername(current.username)
                current.chessAccount?.let { trackTestAccount(it) }
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
                trackTestUsername(current.username)
                current.chessAccount?.let { trackTestAccount(it) }
                return current
            }
            Thread.sleep(100)
        }
        error("Analysis job $jobId did not complete within timeout")
    }
}
