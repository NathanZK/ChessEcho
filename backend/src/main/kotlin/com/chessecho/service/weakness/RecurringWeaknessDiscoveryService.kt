package com.chessecho.service.weakness

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.CommunityPatternClassification
import com.chessecho.domain.DiscoveryJobStatus
import com.chessecho.domain.DiscoveryStage
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.clustering.ClusterType
import com.chessecho.domain.clustering.PositionCluster
import com.chessecho.domain.clustering.PositionClusterSnapshot
import com.chessecho.domain.clustering.PositionClusterSnapshotStatus
import com.chessecho.dto.RecurringDiscoveryJobResponse
import com.chessecho.dto.RecurringDiscoveryRequest
import com.chessecho.dto.RecurringWeaknessResponse
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
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveryJobState(
    val jobId: UUID,
    val accountId: UUID,
    @Volatile var status: DiscoveryJobStatus = DiscoveryJobStatus.QUEUED,
    @Volatile var stage: DiscoveryStage = DiscoveryStage.INITIALIZING,
    @Volatile var progressPercent: Int = 0,
    @Volatile var errorMessage: String? = null,
    val cancellationRequested: AtomicBoolean = AtomicBoolean(false),
    val completionLatch: CountDownLatch = CountDownLatch(1),
    val createdAt: Instant = Instant.now(),
    @Volatile var updatedAt: Instant = Instant.now(),
)

@Service
class RecurringWeaknessDiscoveryService(
    private val occurrenceRepository: PositionOccurrenceRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    private val snapshotRepository: PositionClusterSnapshotRepository,
    private val clusterRepository: PositionClusterRepository,
    private val assignmentRepository: PositionClusterAssignmentRepository,
    private val communityWeaknessService: CommunityWeaknessService,
    private val concurrencyManager: SimilarityConcurrencyManager,
    private val clusteringService: PositionClusteringService? = null,
    private val executor: ExecutorService = Executors.newCachedThreadPool(),
) {
    private val jobs = ConcurrentHashMap<UUID, DiscoveryJobState>()
    private val activeAccountJobs = ConcurrentHashMap<UUID, UUID>()
    private val cachedWeaknesses = ConcurrentHashMap<UUID, List<RecurringWeaknessResponse>>()

    fun triggerDiscovery(
        account: ChessAccount,
        request: RecurringDiscoveryRequest,
    ): RecurringDiscoveryJobResponse {
        request.validate()

        val existingJobId = activeAccountJobs[account.id]
        if (existingJobId != null) {
            val existing = jobs[existingJobId]
            if (existing != null && !existing.status.isTerminal()) {
                throw IllegalStateException("An active discovery job is already in progress for account ${account.id}")
            }
        }

        val jobId = UUID.randomUUID()
        val jobState = DiscoveryJobState(jobId = jobId, accountId = account.id)
        jobs[jobId] = jobState
        activeAccountJobs[account.id] = jobId
        val initialResponse = toResponse(jobState)

        executor.submit { runPipeline(jobState, account, request) }
        return initialResponse
    }

    fun getJob(jobId: UUID): RecurringDiscoveryJobResponse? = jobs[jobId]?.let { toResponse(it) }

    fun cancelJob(
        jobId: UUID,
        account: ChessAccount,
    ): RecurringDiscoveryJobResponse {
        val jobState = jobs[jobId] ?: throw NoSuchElementException("Job $jobId not found")
        if (jobState.accountId != account.id) {
            throw ForbiddenAccountException("Job does not belong to account ${account.id}")
        }
        if (jobState.status.isTerminal()) {
            throw IllegalStateException("Cannot cancel job in terminal state ${jobState.status}")
        }

        jobState.cancellationRequested.set(true)
        jobState.status = DiscoveryJobStatus.CANCELLED
        jobState.stage = DiscoveryStage.CANCELLED
        jobState.updatedAt = Instant.now()
        activeAccountJobs.remove(account.id, jobId)
        jobState.completionLatch.countDown()

        return toResponse(jobState)
    }

    fun getLatestRecurringWeaknesses(account: ChessAccount): List<RecurringWeaknessResponse> = cachedWeaknesses[account.id].orEmpty()

    private fun runPipeline(
        jobState: DiscoveryJobState,
        account: ChessAccount,
        request: RecurringDiscoveryRequest,
    ) {
        try {
            transition(jobState, DiscoveryJobStatus.PROCESSING, DiscoveryStage.INITIALIZING, 5)
            if (isCancelled(jobState)) return

            val snapshot = resolveActiveSnapshot(account.id)
            if (snapshot == null) {
                completeJob(jobState, account.id, emptyList())
                return
            }

            transition(jobState, DiscoveryJobStatus.PROCESSING, DiscoveryStage.INGESTION, 15)
            if (isCancelled(jobState)) return

            val candidateClusters = loadCandidateClusters(snapshot.id)
            if (candidateClusters.isEmpty()) {
                completeJob(jobState, account.id, emptyList())
                return
            }

            transition(jobState, DiscoveryJobStatus.PROCESSING, DiscoveryStage.EVALUATION, 40)
            if (isCancelled(jobState)) return

            val results = executeEvaluationAndClustering(jobState, account, candidateClusters, request)
            if (results != null && !isCancelled(jobState)) {
                transition(jobState, DiscoveryJobStatus.PROCESSING, DiscoveryStage.PROFILING, 90)
                completeJob(jobState, account.id, results)
            }
        } catch (e: Exception) {
            failJob(jobState, account.id, e.message ?: "Unknown error")
        }
    }

    private fun resolveActiveSnapshot(accountId: UUID): PositionClusterSnapshot? =
        snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
            accountId = accountId,
            clusterType = ClusterType.STRUCTURAL,
            status = PositionClusterSnapshotStatus.ACTIVE,
        ) ?: snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
            accountId = accountId,
            clusterType = ClusterType.DYNAMIC,
            status = PositionClusterSnapshotStatus.ACTIVE,
        ) ?: clusteringService?.getOrGenerateActiveSnapshot(accountId, ClusterType.STRUCTURAL)

    private fun loadCandidateClusters(snapshotId: UUID): List<PositionCluster> =
        clusterRepository
            .findBySnapshotIdOrderByClusterIndexAsc(snapshotId)
            .filter { it.size >= MIN_COMMUNITY_SIZE }

    private fun executeEvaluationAndClustering(
        jobState: DiscoveryJobState,
        account: ChessAccount,
        candidateClusters: List<PositionCluster>,
        request: RecurringDiscoveryRequest,
    ): List<RecurringWeaknessResponse>? {
        val recurringResults = mutableListOf<RecurringWeaknessResponse>()
        val executed =
            executeWithPermitBackoff {
                if (isCancelled(jobState)) return@executeWithPermitBackoff
                transition(jobState, DiscoveryJobStatus.PROCESSING, DiscoveryStage.CLUSTERING, 60)
                for (cluster in candidateClusters) {
                    if (isCancelled(jobState)) break
                    val response = processCluster(cluster, account.id, request)
                    if (response != null) {
                        recurringResults.add(response)
                    }
                }
            }

        if (!executed && !isCancelled(jobState)) {
            failJob(jobState, account.id, "CAPACITY_EXCEEDED")
            return null
        }
        return recurringResults
    }

    private fun processCluster(
        cluster: PositionCluster,
        accountId: UUID,
        request: RecurringDiscoveryRequest,
    ): RecurringWeaknessResponse? {
        val assignments = assignmentRepository.findByClusterId(cluster.id)
        val positionIds = assignments.map { it.positionId }
        if (positionIds.isEmpty()) return null

        val occurrences =
            occurrenceRepository
                .findAllByChessAccountIdAndPositionIdIn(accountId, positionIds)
                .filter {
                    matchesTimeControl(it, request.timeControls) &&
                        matchesDateRange(it, request.fromDate, request.toDate)
                }
        if (occurrences.isEmpty()) return null

        val evaluations = engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(positionIds.toSet())
        val evalLossMap = mutableMapOf<UUID, Double?>()
        for (occ in occurrences) {
            val analysis = evaluations.firstOrNull { it.position.id == occ.position.id }
            val moveEval = analysis?.moveEvaluations?.firstOrNull { it.move == occ.movePlayed }
            evalLossMap[occ.id] = moveEval?.evalLossFromBest
        }

        val profile = communityWeaknessService.profileCommunity(cluster, occurrences, evalLossMap)
        if (profile == null || profile.classification != CommunityPatternClassification.RECURRING_WEAKNESS) {
            return null
        }

        val repFen = occurrences.firstOrNull()?.position?.fen ?: ""
        return toWeaknessResponse(profile, repFen)
    }

    private fun toWeaknessResponse(
        profile: CommunityWeaknessProfile,
        repFen: String,
    ): RecurringWeaknessResponse =
        RecurringWeaknessResponse(
            clusterId = profile.clusterId,
            representativeFen = repFen,
            memberPositionCount = profile.memberPositionCount,
            totalOccurrences = profile.totalOccurrences,
            distinctGamesCount = profile.distinctGamesCount,
            mistakeCount = profile.mistakeCount,
            mistakeFrequency = profile.mistakeFrequency,
            blunderRate = profile.blunderRate,
            evalLossDistribution = profile.evalLossDistribution.mapKeys { it.key.name },
            evalLossPercentages = profile.evalLossPercentages.mapKeys { it.key.name },
            timingProfile = profile.timingProfile.mapKeys { it.key.name },
            formatBreakdown = profile.formatBreakdown,
            practiceContinuationUrl = "/api/puzzles/practice?fen=$repFen&clusterId=${profile.clusterId}",
        )

    private fun executeWithPermitBackoff(action: () -> Unit): Boolean {
        var retries = 0
        while (retries < MAX_PERMIT_RETRIES) {
            val acquired =
                concurrencyManager.withPermitOrFallback(
                    onPermitAcquired = {
                        action()
                        true
                    },
                    onCapacityExceeded = { false },
                )
            if (acquired) return true
            retries++
            try {
                Thread.sleep(BACKOFF_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun matchesTimeControl(
        occ: PositionOccurrence,
        timeControls: List<String>,
    ): Boolean {
        val tc = occ.game.timeControl?.uppercase() ?: return false
        val canonicalSet = timeControls.map { it.uppercase() }.toSet()
        return tc in canonicalSet || canonicalSet.any { tc.startsWith(it) }
    }

    private fun matchesDateRange(
        occ: PositionOccurrence,
        fromDate: String?,
        toDate: String?,
    ): Boolean {
        if (fromDate.isNullOrBlank() && toDate.isNullOrBlank()) return true
        val playedAt = occ.game.playedAt ?: return false
        val from = fromDate?.let { RecurringDiscoveryRequest.parseDateBoundary(it, false) }
        val to = toDate?.let { RecurringDiscoveryRequest.parseDateBoundary(it, true) }
        if (from != null && playedAt.isBefore(from)) return false
        if (to != null && playedAt.isAfter(to)) return false
        return true
    }

    private fun transition(
        jobState: DiscoveryJobState,
        status: DiscoveryJobStatus,
        stage: DiscoveryStage,
        progress: Int,
    ) {
        jobState.status = status
        jobState.stage = stage
        jobState.progressPercent = progress
        jobState.updatedAt = Instant.now()
    }

    private fun completeJob(
        jobState: DiscoveryJobState,
        accountId: UUID,
        results: List<RecurringWeaknessResponse>,
    ) {
        cachedWeaknesses[accountId] = results
        jobState.status = DiscoveryJobStatus.COMPLETED
        jobState.stage = DiscoveryStage.COMPLETE
        jobState.progressPercent = 100
        jobState.updatedAt = Instant.now()
        activeAccountJobs.remove(accountId, jobState.jobId)
        jobState.completionLatch.countDown()
    }

    private fun failJob(
        jobState: DiscoveryJobState,
        accountId: UUID,
        error: String,
    ) {
        jobState.status = DiscoveryJobStatus.FAILED
        jobState.stage = DiscoveryStage.FAILED
        jobState.errorMessage = error
        jobState.updatedAt = Instant.now()
        activeAccountJobs.remove(accountId, jobState.jobId)
        jobState.completionLatch.countDown()
    }

    private fun isCancelled(jobState: DiscoveryJobState): Boolean = jobState.cancellationRequested.get()

    private fun toResponse(state: DiscoveryJobState): RecurringDiscoveryJobResponse =
        RecurringDiscoveryJobResponse(
            jobId = state.jobId,
            accountId = state.accountId,
            status = state.status.name,
            progressPercent = state.progressPercent,
            stage = state.stage.name,
            errorMessage = state.errorMessage,
            createdAt = state.createdAt,
            updatedAt = state.updatedAt,
        )

    fun awaitJobCompletion(
        jobId: UUID,
        timeoutMs: Long = 5000,
    ): Boolean = jobs[jobId]?.completionLatch?.await(timeoutMs, TimeUnit.MILLISECONDS) ?: false

    fun createRunningJob(account: ChessAccount): DiscoveryJobState {
        val jobId = UUID.randomUUID()
        val job =
            DiscoveryJobState(
                jobId = jobId,
                accountId = account.id,
                status = DiscoveryJobStatus.PROCESSING,
                stage = DiscoveryStage.EVALUATION,
                progressPercent = 50,
            )
        jobs[jobId] = job
        activeAccountJobs[account.id] = jobId
        return job
    }

    fun createTerminalJob(
        account: ChessAccount,
        status: DiscoveryJobStatus,
    ): DiscoveryJobState {
        val jobId = UUID.randomUUID()
        val job =
            DiscoveryJobState(
                jobId = jobId,
                accountId = account.id,
                status = status,
                stage = DiscoveryStage.COMPLETE,
                progressPercent = 100,
            )
        jobs[jobId] = job
        return job
    }

    companion object {
        private const val MIN_COMMUNITY_SIZE = 10
        private const val MAX_PERMIT_RETRIES = 3
        private const val BACKOFF_MS = 50L
    }
}
