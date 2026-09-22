package com.chessecho.integration.service

import com.chessecho.domain.ArchiveDerivedStatus
import com.chessecho.domain.AsyncJob
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.SchedulingEventType
import com.chessecho.domain.UserPositionWeakness
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
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.memberProperties
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
    private lateinit var archiveDerivedProcessingRepository: ArchiveDerivedProcessingRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    private lateinit var userPositionStatsRepository: UserPositionStatsRepository

    @Autowired
    private lateinit var userPositionWeaknessRepository: UserPositionWeaknessRepository

    @Autowired
    private lateinit var puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository

    @Autowired
    private lateinit var engineAnalysisRepository: com.chessecho.repository.EngineAnalysisRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockBean
    private lateinit var chessComClient: ChessComClient

    @MockBean
    private lateinit var engineAnalysisOrchestrator: EngineAnalysisOrchestrator

    @SpyBean
    private lateinit var gameParserService: com.chessecho.service.GameParserService

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
    fun `raw games remain committed when derived processing fails`() {
        doThrow(IllegalStateException("derived failure"))
            .whenever(gameParserService)
            .parseAndSavePositions(any())

        val job = gameImportService.createImportJob(importRequest("raw-commit"))
        gameImportService.executeImportJob(job.id)
        val completed = awaitTerminalJob(job.id)

        assertEquals("FAILED", completed.status)
        val account = assertNotNull(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "hikaru"))
        assertEquals(
            1,
            gameRepository.findAllByChessAccountOrderByPlayedAtDesc(
                account,
                org.springframework.data.domain.PageRequest.of(0, 20),
            ).content.size,
            "raw game rows must commit before derived processing begins",
        )
    }

    @Test
    fun `derived retry resumes partial processing from persisted games idempotently`() {
        doThrow(IllegalStateException("derived failure"))
            .whenever(gameParserService)
            .parseAndSavePositions(any())

        val first = gameImportService.createImportJob(importRequest("retry-no-refetch"))
        gameImportService.executeImportJob(first.id)
        val failed = awaitTerminalJob(first.id)
        assertEquals("FAILED", failed.status, "the durable import lifecycle must retain the derived failure")

        val account =
            assertNotNull(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "hikaru"))
        val archive = importedArchiveRepository.findByChessAccount(account).single()
        val failedProcessing = archiveDerivedProcessingRepository.findByImportedArchive(archive)
        assertNotNull(failedProcessing)
        assertEquals(ArchiveDerivedStatus.FAILED, failedProcessing.status)

        org.mockito.kotlin.reset(gameParserService)
        doCallRealMethod().whenever(gameParserService).parseAndSavePositions(any())

        val retry = gameImportService.createImportJob(importRequest("retry-no-refetch"))
        gameImportService.executeImportJob(retry.id)
        assertEquals("COMPLETED", awaitTerminalJob(retry.id).status)
        val afterRetry = positionOccurrenceRepository.count()
        assertTrue(afterRetry > 0, "retry must persist derived occurrences from the archive's committed raw games")

        val completedProcessing = archiveDerivedProcessingRepository.findByImportedArchive(archive)
        assertNotNull(completedProcessing)
        assertEquals(ArchiveDerivedStatus.COMPLETED, completedProcessing.status)
        assertEquals(2, completedProcessing.attemptCount)

        verify(chessComClient, org.mockito.kotlin.times(1)).fetchMonthlyGames(any())
    }

    @Test
    fun `concurrent archive retries have one durable derived-processing claimant`() {
        doThrow(IllegalStateException("seed derived failure"))
            .whenever(gameParserService)
            .parseAndSavePositions(any())
        val seed = gameImportService.createImportJob(importRequest("claim-owner"))
        gameImportService.executeImportJob(seed.id)
        assertEquals("FAILED", awaitTerminalJob(seed.id).status)

        val account = assertNotNull(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "hikaru"))
        val archive = importedArchiveRepository.findByChessAccount(account).single()
        org.mockito.kotlin.reset(gameParserService)
        val enteredParser = CountDownLatch(1)
        val releaseParser = CountDownLatch(1)
        doAnswer {
            enteredParser.countDown()
            assertTrue(releaseParser.await(5, TimeUnit.SECONDS), "derived claimant was not released")
            emptySet<UUID>()
        }.whenever(gameParserService).parseAndSavePositions(any())

        val targetService = org.springframework.test.util.AopTestUtils.getUltimateTargetObject<GameImportService>(gameImportService)
        val processArchive =
            GameImportService::class.java.getDeclaredMethod(
                "processDerivedArchive",
                com.chessecho.domain.ChessAccount::class.java,
                com.chessecho.domain.ImportedArchive::class.java,
            ).apply { isAccessible = true }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Any?> { processArchive.invoke(targetService, account, archive) }
            assertTrue(enteredParser.await(5, TimeUnit.SECONDS), "first worker did not claim derived processing")
            val second = pool.submit<Any?> { processArchive.invoke(targetService, account, archive) }
            assertEquals(
                emptySet<UUID>(),
                second.get(5, TimeUnit.SECONDS),
                "a live lease must prevent a second worker claim",
            )
            releaseParser.countDown()
            first.get(5, TimeUnit.SECONDS)
        } finally {
            releaseParser.countDown()
            pool.shutdownNow()
        }

        val processing = assertNotNull(archiveDerivedProcessingRepository.findByImportedArchive(archive))
        assertEquals(ArchiveDerivedStatus.COMPLETED, processing.status)
        assertEquals(2, processing.attemptCount, "only the seed failure and one retry may claim the archive")
    }

    @Test
    fun `concurrent first derived claims create one processing record`() {
        val account =
            chessAccountRepository.saveAndFlush(
                com.chessecho.domain.ChessAccount(platform = "CHESS_COM", username = "first-claim"),
            )
        val archive =
            importedArchiveRepository.saveAndFlush(
                com.chessecho.domain.ImportedArchive(
                    chessAccount = account,
                    archiveUrl = "https://api.chess.com/pub/player/first-claim/games/2024/01",
                    yearMonth = "2024-01",
                    gameCount = 0,
                ),
            )
        gameRepository.saveAndFlush(
            com.chessecho.domain.Game(
                chessAccount = account,
                importedArchive = archive,
                platformGameId = "first-claim-game",
                whiteUsername = "first-claim",
                blackUsername = "opponent",
                pgn =
                    """
                    [Event "Live Chess"]
                    [White "first-claim"]
                    [Black "opponent"]

                    1. e4 e5 1/2-1/2
                    """.trimIndent(),
                timeControl = "600",
            ),
        )
        val enteredParser = CountDownLatch(1)
        val releaseParser = CountDownLatch(1)
        doAnswer {
            enteredParser.countDown()
            assertTrue(releaseParser.await(5, TimeUnit.SECONDS), "first derived claimant was not released")
            emptySet<UUID>()
        }.whenever(gameParserService).parseAndSavePositions(any())

        val targetService = org.springframework.test.util.AopTestUtils.getUltimateTargetObject<GameImportService>(gameImportService)
        val processArchive =
            GameImportService::class.java.getDeclaredMethod(
                "processDerivedArchive",
                com.chessecho.domain.ChessAccount::class.java,
                com.chessecho.domain.ImportedArchive::class.java,
            ).apply { isAccessible = true }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Any?> { processArchive.invoke(targetService, account, archive) }
            assertTrue(enteredParser.await(5, TimeUnit.SECONDS), "first worker did not create and claim derived processing")
            val second = pool.submit<Any?> { processArchive.invoke(targetService, account, archive) }
            assertEquals(emptySet<UUID>(), second.get(5, TimeUnit.SECONDS))
            releaseParser.countDown()
            first.get(5, TimeUnit.SECONDS)
        } finally {
            releaseParser.countDown()
            pool.shutdownNow()
        }

        val processingRows = archiveDerivedProcessingRepository.findAll()
        assertEquals(1, processingRows.size)
        assertEquals(ArchiveDerivedStatus.COMPLETED, processingRows.single().status)
        assertEquals(1, processingRows.single().attemptCount)
    }

    @Test
    fun `stale processing import job can be reclaimed by execute without duplicate active job writes`() {
        val job = gameImportService.createImportJob(importRequest("stale-execute"))
        transactionTemplate.executeWithoutResult {
            val stale = asyncJobRepository.findAnyByIdForUpdate(job.id) ?: error("missing job")
            stale.status = "PROCESSING"
            stale.workerToken = UUID.randomUUID()
            stale.leaseExpiresAt = java.time.Instant.now().minusSeconds(60)
            stale.updatedAt = java.time.Instant.now().minusSeconds(60)
            asyncJobRepository.save(stale)
        }

        gameImportService.executeImportJob(job.id)

        val completed = awaitTerminalJob(job.id)
        assertEquals("COMPLETED", completed.status, completed.errorMessage)
        assertEquals(null, completed.workerToken)
        assertEquals(null, completed.leaseExpiresAt)
        assertTrue(positionOccurrenceRepository.count() > 0)
    }

    @Test
    fun `stale processing jobs are recovered by the sweep without a user creating a new job`() {
        val job = gameImportService.createImportJob(importRequest("stale-sweep"))
        markJobAbandoned(job.id)

        val recovered = gameImportService.recoverStaleImportJobs()

        assertEquals(listOf(job.id), recovered, "the abandoned job must be resumed by the recovery sweep alone")
        val completed = awaitTerminalJob(job.id)
        assertEquals("COMPLETED", completed.status, completed.errorMessage)
        assertTrue(positionOccurrenceRepository.count() > 0, "recovery must finish the derived work")
        assertEquals(emptyList(), gameImportService.recoverStaleImportJobs(), "a finished job is no longer a candidate")
    }

    @Test
    fun `stale job recovery is scheduled, so nothing depends on a user creating another job`() {
        val scheduled =
            GameImportService::class.java.getDeclaredMethod("sweepStaleImportJobs")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled::class.java)
        assertNotNull(scheduled, "recovery must have a caller in the running application")
        assertTrue(
            scheduled.fixedDelayString.contains("chessecho.import.stale-job-recovery-interval-ms"),
            "the recovery cadence must be configurable like the existing scheduled maintenance",
        )
    }

    @Test
    fun `the recovery sweep leaves a job whose worker lease is still live`() {
        val job = gameImportService.createImportJob(importRequest("live-lease"))
        val liveToken = UUID.randomUUID()
        transactionTemplate.executeWithoutResult {
            val live = asyncJobRepository.findAnyByIdForUpdate(job.id) ?: error("missing job")
            live.status = "PROCESSING"
            live.workerToken = liveToken
            live.leaseExpiresAt = java.time.Instant.now().plusSeconds(600)
            asyncJobRepository.save(live)
        }

        assertEquals(emptyList(), gameImportService.recoverStaleImportJobs())

        val untouched = asyncJobRepository.findById(job.id).orElseThrow()
        assertEquals("PROCESSING", untouched.status, "a live worker must not be displaced")
        assertEquals(liveToken, untouched.workerToken, "recovery must not steal a live worker's lease")
        verifyNoInteractions(chessComClient)
    }

    @Test
    fun `a progress write from a displaced worker is rejected`() {
        val job = gameImportService.createImportJob(importRequest("displaced-worker"))
        markJobAbandoned(job.id)
        val displacedClaim = asyncJobClaim(asyncJobRepository.findById(job.id).orElseThrow(), UUID.randomUUID())

        transactionTemplate.executeWithoutResult {
            persistImportProgressMethod().invoke(
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject<GameImportService>(gameImportService),
                displacedClaim,
                99,
                99,
                99,
            )
        }

        val untouched = asyncJobRepository.findById(job.id).orElseThrow()
        assertEquals(0, untouched.gamesImported, "a worker that no longer owns the lease must not write progress")
        assertEquals(0, untouched.gamesProcessed)
    }

    private fun markJobAbandoned(jobId: UUID) {
        transactionTemplate.executeWithoutResult {
            val stale = asyncJobRepository.findAnyByIdForUpdate(jobId) ?: error("missing job")
            stale.status = "PROCESSING"
            stale.workerToken = UUID.randomUUID()
            stale.leaseExpiresAt = java.time.Instant.now().minusSeconds(60)
            stale.updatedAt = java.time.Instant.now().minusSeconds(60)
            asyncJobRepository.save(stale)
        }
    }

    private fun persistImportProgressMethod(): java.lang.reflect.Method =
        GameImportService::class.java.getDeclaredMethod(
            "persistImportProgress",
            Class.forName("com.chessecho.service.GameImportService\$AsyncJobClaim"),
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }

    private fun asyncJobClaim(
        job: AsyncJob,
        workerToken: UUID,
    ): Any {
        val constructor =
            Class.forName("com.chessecho.service.GameImportService\$AsyncJobClaim").declaredConstructors.single()
        constructor.isAccessible = true
        return constructor.newInstance(job, workerToken)
    }

    @Test
    fun `creating a new import marks stale active job failed instead of blocking forever`() {
        val staleJob = gameImportService.createImportJob(importRequest("stale-create"))
        transactionTemplate.executeWithoutResult {
            val stale = asyncJobRepository.findAnyByIdForUpdate(staleJob.id) ?: error("missing job")
            stale.status = "PROCESSING"
            stale.workerToken = UUID.randomUUID()
            stale.leaseExpiresAt = java.time.Instant.now().minusSeconds(60)
            stale.updatedAt = java.time.Instant.now().minusSeconds(60)
            asyncJobRepository.save(stale)
        }

        val replacement = gameImportService.createImportJob(importRequest("stale-create"))

        val stale = asyncJobRepository.findById(staleJob.id).orElseThrow()
        assertEquals("FAILED", stale.status)
        assertEquals("STALE_PROCESSING_JOB_RECLAIMED", stale.errorMessage)
        assertEquals("QUEUED", replacement.status)
    }

    @Test
    fun `crash after derived write retries without duplicate occurrences`() {
        doAnswer { invocation ->
            invocation.callRealMethod()
            throw IllegalStateException("simulated worker crash after derived commit")
        }.whenever(gameParserService).parseAndSavePositions(any())

        val first = gameImportService.createImportJob(importRequest("crash-window"))
        gameImportService.executeImportJob(first.id)
        assertEquals("FAILED", awaitTerminalJob(first.id).status)
        val occurrencesAfterCrash = positionOccurrenceRepository.count()
        assertTrue(occurrencesAfterCrash > 0, "the parser transaction must commit before the worker crashes")

        org.mockito.kotlin.reset(gameParserService)
        doCallRealMethod().whenever(gameParserService).parseAndSavePositions(any())
        val retry = gameImportService.createImportJob(importRequest("crash-window"))
        gameImportService.executeImportJob(retry.id)
        assertEquals("COMPLETED", awaitTerminalJob(retry.id).status)
        assertEquals(
            occurrencesAfterCrash,
            positionOccurrenceRepository.count(),
            "database-backed semantic identity must reconcile the crash-window replay",
        )
        val account = assertNotNull(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "hikaru"))
        val stats = userPositionStatsRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertEquals(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE").map { it.position.id }.toSet().size,
            stats.size,
            "retry must create exactly one stats row per reached position/color",
        )
        verify(chessComClient, org.mockito.kotlin.times(1)).fetchMonthlyGames(any())
    }

    @Test
    fun `crash after occurrence commit retries through coordinator without duplicate stats or scheduling events`() {
        doAnswer { invocation ->
            invocation.callRealMethod()
            throw IllegalStateException("simulated worker crash after occurrence commit before stats")
        }.whenever(gameParserService).parseAndSavePositions(any())

        val first = gameImportService.createImportJob(importRequest("crash-before-stats"))
        gameImportService.executeImportJob(first.id)
        assertEquals("FAILED", awaitTerminalJob(first.id).status)
        val account = assertNotNull(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "hikaru"))
        val occurrences = positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertFalse(occurrences.isEmpty())
        occurrences.forEach { occurrence ->
            userPositionWeaknessRepository.save(
                UserPositionWeakness(
                    chessAccount = account,
                    position = occurrence.position,
                    playerColor = occurrence.playerColor,
                    mistakeCount = 1,
                ),
            )
            val analysis =
                EngineAnalysis(
                    position = occurrence.position,
                    depth = 12,
                    baselineEvalCp = 20,
                    bestMove = occurrence.movePlayed,
                    bestMoveEvalCp = 20,
                )
            analysis.moveEvaluations.add(
                MoveEvaluation(
                    engineAnalysis = analysis,
                    move = occurrence.movePlayed,
                    evalCp = 20,
                    evalLossFromBest = 0.0,
                ),
            )
            engineAnalysisRepository.save(analysis)
        }

        org.mockito.kotlin.reset(gameParserService)
        doCallRealMethod().whenever(gameParserService).parseAndSavePositions(any())
        val retry = gameImportService.createImportJob(importRequest("crash-before-stats"))
        gameImportService.executeImportJob(retry.id)
        assertEquals("COMPLETED", awaitTerminalJob(retry.id).status)

        assertEquals(occurrences.size.toLong(), positionOccurrenceRepository.count())

        // Exact stats, not just "some stats": one row per reached position/colour,
        // each counting exactly the occurrences that exist.
        val expectedTimesReached = occurrences.groupingBy { it.position.id }.eachCount()
        val stats = userPositionStatsRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertEquals(expectedTimesReached.size, stats.size)
        assertEquals(
            expectedTimesReached,
            stats.associate { it.position.id to it.timesReached },
            "each stats row must count exactly the occurrences that survived the crash window",
        )

        // Exact scheduling events: every occurrence was given a weakness and an
        // analysis above, so the retry must emit exactly one GAME_REENCOUNTERED
        // and exactly one outcome event per occurrence.
        val expectedEvents =
            occurrences.flatMap { occurrence ->
                listOf(
                    occurrence.id to SchedulingEventType.GAME_REENCOUNTERED,
                    occurrence.id to SchedulingEventType.GAME_HANDLED_SUCCESSFULLY,
                )
            }.toSet()
        assertEquals(
            expectedEvents,
            puzzleSchedulingEventRepository.findAll().map { it.sourceOccurrence?.id to it.eventType }.toSet(),
        )
        assertEquals(
            expectedEvents.size.toLong(),
            puzzleSchedulingEventRepository.count(),
            "retry must not duplicate source-occurrence scheduling events",
        )

        // Now replay the coordinator over an archive whose derived unit was left
        // FAILED after its occurrences, stats and events were already written -
        // exactly the state a crash in the stats/scheduling window produces.
        val archive = importedArchiveRepository.findByChessAccount(account).single()
        transactionTemplate.executeWithoutResult {
            val record = assertNotNull(archiveDerivedProcessingRepository.findByImportedArchive(archive))
            record.status = ArchiveDerivedStatus.FAILED
            record.workerToken = null
            record.leaseExpiresAt = null
            record.completedAt = null
            archiveDerivedProcessingRepository.saveAndFlush(record)
        }

        val replay = gameImportService.createImportJob(importRequest("crash-before-stats"))
        gameImportService.executeImportJob(replay.id)
        assertEquals("COMPLETED", awaitTerminalJob(replay.id).status)

        assertEquals(occurrences.size.toLong(), positionOccurrenceRepository.count())
        assertEquals(
            expectedTimesReached,
            userPositionStatsRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
                .associate { it.position.id to it.timesReached },
            "a replayed derived unit must not inflate the occurrence counts it recomputes",
        )
        assertEquals(
            expectedEvents.size.toLong(),
            puzzleSchedulingEventRepository.count(),
            "a replayed derived unit must not duplicate scheduling events",
        )
    }

    @Test
    fun `raw game commit is observable before a blocked derived transaction`() {
        val enteredDerived = CountDownLatch(1)
        val releaseDerived = CountDownLatch(1)
        var derivedTransactionActive: Boolean? = null
        doAnswer {
            derivedTransactionActive = TransactionSynchronizationManager.isActualTransactionActive()
            enteredDerived.countDown()
            assertTrue(releaseDerived.await(5, TimeUnit.SECONDS))
            emptySet<UUID>()
        }.whenever(gameParserService).parseAndSavePositions(any())

        val job = gameImportService.createImportJob(importRequest("transaction-boundary"))
        gameImportService.executeImportJob(job.id)
        assertTrue(enteredDerived.await(5, TimeUnit.SECONDS), "derived processing did not start")

        val account = assertNotNull(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "hikaru"))
        val visibleRawGames =
            transactionTemplate.execute {
                jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM game WHERE chess_account_id = ?",
                    Long::class.java,
                    account.id,
                )
            }
        releaseDerived.countDown()

        assertEquals(1L, visibleRawGames, "raw commit must be a separate transaction from derived work")
        assertEquals(
            false,
            derivedTransactionActive,
            "derived processing must not retain the old archive-month write transaction",
        )
    }

    private fun importRequest(username: String): ImportGamesRequest =
        ImportGamesRequest(
            username = "hikaru",
            platform = Platform.CHESS_COM,
            timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
            playerColor = PlayerColor.BOTH,
        )

    @Test
    fun `worker waits for concurrent account deletion and rejects the resulting unresolved job`() {
        val request =
            ImportGamesRequest(
                username = "deleted-before-claim",
                platform = Platform.CHESS_COM,
                timeControls = listOf(com.chessecho.domain.TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
            )
        val job = gameImportService.createImportJob(request)
        val accountId = job.chessAccount!!.id
        val deletionApplied = CountDownLatch(1)
        val releaseDeletion = CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()

        try {
            val deletion =
                pool.submit {
                    transactionTemplate.executeWithoutResult {
                        jdbcTemplate.update(
                            "UPDATE async_job SET configuration_state = 'UNRESOLVED', chess_account_id = NULL WHERE id = ?",
                            job.id,
                        )
                        jdbcTemplate.update("DELETE FROM chess_account WHERE id = ?", accountId)
                        deletionApplied.countDown()
                        assertTrue(releaseDeletion.await(5, TimeUnit.SECONDS), "Account deletion was not released")
                    }
                }
            assertTrue(deletionApplied.await(5, TimeUnit.SECONDS), "Account deletion did not reach the worker race")

            gameImportService.executeImportJob(job.id)
            Thread.sleep(250)
            verifyNoInteractions(chessComClient)

            releaseDeletion.countDown()
            deletion.get(5, TimeUnit.SECONDS)
            val failed = awaitTerminalJob(job.id)
            assertEquals("FAILED", failed.status)
            assertEquals("INVALID_READY_JOB_CONFIGURATION", failed.errorMessage)
            assertEquals(AsyncJob.CONFIGURATION_UNRESOLVED, failed.configurationState)
            assertEquals(null, failed.chessAccount)
            verifyNoInteractions(chessComClient)
        } finally {
            releaseDeletion.countDown()
            pool.shutdownNow()
        }
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
                Class.forName("com.chessecho.service.GameImportService\$AsyncJobClaim"),
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        method.isAccessible = true
        val claimConstructor = Class.forName("com.chessecho.service.GameImportService\$AsyncJobClaim").declaredConstructors.single()
        claimConstructor.isAccessible = true
        val token = UUID.randomUUID()
        job.workerToken = token
        asyncJobRepository.saveAndFlush(job)
        val claim = claimConstructor.newInstance(job, token)

        transactionTemplate.executeWithoutResult {
            method.invoke(targetService, claim, 3, 1, 6)
        }

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

    private fun awaitTerminalJob(jobId: UUID): AsyncJob {
        repeat(50) {
            val current = asyncJobRepository.findById(jobId).orElse(null)
            if (current != null && current.status in listOf("COMPLETED", "FAILED")) {
                return current
            }
            Thread.sleep(100)
        }
        error("Import job $jobId did not complete within timeout")
    }
}
