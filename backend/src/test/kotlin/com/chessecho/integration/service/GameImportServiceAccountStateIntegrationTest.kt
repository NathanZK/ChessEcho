package com.chessecho.integration.service

import com.chessecho.domain.AsyncJob
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.service.GameImportService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verifyNoInteractions
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GameImportServiceAccountStateIntegrationTest : GameImportServiceBaseIntegrationTest() {
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
}
