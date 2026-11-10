package com.chessecho.service.weakness

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.CommunityPatternClassification
import com.chessecho.domain.DecisionSeverity
import com.chessecho.domain.DiscoveryJobStatus
import com.chessecho.domain.DiscoveryStage
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MistakeSeverity
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.TimeControl
import com.chessecho.domain.clustering.ClusterType
import com.chessecho.domain.clustering.PositionCluster
import com.chessecho.domain.clustering.PositionClusterSnapshot
import com.chessecho.domain.clustering.PositionClusterSnapshotStatus
import com.chessecho.dto.AcceptableMove
import com.chessecho.dto.ClusterMemberPositionDto
import com.chessecho.dto.ClusterMemberPositionsResponse
import com.chessecho.dto.RecurringDiscoveryJobResponse
import com.chessecho.dto.RecurringDiscoveryRequest
import com.chessecho.dto.RecurringWeaknessResponse
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.clustering.PositionClusterAssignmentRepository
import com.chessecho.repository.clustering.PositionClusterRepository
import com.chessecho.repository.clustering.PositionClusterSnapshotRepository
import com.chessecho.service.DecisionTimingPolicy
import com.chessecho.service.EvaluationLossPolicy
import com.chessecho.service.ForbiddenAccountException
import com.chessecho.service.ObjectiveEvidenceState
import com.chessecho.service.WeaknessPriorityPolicy
import com.chessecho.service.clustering.CommunityWeaknessProfile
import com.chessecho.service.clustering.CommunityWeaknessService
import com.chessecho.service.clustering.PositionClusteringService
import com.chessecho.service.similarity.SimilarityConcurrencyManager
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
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
    @Volatile var warningMessage: String? = null,
    val cancellationRequested: AtomicBoolean = AtomicBoolean(false),
    val completionLatch: CountDownLatch = CountDownLatch(1),
    val createdAt: Instant = Instant.now(),
    @Volatile var updatedAt: Instant = Instant.now(),
)

private sealed class SnapshotGenerationResult {
    data class Success(
        val snapshot: PositionClusterSnapshot,
    ) : SnapshotGenerationResult()

    data object Unavailable : SnapshotGenerationResult()

    data class Failed(
        val exception: Exception,
    ) : SnapshotGenerationResult()
}

private data class ResolvedSnapshotsResult(
    val snapshots: List<PositionClusterSnapshot>,
    val onDemandRan: Boolean,
    val failedTypes: List<Pair<ClusterType, Exception>>,
    val unavailableTypes: List<ClusterType>,
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
    private val weaknessPriorityPolicy: WeaknessPriorityPolicy? = null,
    private val decisionTimingPolicy: DecisionTimingPolicy = DecisionTimingPolicy(),
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
        synchronized(jobState) {
            if (jobState.status.isTerminal()) {
                throw IllegalStateException("Cannot cancel job in terminal state ${jobState.status}")
            }

            jobState.cancellationRequested.set(true)
            jobState.status = DiscoveryJobStatus.CANCELLED
            jobState.stage = DiscoveryStage.CANCELLED
            jobState.updatedAt = Instant.now()
            activeAccountJobs.remove(account.id, jobId)
            jobState.completionLatch.countDown()
        }

        return toResponse(jobState)
    }

    fun getLatestRecurringWeaknesses(account: ChessAccount): List<RecurringWeaknessResponse> = cachedWeaknesses[account.id].orEmpty()

    fun clearCache(accountId: UUID) {
        cachedWeaknesses.remove(accountId)
    }

    fun getClusterAccountId(clusterId: UUID): UUID? {
        val cluster = clusterRepository.findById(clusterId).orElse(null) ?: return null
        val snapshot = snapshotRepository.findById(cluster.snapshotId).orElse(null) ?: return null
        return snapshot.accountId
    }

    fun getClusterPositions(
        clusterId: UUID,
        account: ChessAccount,
        page: Int = 0,
        size: Int = 20,
        playableOnly: Boolean = false,
    ): ClusterMemberPositionsResponse {
        val cluster =
            clusterRepository.findById(clusterId).orElse(null)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Cluster $clusterId not found")
        val snapshot =
            snapshotRepository.findById(cluster.snapshotId).orElse(null)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Cluster snapshot not found")

        if (snapshot.accountId != account.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Cluster $clusterId not found for account ${account.id}")
        }

        val assignments = assignmentRepository.findByClusterId(clusterId)
        val positionIds = assignments.map { it.positionId }.distinct()
        if (positionIds.isEmpty()) {
            return emptyClusterPositionsResponse(page, size)
        }

        val occurrences = occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(account.id, positionIds)
        val analyses =
            engineAnalysisRepository
                .findByPositionIdInWithMoveEvaluations(positionIds.toSet())
                .associateBy { it.position.id }

        val allItems =
            occurrences.mapNotNull { occ ->
                toMemberPositionDto(occ, analyses[occ.position.id], playableOnly)
            }

        return paginatePositions(allItems, page, size)
    }

    private fun toMemberPositionDto(
        occ: PositionOccurrence,
        analysis: EngineAnalysis?,
        playableOnly: Boolean,
    ): ClusterMemberPositionDto? {
        val hasAnalysis = !analysis?.bestMove.isNullOrBlank()
        if (playableOnly && !hasAnalysis) return null

        val moveEval = analysis?.moveEvaluations?.firstOrNull { it.move == occ.movePlayed }
        val evalLoss = moveEval?.evalLossFromBest
        val mistakeSeverity = if (hasAnalysis) EvaluationLossPolicy.classifyMistakeSeverity(evalLoss) else null
        val acceptableMoves =
            if (hasAnalysis) {
                analysis
                    ?.moveEvaluations
                    ?.filter { (it.evalLossFromBest ?: Double.MAX_VALUE) <= 0.80 }
                    ?.map { AcceptableMove(it.move, it.evalLossFromBest ?: 0.0) }
            } else {
                null
            }

        val timeControlEnum = occ.game.timeControl?.let { TimeControl.fromExternal(it) }
        val decisionSeverity =
            if (occ.decisionTimeMs != null) {
                decisionTimingPolicy.classify(timeControlEnum, occ.decisionTimeMs)
            } else {
                DecisionSeverity.UNKNOWN
            }

        return ClusterMemberPositionDto(
            positionId = occ.position.id,
            fen = occ.position.fen,
            playerColor = occ.playerColor,
            movePlayed = occ.movePlayed,
            targetMove = analysis?.bestMove,
            acceptableMoves = acceptableMoves,
            evalLoss = evalLoss,
            mistakeSeverity = mistakeSeverity,
            decisionTimeMs = occ.decisionTimeMs,
            decisionSeverity = decisionSeverity,
            timeControl = occ.game.timeControl?.uppercase(),
            gameId = occ.game.id,
            platformGameId = occ.game.platformGameId,
            plyNumber = occ.plyNumber,
            moveNumber = (occ.plyNumber + 1) / 2,
        )
    }

    private fun runPipeline(
        jobState: DiscoveryJobState,
        account: ChessAccount,
        request: RecurringDiscoveryRequest,
    ) {
        try {
            if (!transition(jobState, DiscoveryJobStatus.PROCESSING, DiscoveryStage.INITIALIZING, 10)) return
            if (isCancelled(jobState)) return

            val resolved = resolveSnapshots(jobState, account.id, request.clusterType)
            if (isCancelled(jobState)) return

            if (resolved.snapshots.isEmpty()) {
                handleEmptySnapshots(jobState, account.id, resolved)
                return
            }

            if (!transition(jobState, DiscoveryJobStatus.PROCESSING, DiscoveryStage.INGESTION, if (resolved.onDemandRan) 50 else 30)) return
            if (isCancelled(jobState)) return

            val candidateClusters = loadCandidateClustersForSnapshots(resolved.snapshots)
            if (candidateClusters.isEmpty()) {
                completeJob(jobState, account.id, emptyList(), buildPartialWarning(resolved))
                return
            }

            processPipelineEvaluation(jobState, account, candidateClusters, request, resolved)
        } catch (e: Exception) {
            failJob(jobState, account.id, e.message ?: "Unknown error")
        }
    }

    private fun handleEmptySnapshots(
        jobState: DiscoveryJobState,
        accountId: UUID,
        resolved: ResolvedSnapshotsResult,
    ) {
        if (resolved.failedTypes.isNotEmpty()) {
            val reasons = resolved.failedTypes.joinToString("; ") { "${it.first}: ${it.second.message ?: "clustering failed"}" }
            failJob(jobState, accountId, "Clustering generation failed: $reasons")
        } else {
            completeJob(jobState, accountId, emptyList())
        }
    }

    private fun loadCandidateClustersForSnapshots(snapshots: List<PositionClusterSnapshot>): List<Pair<PositionCluster, ClusterType>> =
        snapshots.flatMap { snapshot ->
            loadCandidateClusters(snapshot.id).map { cluster -> cluster to snapshot.clusterType }
        }

    private fun processPipelineEvaluation(
        jobState: DiscoveryJobState,
        account: ChessAccount,
        candidateClusters: List<Pair<PositionCluster, ClusterType>>,
        request: RecurringDiscoveryRequest,
        resolved: ResolvedSnapshotsResult,
    ) {
        val results = executeEvaluationAndClustering(jobState, account, candidateClusters, request, resolved.onDemandRan)
        if (results != null && !isCancelled(jobState)) {
            completeJob(jobState, account.id, results, buildPartialWarning(resolved))
        }
    }

    private fun resolveSnapshots(
        jobState: DiscoveryJobState,
        accountId: UUID,
        requestedType: ClusterType?,
    ): ResolvedSnapshotsResult {
        val targetTypes =
            if (requestedType != null) {
                listOf(requestedType)
            } else {
                listOf(ClusterType.STRUCTURAL, ClusterType.DYNAMIC)
            }

        val snapshots = mutableListOf<PositionClusterSnapshot>()
        val failedTypes = mutableListOf<Pair<ClusterType, Exception>>()
        val unavailableTypes = mutableListOf<ClusterType>()
        var onDemandClusteringRan = false

        for ((index, type) in targetTypes.withIndex()) {
            if (isCancelled(jobState)) break
            val progress = if (index == 0) 15 else 35
            val triggered =
                resolveSnapshotForType(
                    jobState,
                    accountId,
                    type,
                    progress,
                    snapshots,
                    failedTypes,
                    unavailableTypes,
                ) ?: break
            if (triggered) {
                onDemandClusteringRan = true
            }
        }
        return ResolvedSnapshotsResult(snapshots, onDemandClusteringRan, failedTypes, unavailableTypes)
    }

    private fun resolveSnapshotForType(
        jobState: DiscoveryJobState,
        accountId: UUID,
        type: ClusterType,
        progress: Int,
        snapshots: MutableList<PositionClusterSnapshot>,
        failedTypes: MutableList<Pair<ClusterType, Exception>>,
        unavailableTypes: MutableList<ClusterType>,
    ): Boolean? {
        val existing =
            snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
                accountId = accountId,
                clusterType = type,
                status = PositionClusterSnapshotStatus.ACTIVE,
            )
        if (existing != null) {
            snapshots.add(existing)
            return false
        }
        if (!transition(jobState, DiscoveryJobStatus.PROCESSING, DiscoveryStage.CLUSTERING, progress)) {
            return null
        }
        collectGeneratedSnapshot(accountId, type, snapshots, failedTypes, unavailableTypes)
        return true
    }

    private fun collectGeneratedSnapshot(
        accountId: UUID,
        type: ClusterType,
        snapshots: MutableList<PositionClusterSnapshot>,
        failedTypes: MutableList<Pair<ClusterType, Exception>>,
        unavailableTypes: MutableList<ClusterType>,
    ) {
        when (val result = generateSnapshotSafely(accountId, type)) {
            is SnapshotGenerationResult.Success -> snapshots.add(result.snapshot)
            is SnapshotGenerationResult.Failed -> failedTypes.add(type to result.exception)
            is SnapshotGenerationResult.Unavailable -> unavailableTypes.add(type)
        }
    }

    private fun generateSnapshotSafely(
        accountId: UUID,
        type: ClusterType,
    ): SnapshotGenerationResult =
        try {
            val generated = clusteringService?.getOrGenerateActiveSnapshot(accountId, type)
            if (generated == null) {
                logger.warn("On-demand snapshot generation returned null for account {} and type {}", accountId, type)
                SnapshotGenerationResult.Unavailable
            } else {
                SnapshotGenerationResult.Success(generated)
            }
        } catch (e: Exception) {
            logger.warn("On-demand snapshot generation failed for account {} and type {}", accountId, type, e)
            SnapshotGenerationResult.Failed(e)
        }

    private fun loadCandidateClusters(snapshotId: UUID): List<PositionCluster> =
        clusterRepository
            .findBySnapshotIdOrderByClusterIndexAsc(snapshotId)
            .filter { it.size >= MIN_COMMUNITY_SIZE }

    private fun executeEvaluationAndClustering(
        jobState: DiscoveryJobState,
        account: ChessAccount,
        candidateClusters: List<Pair<PositionCluster, ClusterType>>,
        request: RecurringDiscoveryRequest,
        onDemandRan: Boolean,
    ): List<RecurringWeaknessResponse>? {
        var results: List<RecurringWeaknessResponse>? = null
        val executed =
            executeWithPermitBackoff {
                if (isCancelled(jobState)) return@executeWithPermitBackoff
                transition(jobState, DiscoveryJobStatus.PROCESSING, DiscoveryStage.EVALUATION, if (onDemandRan) 70 else 60)
                val processed = evaluateClusters(jobState, account.id, candidateClusters, request)
                if (!isCancelled(jobState)) {
                    transition(jobState, DiscoveryJobStatus.PROCESSING, DiscoveryStage.PROFILING, if (onDemandRan) 90 else 85)
                    results = sortWeaknesses(processed)
                }
            }

        if (!executed && !isCancelled(jobState)) {
            failJob(jobState, account.id, "CAPACITY_EXCEEDED")
            return null
        }
        return results
    }

    private fun evaluateClusters(
        jobState: DiscoveryJobState,
        accountId: UUID,
        candidateClusters: List<Pair<PositionCluster, ClusterType>>,
        request: RecurringDiscoveryRequest,
    ): List<RecurringWeaknessResponse> {
        val recurringResults = mutableListOf<RecurringWeaknessResponse>()
        for ((cluster, clusterType) in candidateClusters) {
            if (isCancelled(jobState)) break
            processCluster(cluster, clusterType, accountId, request)?.let { recurringResults.add(it) }
        }
        return recurringResults
    }

    private fun sortWeaknesses(results: List<RecurringWeaknessResponse>): List<RecurringWeaknessResponse> =
        results.sortedWith(
            compareByDescending<RecurringWeaknessResponse> { it.recommendationPriority }
                .thenByDescending { it.mistakeCount }
                .thenByDescending { it.memberPositionCount }
                .thenBy { it.clusterId },
        )

    private fun processCluster(
        cluster: PositionCluster,
        clusterType: ClusterType,
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

        if (!matchesFilterCriteria(profile, evalLossMap, request)) {
            return null
        }

        val repFen = occurrences.firstOrNull()?.position?.fen ?: ""
        val recommendationPriority = computeRecommendationPriority(profile)

        return toWeaknessResponse(
            profile = profile,
            repFen = repFen,
            clusterType = clusterType,
            recommendationPriority = recommendationPriority,
        )
    }

    private fun computeRecommendationPriority(profile: CommunityWeaknessProfile): Double {
        val objectivePriority = profile.blunderRate
        val decision =
            weaknessPriorityPolicy?.evaluate(
                ObjectiveEvidenceState.INACCURATE,
                objectivePriority,
                null,
            )
        return decision?.recommendationPriority ?: objectivePriority
    }

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

    private fun transition(
        jobState: DiscoveryJobState,
        status: DiscoveryJobStatus,
        stage: DiscoveryStage,
        progress: Int,
    ): Boolean =
        synchronized(jobState) {
            if (jobState.status.isTerminal() || isCancelled(jobState)) {
                return@synchronized false
            }
            if (!jobState.status.canTransitionTo(status) && jobState.status != status) {
                return@synchronized false
            }
            jobState.status = status
            jobState.stage = stage
            jobState.progressPercent = maxOf(jobState.progressPercent, progress)
            jobState.updatedAt = Instant.now()
            true
        }

    private fun completeJob(
        jobState: DiscoveryJobState,
        accountId: UUID,
        results: List<RecurringWeaknessResponse>,
        warning: String? = null,
    ) = synchronized(jobState) {
        if (jobState.status.isTerminal() || isCancelled(jobState)) {
            return@synchronized
        }
        cachedWeaknesses[accountId] = results
        jobState.status = DiscoveryJobStatus.COMPLETED
        jobState.stage = DiscoveryStage.COMPLETE
        jobState.progressPercent = 100
        jobState.warningMessage = warning
        jobState.updatedAt = Instant.now()
        activeAccountJobs.remove(accountId, jobState.jobId)
        jobState.completionLatch.countDown()
    }

    private fun failJob(
        jobState: DiscoveryJobState,
        accountId: UUID,
        error: String,
    ) = synchronized(jobState) {
        if (jobState.status.isTerminal() || isCancelled(jobState)) {
            return@synchronized
        }
        jobState.status = DiscoveryJobStatus.FAILED
        jobState.stage = DiscoveryStage.FAILED
        jobState.errorMessage = error
        jobState.updatedAt = Instant.now()
        activeAccountJobs.remove(accountId, jobState.jobId)
        jobState.completionLatch.countDown()
    }

    private fun isCancelled(jobState: DiscoveryJobState): Boolean = jobState.cancellationRequested.get()

    internal fun getJobStateForTesting(jobId: UUID): DiscoveryJobState? = jobs[jobId]

    internal fun tryTransitionForTesting(
        jobState: DiscoveryJobState,
        status: DiscoveryJobStatus,
        stage: DiscoveryStage,
        progress: Int,
    ): Boolean = transition(jobState, status, stage, progress)

    internal fun completeJobForTesting(
        jobState: DiscoveryJobState,
        accountId: UUID,
        results: List<RecurringWeaknessResponse>,
        warning: String? = null,
    ) = completeJob(jobState, accountId, results, warning)

    internal fun failJobForTesting(
        jobState: DiscoveryJobState,
        accountId: UUID,
        error: String,
    ) = failJob(jobState, accountId, error)

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
        private val logger = LoggerFactory.getLogger(RecurringWeaknessDiscoveryService::class.java)
        private const val MIN_COMMUNITY_SIZE = 10
        private const val MAX_PERMIT_RETRIES = 3
        private const val BACKOFF_MS = 50L
    }
}

private fun emptyClusterPositionsResponse(
    page: Int,
    size: Int,
): ClusterMemberPositionsResponse =
    ClusterMemberPositionsResponse(
        page = page,
        size = size,
        totalElements = 0L,
        totalPages = 0,
        hasNext = false,
        positions = emptyList(),
    )

private fun paginatePositions(
    items: List<ClusterMemberPositionDto>,
    page: Int,
    size: Int,
): ClusterMemberPositionsResponse {
    val totalElements = items.size.toLong()
    val totalPages = if (size == 0) 0 else ((totalElements + size - 1) / size).toInt()
    val fromIndex = (page * size).coerceAtMost(items.size)
    val toIndex = ((page + 1) * size).coerceAtMost(items.size)
    val pagedPositions = if (fromIndex < toIndex) items.subList(fromIndex, toIndex) else emptyList()
    val hasNext = toIndex < items.size

    return ClusterMemberPositionsResponse(
        page = page,
        size = size,
        totalElements = totalElements,
        totalPages = totalPages,
        hasNext = hasNext,
        positions = pagedPositions,
    )
}

private fun matchesFilterCriteria(
    profile: CommunityWeaknessProfile,
    evalLossMap: Map<UUID, Double?>,
    request: RecurringDiscoveryRequest,
): Boolean {
    val severityMatches = request.minMistakeSeverity == null || matchesMinMistakeSeverity(profile, request.minMistakeSeverity)
    val evalLossMatches = request.minEvalLoss == null || matchesMinEvalLoss(evalLossMap, request.minEvalLoss)
    return severityMatches && evalLossMatches
}

private fun matchesMinMistakeSeverity(
    profile: CommunityWeaknessProfile,
    minSeverity: MistakeSeverity,
): Boolean {
    if (minSeverity == MistakeSeverity.UNEVALUATED) {
        return (profile.evalLossDistribution[MistakeSeverity.UNEVALUATED] ?: 0) >= 1
    }
    val targetRank = severityRank(minSeverity)
    val qualifyingCount =
        profile.evalLossDistribution.entries
            .filter { (sev, _) -> sev != MistakeSeverity.UNEVALUATED && severityRank(sev) >= targetRank }
            .sumOf { it.value }
    return qualifyingCount >= 1
}

private fun matchesMinEvalLoss(
    evalLossMap: Map<UUID, Double?>,
    minEvalLoss: Double,
): Boolean = evalLossMap.values.any { it != null && it >= minEvalLoss }

private fun severityRank(severity: MistakeSeverity): Int =
    when (severity) {
        MistakeSeverity.NO_CONCERN -> 0
        MistakeSeverity.MINOR -> 1
        MistakeSeverity.MODERATE -> 2
        MistakeSeverity.SIGNIFICANT -> 3
        MistakeSeverity.SEVERE -> 4
        MistakeSeverity.UNEVALUATED -> -1
    }

private fun toWeaknessResponse(
    profile: CommunityWeaknessProfile,
    repFen: String,
    clusterType: ClusterType = ClusterType.STRUCTURAL,
    recommendationPriority: Double = profile.blunderRate,
): RecurringWeaknessResponse =
    RecurringWeaknessResponse(
        clusterId = profile.clusterId,
        clusterType = clusterType,
        recommendationPriority = recommendationPriority,
        representativeFen = repFen,
        memberPositionCount = profile.memberPositionCount,
        totalOccurrences = profile.totalOccurrences,
        distinctGamesCount = profile.distinctGamesCount,
        mistakeCount = profile.mistakeCount,
        mistakeFrequency = profile.mistakeFrequency,
        blunderRate = profile.blunderRate,
        evaluatedOccurrences = profile.evaluatedOccurrences,
        unevaluatedOccurrences = profile.unevaluatedOccurrences,
        evalLossDistribution = profile.evalLossDistribution.mapKeys { it.key.name },
        evalLossPercentages = profile.evalLossPercentages.mapKeys { it.key.name },
        timingProfile = profile.timingProfile.mapKeys { it.key.name },
        formatBreakdown = profile.formatBreakdown,
    )

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

private fun buildPartialWarning(resolved: ResolvedSnapshotsResult): String? =
    if (resolved.failedTypes.isNotEmpty()) {
        "Partial discovery: ${resolved.failedTypes.joinToString {
            it.first.name.lowercase()
        }} clustering failed; ${resolved.snapshots.map {
            it.clusterType.name.lowercase()
        }.joinToString()} clusters refreshed."
    } else {
        null
    }

private fun toResponse(state: DiscoveryJobState): RecurringDiscoveryJobResponse =
    RecurringDiscoveryJobResponse(
        jobId = state.jobId,
        accountId = state.accountId,
        status = state.status.name,
        progressPercent = state.progressPercent,
        stage = state.stage.name,
        errorMessage = state.errorMessage,
        warningMessage = state.warningMessage,
        createdAt = state.createdAt,
        updatedAt = state.updatedAt,
    )
