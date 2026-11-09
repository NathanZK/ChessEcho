package com.chessecho.service.weakness

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.CommunityPatternClassification
import com.chessecho.domain.DiscoveryJobStatus
import com.chessecho.domain.DiscoveryStage
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MistakeSeverity
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.clustering.ClusterType
import com.chessecho.domain.clustering.PositionCluster
import com.chessecho.domain.clustering.PositionClusterAssignment
import com.chessecho.domain.clustering.PositionClusterAssignmentId
import com.chessecho.domain.clustering.PositionClusterAssignmentStatus
import com.chessecho.domain.clustering.PositionClusterSnapshot
import com.chessecho.domain.clustering.PositionClusterSnapshotStatus
import com.chessecho.dto.RecurringDiscoveryRequest
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.clustering.PositionClusterAssignmentRepository
import com.chessecho.repository.clustering.PositionClusterRepository
import com.chessecho.repository.clustering.PositionClusterSnapshotRepository
import com.chessecho.service.ForbiddenAccountException
import com.chessecho.service.clustering.CommunityWeaknessProfile
import com.chessecho.service.clustering.CommunityWeaknessService
import com.chessecho.service.clustering.PositionClusteringService
import com.chessecho.service.similarity.SimilarityConcurrencyManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

class RecurringWeaknessDiscoveryServiceTest {
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
    fun `triggerDiscovery starts job and transitions to COMPLETED with recurring weaknesses`() {
        val snapshotId = UUID.randomUUID()
        val clusterId = UUID.randomUUID()
        val posId = UUID.randomUUID()

        val cluster = mockSnapshotAndCluster(snapshotId, clusterId, posId)
        val occurrences = mockOccurrences(posId)
        mockConcurrencyAndProfile(cluster, occurrences)

        val request = RecurringDiscoveryRequest(timeControls = listOf("RAPID"))
        val response = service.triggerDiscovery(testAccount, request)

        assertEquals(DiscoveryJobStatus.QUEUED.name, response.status)

        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val completedJob = service.getJob(response.jobId)
        assertNotNull(completedJob)
        assertEquals(DiscoveryJobStatus.COMPLETED.name, completedJob!!.status)
        assertEquals(DiscoveryStage.COMPLETE.name, completedJob.stage)
        assertEquals(100, completedJob.progressPercent)

        val weaknesses = service.getLatestRecurringWeaknesses(testAccount)
        assertEquals(1, weaknesses.size)
        assertEquals(clusterId, weaknesses[0].clusterId)
        assertEquals(2, weaknesses[0].distinctGamesCount)
    }

    private fun mockSnapshotAndCluster(
        snapshotId: UUID,
        clusterId: UUID,
        posId: UUID,
    ): PositionCluster {
        val snapshot =
            PositionClusterSnapshot(
                id = snapshotId,
                accountId = testAccount.id,
                snapshotVersion = 1,
                clusterType = ClusterType.STRUCTURAL,
                status = PositionClusterSnapshotStatus.ACTIVE,
            )
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                ClusterType.STRUCTURAL,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(snapshot)

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

    private fun mockOccurrences(posId: UUID): List<PositionOccurrence> {
        val game1 = Game(id = UUID.randomUUID(), chessAccount = testAccount, platformGameId = "g1", pgn = "1. e4", timeControl = "RAPID")
        val game2 = Game(id = UUID.randomUUID(), chessAccount = testAccount, platformGameId = "g2", pgn = "1. d4", timeControl = "RAPID")
        val position = Position(id = posId, hash = "h1", fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3")
        val occ1 = createOccurrence(game1, position)
        val occ2 = createOccurrence(game2, position)
        val occurrences = listOf(occ1, occ2)
        `when`(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(testAccount.id, listOf(posId))).thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(posId))).thenReturn(emptyList())
        return occurrences
    }

    private fun createOccurrence(
        game: Game,
        position: Position,
    ): PositionOccurrence =
        PositionOccurrence(
            id = UUID.randomUUID(),
            game = game,
            position = position,
            chessAccount = testAccount,
            plyNumber = 5,
            movePlayed = "Nf3",
            playerColor = "WHITE",
        )

    private fun mockConcurrencyAndProfile(
        cluster: PositionCluster,
        occurrences: List<PositionOccurrence>,
        customLossMap: Map<UUID, Double?>? = null,
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
                formatBreakdown = mapOf("RAPID" to 2),
            )
        val evalLossMap = customLossMap ?: occurrences.associate { it.id to null }
        `when`(communityWeaknessService.profileCommunity(cluster, occurrences, evalLossMap)).thenReturn(profile)
    }

    @Test
    fun `recurrence gate excludes single-game clusters and small clusters`() {
        val snapshotId = UUID.randomUUID()
        val snapshot =
            PositionClusterSnapshot(
                id = snapshotId,
                accountId = testAccount.id,
                snapshotVersion = 1,
                clusterType = ClusterType.STRUCTURAL,
                status = PositionClusterSnapshotStatus.ACTIVE,
            )
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                ClusterType.STRUCTURAL,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(snapshot)

        val smallClusterId = UUID.randomUUID()
        val smallCluster =
            PositionCluster(
                id = smallClusterId,
                snapshotId = snapshotId,
                clusterIndex = 0,
                size = 5,
                centroidVector = "{}",
            )
        `when`(clusterRepository.findBySnapshotIdOrderByClusterIndexAsc(snapshotId)).thenReturn(listOf(smallCluster))

        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest())
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val weaknesses = service.getLatestRecurringWeaknesses(testAccount)
        assertTrue(weaknesses.isEmpty())
    }

    @Test
    fun `concurrent discovery trigger on same account throws IllegalStateException`() {
        val job = service.createRunningJob(testAccount)

        assertThrows(IllegalStateException::class.java) {
            service.triggerDiscovery(testAccount, RecurringDiscoveryRequest())
        }

        service.cancelJob(job.jobId, testAccount)
    }

    @Test
    fun `cancelJob stops active job and marks CANCELLED`() {
        val job = service.createRunningJob(testAccount)

        val cancelled = service.cancelJob(job.jobId, testAccount)
        assertEquals(DiscoveryJobStatus.CANCELLED.name, cancelled.status)
        assertEquals(DiscoveryStage.CANCELLED.name, cancelled.stage)
    }

    @Test
    fun `cancelling terminal job throws IllegalStateException`() {
        val job = service.createTerminalJob(testAccount, DiscoveryJobStatus.COMPLETED)

        assertThrows(IllegalStateException::class.java) {
            service.cancelJob(job.jobId, testAccount)
        }
    }

    @Test
    fun `triggerDiscovery with invalid request throws IllegalArgumentException`() {
        val invalidRequest = RecurringDiscoveryRequest(timeControls = listOf("INVALID_TC"))
        assertThrows(IllegalArgumentException::class.java) {
            service.triggerDiscovery(testAccount, invalidRequest)
        }
    }

    @Test
    fun `triggerDiscovery succeeds when prior job is in terminal state`() {
        service.createTerminalJob(testAccount, DiscoveryJobStatus.COMPLETED)
        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest())
        assertEquals(DiscoveryJobStatus.QUEUED.name, response.status)
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)
    }

    @Test
    fun `cancelJob with unknown jobId throws NoSuchElementException`() {
        assertThrows(NoSuchElementException::class.java) {
            service.cancelJob(UUID.randomUUID(), testAccount)
        }
    }

    @Test
    fun `cancelJob with mismatched account throws ForbiddenAccountException`() {
        val otherAccount = ChessAccount(id = UUID.randomUUID(), platform = "CHESS_COM", username = "other")
        val job = service.createRunningJob(testAccount)
        assertThrows(ForbiddenAccountException::class.java) {
            service.cancelJob(job.jobId, otherAccount)
        }
    }

    @Test
    fun `getJob returns null for unknown jobId`() {
        assertNull(service.getJob(UUID.randomUUID()))
    }

    @Test
    fun `awaitJobCompletion returns false when jobId does not exist`() {
        assertFalse(service.awaitJobCompletion(UUID.randomUUID(), timeoutMs = 10))
    }

    @Test
    fun `discovery completes with empty list when no active snapshot exists`() {
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                ClusterType.STRUCTURAL,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(null)
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                ClusterType.DYNAMIC,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(null)
        `when`(clusteringService.getOrGenerateActiveSnapshot(testAccount.id, ClusterType.STRUCTURAL)).thenReturn(null)

        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest())
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val job = service.getJob(response.jobId)
        assertNotNull(job)
        assertEquals(DiscoveryJobStatus.COMPLETED.name, job!!.status)
        assertEquals(DiscoveryStage.COMPLETE.name, job.stage)
        assertEquals(100, job.progressPercent)
        assertTrue(service.getLatestRecurringWeaknesses(testAccount).isEmpty())
    }

    @Test
    fun `discovery triggers on-demand snapshot generation when active snapshots are absent`() {
        val snapshotId = UUID.randomUUID()
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                ClusterType.STRUCTURAL,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(null)
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                ClusterType.DYNAMIC,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(null)

        val onDemandSnapshot =
            PositionClusterSnapshot(
                id = snapshotId,
                accountId = testAccount.id,
                snapshotVersion = 1,
                clusterType = ClusterType.STRUCTURAL,
                status = PositionClusterSnapshotStatus.ACTIVE,
            )
        `when`(clusteringService.getOrGenerateActiveSnapshot(testAccount.id, ClusterType.STRUCTURAL))
            .thenReturn(onDemandSnapshot)
        `when`(clusterRepository.findBySnapshotIdOrderByClusterIndexAsc(snapshotId)).thenReturn(emptyList())

        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest())
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        verify(clusteringService).getOrGenerateActiveSnapshot(testAccount.id, ClusterType.STRUCTURAL)
        val job = service.getJob(response.jobId)
        assertNotNull(job)
        assertEquals(DiscoveryJobStatus.COMPLETED.name, job!!.status)
    }

    @Test
    fun `discovery falls back to dynamic snapshot when structural snapshot is absent`() {
        val snapshotId = UUID.randomUUID()
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                ClusterType.STRUCTURAL,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(null)
        val dynamicSnapshot =
            PositionClusterSnapshot(
                id = snapshotId,
                accountId = testAccount.id,
                snapshotVersion = 1,
                clusterType = ClusterType.DYNAMIC,
                status = PositionClusterSnapshotStatus.ACTIVE,
            )
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                ClusterType.DYNAMIC,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(dynamicSnapshot)
        `when`(clusterRepository.findBySnapshotIdOrderByClusterIndexAsc(snapshotId)).thenReturn(emptyList())

        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest())
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val job = service.getJob(response.jobId)
        assertNotNull(job)
        assertEquals(DiscoveryJobStatus.COMPLETED.name, job!!.status)
        assertEquals(DiscoveryStage.COMPLETE.name, job.stage)
    }

    @Test
    fun `discovery fails with CAPACITY_EXCEEDED when concurrency permit is denied`() {
        val snapshotId = UUID.randomUUID()
        val clusterId = UUID.randomUUID()
        val snapshot =
            PositionClusterSnapshot(
                id = snapshotId,
                accountId = testAccount.id,
                snapshotVersion = 1,
                clusterType = ClusterType.STRUCTURAL,
                status = PositionClusterSnapshotStatus.ACTIVE,
            )
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                ClusterType.STRUCTURAL,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(snapshot)
        val cluster =
            PositionCluster(id = clusterId, snapshotId = snapshotId, clusterIndex = 0, size = 15, centroidVector = "{}")
        `when`(clusterRepository.findBySnapshotIdOrderByClusterIndexAsc(snapshotId)).thenReturn(listOf(cluster))

        `when`(
            concurrencyManager.withPermitOrFallback<Boolean>(
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
            ),
        ).thenAnswer { invocation ->
            val onCapacityExceeded = invocation.getArgument<() -> Boolean>(1)
            onCapacityExceeded()
        }

        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest())
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val job = service.getJob(response.jobId)
        assertNotNull(job)
        assertEquals(DiscoveryJobStatus.FAILED.name, job!!.status)
        assertEquals(DiscoveryStage.FAILED.name, job.stage)
        assertEquals("CAPACITY_EXCEEDED", job.errorMessage)
    }

    @Test
    fun `discovery fails when repository throws exception during pipeline execution`() {
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                ClusterType.STRUCTURAL,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenThrow(RuntimeException("Database unreachable"))

        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest())
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val job = service.getJob(response.jobId)
        assertNotNull(job)
        assertEquals(DiscoveryJobStatus.FAILED.name, job!!.status)
        assertEquals(DiscoveryStage.FAILED.name, job.stage)
        assertEquals("Database unreachable", job.errorMessage)
    }

    @Test
    fun `candidate cluster boundary includes size 10 and excludes size 9`() {
        val snapshotId = UUID.randomUUID()
        val snapshot =
            PositionClusterSnapshot(
                id = snapshotId,
                accountId = testAccount.id,
                snapshotVersion = 1,
                clusterType = ClusterType.STRUCTURAL,
                status = PositionClusterSnapshotStatus.ACTIVE,
            )
        `when`(
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                testAccount.id,
                ClusterType.STRUCTURAL,
                PositionClusterSnapshotStatus.ACTIVE,
            ),
        ).thenReturn(snapshot)

        val cluster9 = PositionCluster(id = UUID.randomUUID(), snapshotId = snapshotId, clusterIndex = 0, size = 9, centroidVector = "{}")
        val cluster10 = PositionCluster(id = UUID.randomUUID(), snapshotId = snapshotId, clusterIndex = 1, size = 10, centroidVector = "{}")
        `when`(clusterRepository.findBySnapshotIdOrderByClusterIndexAsc(snapshotId)).thenReturn(listOf(cluster9, cluster10))
        `when`(assignmentRepository.findByClusterId(cluster10.id)).thenReturn(emptyList())

        `when`(
            concurrencyManager.withPermitOrFallback<Boolean>(
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
            ),
        ).thenAnswer { invocation ->
            val onPermitAcquired = invocation.getArgument<() -> Boolean>(0)
            onPermitAcquired()
        }

        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest())
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val job = service.getJob(response.jobId)
        assertNotNull(job)
        assertEquals(DiscoveryJobStatus.COMPLETED.name, job!!.status)
        assertTrue(service.getLatestRecurringWeaknesses(testAccount).isEmpty())
    }

    @Test
    fun `timeControl matching supports prefix and case-insensitivity and excludes null`() {
        val snapshotId = UUID.randomUUID()
        val clusterId = UUID.randomUUID()
        val posId = UUID.randomUUID()

        val cluster = mockSnapshotAndCluster(snapshotId, clusterId, posId)

        val gameBlitz =
            Game(id = UUID.randomUUID(), chessAccount = testAccount, platformGameId = "gBlitz", pgn = "", timeControl = "blitz+1")
        val gameNull = Game(id = UUID.randomUUID(), chessAccount = testAccount, platformGameId = "gNull", pgn = "", timeControl = null)
        val position = Position(id = posId, hash = "h1", fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3")
        val occBlitz = createOccurrence(gameBlitz, position)
        val occNull = createOccurrence(gameNull, position)

        `when`(
            occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(testAccount.id, listOf(posId)),
        ).thenReturn(listOf(occBlitz, occNull))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(posId))).thenReturn(emptyList())

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
                totalOccurrences = 1,
                distinctGamesCount = 1,
                classification = CommunityPatternClassification.SINGLE_GAME_PATTERN,
                mistakeCount = 1,
                mistakeFrequency = 100.0,
                blunderRate = 0.0,
                evalLossDistribution = emptyMap(),
                evalLossPercentages = emptyMap(),
                timingProfile = emptyMap(),
                formatBreakdown = mapOf("BLITZ" to 1),
            )
        val evalLossMap = mapOf(occBlitz.id to null)
        `when`(communityWeaknessService.profileCommunity(cluster, listOf(occBlitz), evalLossMap)).thenReturn(profile)

        val request = RecurringDiscoveryRequest(timeControls = listOf("BLITZ"))
        val response = service.triggerDiscovery(testAccount, request)
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val job = service.getJob(response.jobId)
        assertNotNull(job)
        assertEquals(DiscoveryJobStatus.COMPLETED.name, job!!.status)
        assertTrue(service.getLatestRecurringWeaknesses(testAccount).isEmpty())
    }

    @Test
    fun `processCluster maps move evaluation loss when engine analysis is present`() {
        val snapshotId = UUID.randomUUID()
        val clusterId = UUID.randomUUID()
        val posId = UUID.randomUUID()

        val cluster = mockSnapshotAndCluster(snapshotId, clusterId, posId)
        val game1 = Game(id = UUID.randomUUID(), chessAccount = testAccount, platformGameId = "g1", pgn = "1. e4", timeControl = "RAPID")
        val game2 = Game(id = UUID.randomUUID(), chessAccount = testAccount, platformGameId = "g2", pgn = "1. d4", timeControl = "RAPID")
        val position = Position(id = posId, hash = "h1", fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3")
        val occ1 = createOccurrence(game1, position)
        val occ2 = createOccurrence(game2, position)
        val occurrences = listOf(occ1, occ2)
        `when`(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(testAccount.id, listOf(posId))).thenReturn(occurrences)

        val engineAnalysis =
            EngineAnalysis(
                id = UUID.randomUUID(),
                position = position,
                depth = 18,
                bestMove = "Nf3",
            )
        val moveEval =
            MoveEvaluation(
                id = UUID.randomUUID(),
                engineAnalysis = engineAnalysis,
                move = "Nf3",
                evalLossFromBest = 150.0,
            )
        engineAnalysis.moveEvaluations.add(moveEval)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(posId))).thenReturn(listOf(engineAnalysis))

        mockConcurrencyAndProfile(cluster, occurrences, occurrences.associate { it.id to 150.0 })

        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest(timeControls = listOf("RAPID")))
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val job = service.getJob(response.jobId)
        assertNotNull(job)
        assertEquals(DiscoveryJobStatus.COMPLETED.name, job!!.status)
        val weaknesses = service.getLatestRecurringWeaknesses(testAccount)
        assertEquals(1, weaknesses.size)
    }

    @Test
    fun `dateRange filtering includes games within range and excludes games outside`() {
        val snapshotId = UUID.randomUUID()
        val clusterId = UUID.randomUUID()
        val posId = UUID.randomUUID()

        val cluster = mockSnapshotAndCluster(snapshotId, clusterId, posId)

        val gameIn1 =
            Game(
                id = UUID.randomUUID(),
                chessAccount = testAccount,
                platformGameId = "gIn1",
                pgn = "",
                timeControl = "RAPID",
                playedAt = java.time.Instant.parse("2024-03-15T10:00:00Z"),
            )
        val gameIn2 =
            Game(
                id = UUID.randomUUID(),
                chessAccount = testAccount,
                platformGameId = "gIn2",
                pgn = "",
                timeControl = "RAPID",
                playedAt = java.time.Instant.parse("2024-05-20T10:00:00Z"),
            )
        val gameOut =
            Game(
                id = UUID.randomUUID(),
                chessAccount = testAccount,
                platformGameId = "gOut",
                pgn = "",
                timeControl = "RAPID",
                playedAt = java.time.Instant.parse("2023-11-01T10:00:00Z"),
            )
        val position = Position(id = posId, hash = "h1", fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3")
        val occIn1 = createOccurrence(gameIn1, position)
        val occIn2 = createOccurrence(gameIn2, position)
        val occOut = createOccurrence(gameOut, position)

        `when`(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(testAccount.id, listOf(posId)))
            .thenReturn(listOf(occIn1, occIn2, occOut))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(posId))).thenReturn(emptyList())

        mockConcurrencyAndProfile(cluster, listOf(occIn1, occIn2))

        val request =
            RecurringDiscoveryRequest(
                timeControls = listOf("RAPID"),
                fromDate = "2024-01-01",
                toDate = "2024-06-30",
            )
        val response = service.triggerDiscovery(testAccount, request)
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        val job = service.getJob(response.jobId)
        assertNotNull(job)
        assertEquals(DiscoveryJobStatus.COMPLETED.name, job!!.status)
        val weaknesses = service.getLatestRecurringWeaknesses(testAccount)
        assertEquals(1, weaknesses.size)
    }

    @Test
    fun `clearCache invalidates cached weaknesses for account`() {
        val snapshotId = UUID.randomUUID()
        val clusterId = UUID.randomUUID()
        val posId = UUID.randomUUID()

        val cluster = mockSnapshotAndCluster(snapshotId, clusterId, posId)
        val occurrences = mockOccurrences(posId)
        mockConcurrencyAndProfile(cluster, occurrences)

        val response = service.triggerDiscovery(testAccount, RecurringDiscoveryRequest(timeControls = listOf("RAPID")))
        service.awaitJobCompletion(response.jobId, timeoutMs = 5000)

        assertFalse(service.getLatestRecurringWeaknesses(testAccount).isEmpty())

        service.clearCache(testAccount.id)

        assertTrue(service.getLatestRecurringWeaknesses(testAccount).isEmpty())
    }
}
