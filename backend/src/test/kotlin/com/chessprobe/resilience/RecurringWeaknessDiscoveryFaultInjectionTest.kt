package com.chessprobe.resilience

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.DiscoveryJobStatus
import com.chessprobe.domain.DiscoveryStage
import com.chessprobe.domain.clustering.ClusterType
import com.chessprobe.domain.clustering.PositionClusterSnapshot
import com.chessprobe.domain.clustering.PositionClusterSnapshotStatus
import com.chessprobe.dto.RecurringDiscoveryRequest
import com.chessprobe.repository.EngineAnalysisRepository
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.repository.clustering.PositionClusterAssignmentRepository
import com.chessprobe.repository.clustering.PositionClusterRepository
import com.chessprobe.repository.clustering.PositionClusterSnapshotRepository
import com.chessprobe.service.clustering.CommunityWeaknessService
import com.chessprobe.service.clustering.PositionClusteringService
import com.chessprobe.service.similarity.SimilarityConcurrencyManager
import com.chessprobe.service.weakness.RecurringWeaknessDiscoveryService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class RecurringWeaknessDiscoveryFaultInjectionTest {
    private val occurrenceRepository: PositionOccurrenceRepository = mock()
    private val engineAnalysisRepository: EngineAnalysisRepository = mock()
    private val snapshotRepository: PositionClusterSnapshotRepository = mock()
    private val clusterRepository: PositionClusterRepository = mock()
    private val assignmentRepository: PositionClusterAssignmentRepository = mock()
    private val communityWeaknessService: CommunityWeaknessService = mock()
    private val concurrencyManager: SimilarityConcurrencyManager = mock()
    private val clusteringService: PositionClusteringService = mock()

    private lateinit var service: RecurringWeaknessDiscoveryService

    private val testAccount =
        ChessAccount(
            id = UUID.randomUUID(),
            platform = "CHESS_COM",
            username = "fault_injection_tester",
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
    fun `injected runtime exception during snapshot lookup transitions job to FAILED and never COMPLETED`() {
        whenever(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                eq(testAccount.id),
                any(),
                eq(PositionClusterSnapshotStatus.ACTIVE),
            ),
        ).thenThrow(RuntimeException("Simulated cluster failure during snapshot lookup"))

        val request = RecurringDiscoveryRequest(timeControls = listOf("RAPID"))
        val response = service.triggerDiscovery(testAccount, request)

        val completed = service.awaitJobCompletion(response.jobId, timeoutMs = 5000)
        assertTrue(completed, "Job should reach terminal state within timeout")

        val terminalJob = service.getJob(response.jobId)
        assertNotNull(terminalJob)
        assertEquals(DiscoveryJobStatus.FAILED.name, terminalJob?.status)
        assertEquals(DiscoveryStage.FAILED.name, terminalJob?.stage)
        assertNotEquals(DiscoveryJobStatus.COMPLETED.name, terminalJob?.status)
        assertTrue(terminalJob?.errorMessage?.contains("Simulated cluster failure during snapshot lookup") == true)
    }

    @Test
    fun `injected failure during on-demand clustering transitions job to FAILED with clustering failure reason`() {
        whenever(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                eq(testAccount.id),
                any(),
                eq(PositionClusterSnapshotStatus.ACTIVE),
            ),
        ).thenReturn(null)

        whenever(
            clusteringService.getOrGenerateActiveSnapshot(
                eq(testAccount.id),
                any(),
            ),
        ).thenThrow(IllegalStateException("Clustering pipeline convergence failed"))

        val request = RecurringDiscoveryRequest(timeControls = listOf("RAPID"))
        val response = service.triggerDiscovery(testAccount, request)

        val completed = service.awaitJobCompletion(response.jobId, timeoutMs = 5000)
        assertTrue(completed, "Job should reach terminal state within timeout")

        val terminalJob = service.getJob(response.jobId)
        assertNotNull(terminalJob)
        assertEquals(DiscoveryJobStatus.FAILED.name, terminalJob?.status)
        assertEquals(DiscoveryStage.FAILED.name, terminalJob?.stage)
        assertNotEquals(DiscoveryJobStatus.COMPLETED.name, terminalJob?.status)
        assertTrue(terminalJob?.errorMessage?.contains("Clustering generation failed") == true)
        assertTrue(terminalJob?.errorMessage?.contains("Clustering pipeline convergence failed") == true)
    }

    @Test
    fun `injected cluster load exception transitions job to FAILED without leaking partial state`() {
        val snapshot =
            PositionClusterSnapshot(
                id = UUID.randomUUID(),
                accountId = testAccount.id,
                snapshotVersion = 1,
                clusterType = ClusterType.STRUCTURAL,
                status = PositionClusterSnapshotStatus.ACTIVE,
            )

        whenever(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                eq(testAccount.id),
                eq(ClusterType.STRUCTURAL),
                eq(PositionClusterSnapshotStatus.ACTIVE),
            ),
        ).thenReturn(snapshot)

        whenever(
            clusterRepository.findBySnapshotIdOrderByClusterIndexAsc(eq(snapshot.id)),
        ).thenThrow(RuntimeException("Disk corruption reading candidate clusters"))

        val request = RecurringDiscoveryRequest(clusterType = ClusterType.STRUCTURAL, timeControls = listOf("RAPID"))
        val response = service.triggerDiscovery(testAccount, request)

        val completed = service.awaitJobCompletion(response.jobId, timeoutMs = 5000)
        assertTrue(completed, "Job should reach terminal state within timeout")

        val terminalJob = service.getJob(response.jobId)
        assertNotNull(terminalJob)
        assertEquals(DiscoveryJobStatus.FAILED.name, terminalJob?.status)
        assertEquals(DiscoveryStage.FAILED.name, terminalJob?.stage)
        assertNotEquals(DiscoveryJobStatus.COMPLETED.name, terminalJob?.status)
        assertTrue(terminalJob?.errorMessage?.contains("Disk corruption reading candidate clusters") == true)
    }
}
