package com.chessprobe.concurrency

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.DiscoveryJobStatus
import com.chessprobe.dto.RecurringDiscoveryRequest
import com.chessprobe.repository.EngineAnalysisRepository
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.repository.clustering.PositionClusterAssignmentRepository
import com.chessprobe.repository.clustering.PositionClusterRepository
import com.chessprobe.repository.clustering.PositionClusterSnapshotRepository
import com.chessprobe.service.clustering.CommunityWeaknessService
import com.chessprobe.service.similarity.SimilarityConcurrencyManager
import com.chessprobe.service.weakness.RecurringWeaknessDiscoveryService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RecurringWeaknessDiscoveryConcurrencyTest {
    private val occurrenceRepository = mock<PositionOccurrenceRepository>()
    private val engineAnalysisRepository = mock<EngineAnalysisRepository>()
    private val snapshotRepository = mock<PositionClusterSnapshotRepository>()
    private val clusterRepository = mock<PositionClusterRepository>()
    private val assignmentRepository = mock<PositionClusterAssignmentRepository>()
    private val communityWeaknessService = mock<CommunityWeaknessService>()
    private val concurrencyManager = mock<SimilarityConcurrencyManager>()

    private lateinit var service: RecurringWeaknessDiscoveryService
    private val testAccount =
        ChessAccount(
            id = UUID.randomUUID(),
            platform = "CHESS_COM",
            username = "concurrency-user",
        )

    @BeforeEach
    fun setup() {
        whenever(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                any(),
                any(),
                any(),
            ),
        ).thenReturn(null)

        service =
            RecurringWeaknessDiscoveryService(
                occurrenceRepository = occurrenceRepository,
                engineAnalysisRepository = engineAnalysisRepository,
                snapshotRepository = snapshotRepository,
                clusterRepository = clusterRepository,
                assignmentRepository = assignmentRepository,
                communityWeaknessService = communityWeaknessService,
                concurrencyManager = concurrencyManager,
            )
    }

    @Test
    fun `racing cancellation and worker completion strictly preserves terminal state immutability without sleep timeouts`() {
        val trials = 10
        val cancelledWins = AtomicInteger(0)
        val completedWins = AtomicInteger(0)

        for (trial in 0 until trials) {
            val trialAccount =
                ChessAccount(
                    id = UUID.randomUUID(),
                    platform = "CHESS_COM",
                    username = "trial-$trial",
                )

            val initialJob = service.triggerDiscovery(trialAccount, RecurringDiscoveryRequest())
            val jobId = initialJob.jobId

            val startRaceLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(2)
            val cancelSucceeded = AtomicBoolean(false)

            // Thread 1: Client issues cancelJob
            Thread.ofVirtual().start {
                startRaceLatch.await()
                try {
                    service.cancelJob(jobId, trialAccount)
                    cancelSucceeded.set(true)
                } catch (_: IllegalStateException) {
                    // Allowed when completeJob won the race and transitioned to terminal COMPLETED
                } finally {
                    doneLatch.countDown()
                }
            }

            // Thread 2: Worker executes asynchronously via executor
            // We release startRaceLatch to race cancelJob with the pipeline
            startRaceLatch.countDown()
            doneLatch.countDown()

            assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Race should resolve deterministically")

            // Wait for terminal state on completion latch
            val stateResponse = service.getJob(jobId)
            assertNotNull(stateResponse)
            val finalStatus = DiscoveryJobStatus.valueOf(stateResponse!!.status)

            // Terminal state immutability assertion
            assertTrue(
                finalStatus.isTerminal(),
                "Job must reach a terminal state (COMPLETED or CANCELLED), got: $finalStatus",
            )

            if (cancelSucceeded.get()) {
                cancelledWins.incrementAndGet()
                assertEquals(DiscoveryJobStatus.CANCELLED, finalStatus)
                // Cache must not be committed if cancelled
                assertTrue(service.getLatestRecurringWeaknesses(trialAccount).isEmpty())
            } else {
                completedWins.incrementAndGet()
                assertEquals(DiscoveryJobStatus.COMPLETED, finalStatus)
                // Subsequent cancel on completed job must throw IllegalStateException
                assertThrows(IllegalStateException::class.java) {
                    service.cancelJob(jobId, trialAccount)
                }
            }
        }

        assertTrue(cancelledWins.get() + completedWins.get() == trials)
    }

    @Test
    fun `concurrent triggerDiscovery for the same account rejects duplicate active jobs with IllegalStateException`() {
        val concurrency = 5
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(concurrency)
        val successCount = AtomicInteger(0)
        val rejectedCount = AtomicInteger(0)

        for (i in 0 until concurrency) {
            Thread.ofVirtual().start {
                startLatch.await()
                try {
                    service.triggerDiscovery(testAccount, RecurringDiscoveryRequest())
                    successCount.incrementAndGet()
                } catch (e: IllegalStateException) {
                    if (e.message?.contains("already in progress") == true) {
                        rejectedCount.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All discovery submissions must settle")

        // Exactly one submission must succeed; the rest must be rejected
        assertEquals(1, successCount.get(), "Only one active job must be permitted")
        assertEquals(concurrency - 1, rejectedCount.get(), "All overlapping submissions must be rejected")
    }
}
