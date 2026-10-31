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
import com.chessecho.service.GameImportService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GameImportServiceCoordinatorIntegrationTest : GameImportServiceBaseIntegrationTest() {
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
            gameRepository
                .findAllByChessAccountOrderByPlayedAtDesc(
                    account,
                    org.springframework.data.domain.PageRequest
                        .of(0, 20),
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

        val targetService =
            org.springframework.test.util.AopTestUtils
                .getUltimateTargetObject<GameImportService>(gameImportService)
        val processArchive =
            GameImportService::class.java
                .getDeclaredMethod(
                    "processDerivedArchive",
                    com.chessecho.domain.ChessAccount::class.java,
                    com.chessecho.domain.AppUser::class.java,
                    com.chessecho.domain.ImportedArchive::class.java,
                ).apply { isAccessible = true }
        val pool =
            java.util.concurrent.Executors
                .newFixedThreadPool(2)
        try {
            val first = pool.submit<Any?> { processArchive.invoke(targetService, account, null, archive) }
            assertTrue(enteredParser.await(5, TimeUnit.SECONDS), "first worker did not claim derived processing")
            val second = pool.submit<Any?> { processArchive.invoke(targetService, account, null, archive) }
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

    private fun seedFirstClaimFixture(): Pair<com.chessecho.domain.ChessAccount, com.chessecho.domain.ImportedArchive> {
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
        return account to archive
    }

    @Test
    fun `concurrent first derived claims create one processing record`() {
        val (account, archive) = seedFirstClaimFixture()
        val enteredParser = CountDownLatch(1)
        val releaseParser = CountDownLatch(1)
        doAnswer {
            enteredParser.countDown()
            assertTrue(releaseParser.await(5, TimeUnit.SECONDS), "first derived claimant was not released")
            emptySet<UUID>()
        }.whenever(gameParserService).parseAndSavePositions(any())

        val targetService =
            org.springframework.test.util.AopTestUtils
                .getUltimateTargetObject<GameImportService>(gameImportService)
        val processArchive =
            GameImportService::class.java
                .getDeclaredMethod(
                    "processDerivedArchive",
                    com.chessecho.domain.ChessAccount::class.java,
                    com.chessecho.domain.AppUser::class.java,
                    com.chessecho.domain.ImportedArchive::class.java,
                ).apply { isAccessible = true }
        val pool =
            java.util.concurrent.Executors
                .newFixedThreadPool(2)
        try {
            val first = pool.submit<Any?> { processArchive.invoke(targetService, account, null, archive) }
            assertTrue(enteredParser.await(5, TimeUnit.SECONDS), "first worker did not create and claim derived processing")
            val second = pool.submit<Any?> { processArchive.invoke(targetService, account, null, archive) }
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
            positionOccurrenceRepository
                .findByChessAccountIdAndPlayerColor(account.id, "WHITE")
                .map { it.position.id }
                .toSet()
                .size,
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
        seedWeaknessesAndEngineAnalysis(account, occurrences)

        org.mockito.kotlin.reset(gameParserService)
        doCallRealMethod().whenever(gameParserService).parseAndSavePositions(any())
        val retry = gameImportService.createImportJob(importRequest("crash-before-stats"))
        gameImportService.executeImportJob(retry.id)
        assertEquals("COMPLETED", awaitTerminalJob(retry.id).status)

        assertEquals(occurrences.size.toLong(), positionOccurrenceRepository.count())

        val expectedTimesReached = occurrences.groupingBy { it.position.id }.eachCount()
        assertExactStatsAndSchedulingEvents(occurrences, account, expectedTimesReached)

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
            userPositionStatsRepository
                .findByChessAccountIdAndPlayerColor(account.id, "WHITE")
                .associate { it.position.id to it.timesReached },
            "a replayed derived unit must not inflate the occurrence counts it recomputes",
        )
    }

    private fun seedWeaknessesAndEngineAnalysis(
        account: com.chessecho.domain.ChessAccount,
        occurrences: List<com.chessecho.domain.PositionOccurrence>,
    ) {
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
    }

    private fun assertExactStatsAndSchedulingEvents(
        occurrences: List<com.chessecho.domain.PositionOccurrence>,
        account: com.chessecho.domain.ChessAccount,
        expectedTimesReached: Map<UUID, Int>,
    ) {
        val stats = userPositionStatsRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertEquals(expectedTimesReached.size, stats.size)
        assertEquals(
            expectedTimesReached,
            stats.associate { it.position.id to it.timesReached },
            "each stats row must count exactly the occurrences that survived the crash window",
        )

        val expectedEvents =
            occurrences
                .flatMap { occurrence ->
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
        val targetService =
            org.springframework.test.util.AopTestUtils
                .getUltimateTargetObject<GameImportService>(gameImportService)
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
        whenever(chessComClient.fetchMonthlyGames(firstArchive)).thenReturn(checkpointFirstArchiveGames())

        val secondArchiveRequested = CountDownLatch(1)
        val releaseSecondArchive = CountDownLatch(1)
        doAnswer {
            secondArchiveRequested.countDown()
            assertTrue(releaseSecondArchive.await(5, TimeUnit.SECONDS), "Second archive was not released")
            checkpointSecondArchiveGames()
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

    private fun checkpointFirstArchiveGames(): List<Map<String, Any>> =
        listOf(
            importGame(
                url = "https://www.chess.com/game/live/checkpoint-1",
                username = "checkpointuser",
            ),
            importGame(
                url = "https://www.chess.com/game/daily/checkpoint-2",
                username = "checkpointuser",
                timeClass = "daily",
            ),
        )

    private fun checkpointSecondArchiveGames(): List<Map<String, Any>> =
        listOf(
            importGame(
                url = "https://www.chess.com/game/live/checkpoint-3",
                username = "checkpointuser",
            ),
            mapOf(
                "url" to "https://www.chess.com/game/live/checkpoint-4",
                "pgn" to "[Event \"Live Chess\"]\n1. e4 e5",
                "time_class" to "blitz",
                "rules" to "chess",
            ),
            mapOf(
                "pgn" to "[Event \"Live Chess\"]\n1. d4 d5",
                "time_class" to "blitz",
                "rules" to "chess",
                "white" to mapOf("username" to "checkpointuser", "result" to "win"),
                "black" to mapOf("username" to "opponent", "result" to "checkmated"),
            ),
        )
}
