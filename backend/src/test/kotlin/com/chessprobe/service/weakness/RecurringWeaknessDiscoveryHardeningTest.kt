package com.chessprobe.service.weakness

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.CommunityPatternClassification
import com.chessprobe.domain.DiscoveryJobStatus
import com.chessprobe.domain.DiscoveryStage
import com.chessprobe.domain.Game
import com.chessprobe.domain.MistakeSeverity
import com.chessprobe.domain.Position
import com.chessprobe.domain.PositionOccurrence
import com.chessprobe.domain.clustering.ClusterType
import com.chessprobe.domain.clustering.PositionCluster
import com.chessprobe.domain.clustering.PositionClusterAssignment
import com.chessprobe.domain.clustering.PositionClusterAssignmentId
import com.chessprobe.domain.clustering.PositionClusterAssignmentStatus
import com.chessprobe.domain.clustering.PositionClusterSnapshot
import com.chessprobe.domain.clustering.PositionClusterSnapshotStatus
import com.chessprobe.dto.RecurringDiscoveryRequest
import com.chessprobe.dto.RecurringWeaknessResponse
import com.chessprobe.repository.EngineAnalysisRepository
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.repository.clustering.PositionClusterAssignmentRepository
import com.chessprobe.repository.clustering.PositionClusterRepository
import com.chessprobe.repository.clustering.PositionClusterSnapshotRepository
import com.chessprobe.service.clustering.CommunityWeaknessProfile
import com.chessprobe.service.clustering.CommunityWeaknessService
import com.chessprobe.service.clustering.PositionClusteringService
import com.chessprobe.service.similarity.SimilarityConcurrencyManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RecurringWeaknessDiscoveryHardeningTest {
    private val occurrenceRepository = mock(PositionOccurrenceRepository::class.java)
    private val engineAnalysisRepository = mock(EngineAnalysisRepository::class.java)
    private val snapshotRepository = mock(PositionClusterSnapshotRepository::class.java)
    private val clusterRepository = mock(PositionClusterRepository::class.java)
    private val assignmentRepository = mock(PositionClusterAssignmentRepository::class.java)
    private val communityWeaknessService = mock(CommunityWeaknessService::class.java)
    private val concurrencyManager = mock(SimilarityConcurrencyManager::class.java)
    private val clusteringService = mock(PositionClusteringService::class.java)

    private lateinit var service: RecurringWeaknessDiscoveryService
    private val testAccount =
        ChessAccount(
            id = UUID.randomUUID(),
            platform = "CHESS_COM",
            username = "grandmaster",
        )

    @BeforeEach
    fun setup() {
        service =
            RecurringWeaknessDiscoveryService(
                occurrenceRepository = occurrenceRepository,
                engineAnalysisRepository = engineAnalysisRepository,
                snapshotRepository = snapshotRepository,
                clusterRepository = clusterRepository,
                assignmentRepository = assignmentRepository,
                communityWeaknessService = communityWeaknessService,
                concurrencyManager = concurrencyManager,
                clusteringService = clusteringService,
            )
    }

    @Test
    fun `cancellation wins race against worker completion and prevents completion mutation`() {
        val job = service.createRunningJob(testAccount)
        val cancelHoldingLock = CountDownLatch(1)
        val workerStarted = CountDownLatch(1)

        withExecutor { executor ->
            val cancelFuture =
                executor.submit(
                    Callable {
                        synchronized(job) {
                            cancelHoldingLock.countDown()
                            workerStarted.await(5, TimeUnit.SECONDS)
                            service.cancelJob(job.jobId, testAccount)
                        }
                    },
                )

            val workerFuture =
                executor.submit(
                    Callable {
                        cancelHoldingLock.await(5, TimeUnit.SECONDS)
                        workerStarted.countDown()
                        service.completeJobForTesting(job, testAccount.id, listOf(createMockWeaknessResponse()))
                    },
                )

            val cancelResponse = cancelFuture.get(5, TimeUnit.SECONDS)
            workerFuture.get(5, TimeUnit.SECONDS)

            assertEquals(DiscoveryJobStatus.CANCELLED.name, cancelResponse.status)
            assertEquals(DiscoveryJobStatus.CANCELLED, job.status)
            assertEquals(DiscoveryStage.CANCELLED, job.stage)
            assertTrue(service.getLatestRecurringWeaknesses(testAccount).isEmpty())
        }
    }

    @Test
    fun `cancellation wins race against worker failure and prevents failure mutation`() {
        val job = service.createRunningJob(testAccount)
        val cancelHoldingLock = CountDownLatch(1)
        val workerStarted = CountDownLatch(1)

        withExecutor { executor ->
            val cancelFuture =
                executor.submit(
                    Callable {
                        synchronized(job) {
                            cancelHoldingLock.countDown()
                            workerStarted.await(5, TimeUnit.SECONDS)
                            service.cancelJob(job.jobId, testAccount)
                        }
                    },
                )

            val workerFuture =
                executor.submit(
                    Callable {
                        cancelHoldingLock.await(5, TimeUnit.SECONDS)
                        workerStarted.countDown()
                        service.failJobForTesting(job, testAccount.id, "Late failure")
                    },
                )

            val cancelResponse = cancelFuture.get(5, TimeUnit.SECONDS)
            workerFuture.get(5, TimeUnit.SECONDS)

            assertEquals(DiscoveryJobStatus.CANCELLED.name, cancelResponse.status)
            assertEquals(DiscoveryJobStatus.CANCELLED, job.status)
            assertEquals(DiscoveryStage.CANCELLED, job.stage)
            assertNull(job.errorMessage)
        }
    }

    @Test
    fun `worker completion wins race against cancellation and prevents cancellation overwrite`() {
        val job = service.createRunningJob(testAccount)
        val workerHoldingLock = CountDownLatch(1)
        val cancelStarted = CountDownLatch(1)

        withExecutor { executor ->
            val workerFuture =
                executor.submit(
                    Callable {
                        synchronized(job) {
                            workerHoldingLock.countDown()
                            cancelStarted.await(5, TimeUnit.SECONDS)
                            service.completeJobForTesting(job, testAccount.id, listOf(createMockWeaknessResponse()))
                        }
                    },
                )

            val cancelFuture =
                executor.submit(
                    Callable {
                        workerHoldingLock.await(5, TimeUnit.SECONDS)
                        cancelStarted.countDown()
                        service.cancelJob(job.jobId, testAccount)
                    },
                )

            workerFuture.get(5, TimeUnit.SECONDS)
            val exception =
                assertThrows(ExecutionException::class.java) {
                    cancelFuture.get(5, TimeUnit.SECONDS)
                }
            assertTrue(exception.cause is IllegalStateException)

            assertEquals(DiscoveryJobStatus.COMPLETED, job.status)
            assertEquals(DiscoveryStage.COMPLETE, job.stage)
            assertEquals(1, service.getLatestRecurringWeaknesses(testAccount).size)
        }
    }

    @Test
    fun `worker failure wins race against cancellation and prevents cancellation overwrite`() {
        val job = service.createRunningJob(testAccount)
        val workerHoldingLock = CountDownLatch(1)
        val cancelStarted = CountDownLatch(1)

        withExecutor { executor ->
            val workerFuture =
                executor.submit(
                    Callable {
                        synchronized(job) {
                            workerHoldingLock.countDown()
                            cancelStarted.await(5, TimeUnit.SECONDS)
                            service.failJobForTesting(job, testAccount.id, "Clustering crashed")
                        }
                    },
                )

            val cancelFuture =
                executor.submit(
                    Callable {
                        workerHoldingLock.await(5, TimeUnit.SECONDS)
                        cancelStarted.countDown()
                        service.cancelJob(job.jobId, testAccount)
                    },
                )

            workerFuture.get(5, TimeUnit.SECONDS)
            val exception =
                assertThrows(ExecutionException::class.java) {
                    cancelFuture.get(5, TimeUnit.SECONDS)
                }
            assertTrue(exception.cause is IllegalStateException)

            assertEquals(DiscoveryJobStatus.FAILED, job.status)
            assertEquals(DiscoveryStage.FAILED, job.stage)
            assertEquals("Clustering crashed", job.errorMessage)
        }
    }

    @Test
    fun `unconstrained concurrent contention between cancellation and completion preserves terminal invariant`() {
        val job = service.createRunningJob(testAccount)
        val barrier = CyclicBarrier(2)

        withExecutor { executor ->
            val cancelFuture =
                executor.submit(
                    Callable {
                        barrier.await(5, TimeUnit.SECONDS)
                        service.cancelJob(job.jobId, testAccount)
                    },
                )

            val workerFuture =
                executor.submit(
                    Callable {
                        barrier.await(5, TimeUnit.SECONDS)
                        service.completeJobForTesting(job, testAccount.id, listOf(createMockWeaknessResponse()))
                    },
                )

            workerFuture.get(5, TimeUnit.SECONDS)
            assertTrue(job.status.isTerminal())

            if (job.status == DiscoveryJobStatus.CANCELLED) {
                assertEquals(DiscoveryStage.CANCELLED, job.stage)
                val response = cancelFuture.get(5, TimeUnit.SECONDS)
                assertEquals(DiscoveryJobStatus.CANCELLED.name, response.status)
                assertTrue(service.getLatestRecurringWeaknesses(testAccount).isEmpty())
            } else if (job.status == DiscoveryJobStatus.COMPLETED) {
                assertEquals(DiscoveryStage.COMPLETE, job.stage)
                val ex =
                    assertThrows(ExecutionException::class.java) {
                        cancelFuture.get(5, TimeUnit.SECONDS)
                    }
                assertTrue(ex.cause is IllegalStateException)
                assertEquals(1, service.getLatestRecurringWeaknesses(testAccount).size)
            } else {
                fail("Unexpected job status after concurrent race: ${job.status}")
            }
        }
    }

    private fun <T> withExecutor(block: (ExecutorService) -> T): T {
        val executor = Executors.newFixedThreadPool(2)
        try {
            return block(executor)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun createMockWeaknessResponse(clusterId: UUID = UUID.randomUUID()): RecurringWeaknessResponse =
        RecurringWeaknessResponse(
            clusterId = clusterId,
            clusterType = ClusterType.STRUCTURAL,
            recommendationPriority = 0.8,
            representativeFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            memberPositionCount = 10,
            totalOccurrences = 15,
            distinctGamesCount = 10,
            mistakeCount = 5,
            mistakeFrequency = 0.5,
            blunderRate = 0.2,
            evaluatedOccurrences = 15,
            unevaluatedOccurrences = 0,
            evalLossDistribution = emptyMap(),
            evalLossPercentages = emptyMap(),
            timingProfile = emptyMap(),
            formatBreakdown = emptyMap(),
        )

    @Test
    fun `triggerDiscovery with all attempted cluster types failing transitions to FAILED`() {
        mockSnapshotLookups(null, null)
        `when`(clusteringService.getOrGenerateActiveSnapshot(testAccount.id, ClusterType.STRUCTURAL))
            .thenThrow(RuntimeException("Structural clustering crashed"))
        `when`(clusteringService.getOrGenerateActiveSnapshot(testAccount.id, ClusterType.DYNAMIC))
            .thenThrow(RuntimeException("Dynamic clustering crashed"))

        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest())
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val job = service.getJob(response.jobId)
        assertNotNull(job)
        assertEquals(DiscoveryJobStatus.FAILED.name, job!!.status)
        assertEquals(DiscoveryStage.FAILED.name, job.stage)
        assertTrue(
            job.errorMessage?.contains("clustering failed") == true ||
                job.errorMessage?.contains("Structural clustering crashed") == true,
        )
        assertNull(job.warningMessage)
    }

    @Test
    fun `triggerDiscovery with structural failure and dynamic success preserves dynamic clusters with warningMessage`() {
        val snapshotId = UUID.randomUUID()
        val clusterId = UUID.randomUUID()
        val posId = UUID.randomUUID()

        mockSnapshotLookups(null, null)
        `when`(clusteringService.getOrGenerateActiveSnapshot(testAccount.id, ClusterType.STRUCTURAL))
            .thenThrow(RuntimeException("Structural clustering error"))

        val dynamicSnapshot = createSnapshot(snapshotId, ClusterType.DYNAMIC)
        `when`(clusteringService.getOrGenerateActiveSnapshot(testAccount.id, ClusterType.DYNAMIC)).thenReturn(dynamicSnapshot)

        val cluster = mockClusterWithAssignments(snapshotId, clusterId, posId)
        val occurrences = mockOccurrences(posId)
        mockConcurrencyAndProfile(cluster, occurrences)

        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest())
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val job = service.getJob(response.jobId)
        assertNotNull(job)
        assertEquals(DiscoveryJobStatus.COMPLETED.name, job!!.status)
        assertNull(job.errorMessage)
        assertNotNull(job.warningMessage)
        assertTrue(job.warningMessage!!.contains("structural") || job.warningMessage!!.contains("Partial"))

        val weaknesses = service.getLatestRecurringWeaknesses(testAccount)
        assertEquals(1, weaknesses.size)
        assertEquals(ClusterType.DYNAMIC, weaknesses[0].clusterType)
    }

    @Test
    fun `triggerDiscovery with dynamic failure and structural success preserves structural clusters with warningMessage`() {
        val snapshotId = UUID.randomUUID()
        val clusterId = UUID.randomUUID()
        val posId = UUID.randomUUID()

        mockSnapshotLookups(null, null)
        val structuralSnapshot = createSnapshot(snapshotId, ClusterType.STRUCTURAL)
        `when`(clusteringService.getOrGenerateActiveSnapshot(testAccount.id, ClusterType.STRUCTURAL))
            .thenReturn(structuralSnapshot)

        `when`(clusteringService.getOrGenerateActiveSnapshot(testAccount.id, ClusterType.DYNAMIC))
            .thenThrow(RuntimeException("Dynamic clustering error"))

        val cluster = mockClusterWithAssignments(snapshotId, clusterId, posId)
        val occurrences = mockOccurrences(posId)
        mockConcurrencyAndProfile(cluster, occurrences)

        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest())
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val job = service.getJob(response.jobId)
        assertNotNull(job)
        assertEquals(DiscoveryJobStatus.COMPLETED.name, job!!.status)
        assertNull(job.errorMessage)
        assertNotNull(job.warningMessage)
        assertTrue(job.warningMessage!!.contains("dynamic") || job.warningMessage!!.contains("Partial"))

        val weaknesses = service.getLatestRecurringWeaknesses(testAccount)
        assertEquals(1, weaknesses.size)
        assertEquals(ClusterType.STRUCTURAL, weaknesses[0].clusterType)
    }

    @Test
    fun `triggerDiscovery when snapshots return null without throwing transitions to COMPLETED with 0 weaknesses`() {
        mockSnapshotLookups(null, null)
        `when`(clusteringService.getOrGenerateActiveSnapshot(testAccount.id, ClusterType.STRUCTURAL)).thenReturn(null)
        `when`(clusteringService.getOrGenerateActiveSnapshot(testAccount.id, ClusterType.DYNAMIC)).thenReturn(null)

        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest())
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val job = service.getJob(response.jobId)
        assertNotNull(job)
        assertEquals(DiscoveryJobStatus.COMPLETED.name, job!!.status)
        assertNull(job.errorMessage)
        assertNull(job.warningMessage)
        assertTrue(service.getLatestRecurringWeaknesses(testAccount).isEmpty())
    }

    private fun mockSnapshotLookups(
        structural: PositionClusterSnapshot?,
        dynamic: PositionClusterSnapshot?,
    ) {
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                ClusterType.STRUCTURAL,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(structural)
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                ClusterType.DYNAMIC,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(dynamic)
    }

    private fun createSnapshot(
        snapshotId: UUID,
        clusterType: ClusterType,
    ): PositionClusterSnapshot =
        PositionClusterSnapshot(
            id = snapshotId,
            accountId = testAccount.id,
            snapshotVersion = 1,
            clusterType = clusterType,
            status = PositionClusterSnapshotStatus.ACTIVE,
        )

    private fun mockClusterWithAssignments(
        snapshotId: UUID,
        clusterId: UUID,
        posId: UUID,
    ): PositionCluster {
        val cluster =
            PositionCluster(
                id = clusterId,
                snapshotId = snapshotId,
                clusterIndex = 0,
                size = 12,
                centroidVector = "{}",
            )
        `when`(clusterRepository.findBySnapshotIdOrderByClusterIndexAsc(snapshotId)).thenReturn(listOf(cluster))
        val assignment =
            PositionClusterAssignment(
                id = PositionClusterAssignmentId(snapshotId = snapshotId, positionId = posId),
                clusterId = clusterId,
                assignmentStatus = PositionClusterAssignmentStatus.ASSIGNED,
            )
        `when`(assignmentRepository.findByClusterId(clusterId)).thenReturn(listOf(assignment))
        return cluster
    }

    private fun mockSnapshotAndCluster(
        snapshotId: UUID,
        clusterId: UUID,
        posId: UUID,
    ): PositionCluster {
        val snapshot = createSnapshot(snapshotId, ClusterType.STRUCTURAL)
        mockSnapshotLookups(snapshot, null)
        return mockClusterWithAssignments(snapshotId, clusterId, posId)
    }

    private fun mockOccurrences(posId: UUID): List<PositionOccurrence> {
        val game1 = Game(id = UUID.randomUUID(), chessAccount = testAccount, platformGameId = "g1", pgn = "1. e4", timeControl = "RAPID")
        val game2 = Game(id = UUID.randomUUID(), chessAccount = testAccount, platformGameId = "g2", pgn = "1. d4", timeControl = "RAPID")
        val position = Position(id = posId, hash = "h1", fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3")
        val occ1 =
            PositionOccurrence(
                id = UUID.randomUUID(),
                game = game1,
                position = position,
                chessAccount = testAccount,
                plyNumber = 5,
                movePlayed = "Nf3",
                playerColor = "WHITE",
            )
        val occ2 =
            PositionOccurrence(
                id = UUID.randomUUID(),
                game = game2,
                position = position,
                chessAccount = testAccount,
                plyNumber = 5,
                movePlayed = "Nf3",
                playerColor = "WHITE",
            )
        val occurrences = listOf(occ1, occ2)
        `when`(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(testAccount.id, listOf(posId))).thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(posId))).thenReturn(emptyList())
        return occurrences
    }

    private fun mockConcurrencyAndProfile(
        cluster: PositionCluster,
        occurrences: List<PositionOccurrence>,
    ) {
        `when`(
            concurrencyManager.withPermitOrFallback<Boolean>(
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
            ),
        ).thenAnswer { invocation ->
            val onPermitAcquired = invocation.getArgument<() -> Boolean>(0)
            onPermitAcquired()
        }

        val profile =
            CommunityWeaknessProfile(
                clusterId = cluster.id,
                memberPositionCount = 12,
                totalOccurrences = 2,
                distinctGamesCount = 2,
                classification = CommunityPatternClassification.RECURRING_WEAKNESS,
                mistakeCount = 2,
                mistakeFrequency = 100.0,
                blunderRate = 50.0,
                evalLossDistribution = mapOf(MistakeSeverity.SEVERE to 1),
                evalLossPercentages = mapOf(MistakeSeverity.SEVERE to 50.0),
                timingProfile = emptyMap(),
                formatBreakdown = emptyMap(),
            )
        `when`(
            communityWeaknessService.profileCommunity(
                org.mockito.kotlin.eq(cluster),
                org.mockito.kotlin.eq(occurrences),
                org.mockito.kotlin.any(),
            ),
        ).thenReturn(profile)
    }
}
