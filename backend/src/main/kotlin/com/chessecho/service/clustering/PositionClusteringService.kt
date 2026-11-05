package com.chessecho.service.clustering

import com.chessecho.config.PositionClusteringProperties
import com.chessecho.domain.clustering.PositionCluster
import com.chessecho.domain.clustering.PositionClusterAssignment
import com.chessecho.domain.clustering.PositionClusterAssignmentId
import com.chessecho.domain.clustering.PositionClusterAssignmentStatus
import com.chessecho.domain.clustering.PositionClusterSnapshot
import com.chessecho.domain.clustering.PositionClusterSnapshotStatus
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.clustering.PositionClusterAssignmentRepository
import com.chessecho.repository.clustering.PositionClusterRepository
import com.chessecho.repository.clustering.PositionClusterSnapshotRepository
import com.chessecho.service.similarity.PersonalPositionCorpusProvider
import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.PositionInvertedIndex
import com.chessecho.similarity.clustering.ClusterEvaluationResult
import com.chessecho.similarity.clustering.PositionClusterCentroidEvaluator
import com.chessecho.similarity.clustering.PositionClusterSimilarityScorer
import com.chessecho.similarity.clustering.PositionLeidenCommunityDetector
import com.chessecho.similarity.clustering.RelaxedMnnGraphBuilder
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln

@Service
class PositionClusteringService(
    private val snapshotRepository: PositionClusterSnapshotRepository,
    private val clusterRepository: PositionClusterRepository,
    private val assignmentRepository: PositionClusterAssignmentRepository,
    private val accountRepository: ChessAccountRepository,
    private val corpusProvider: PersonalPositionCorpusProvider,
    private val properties: PositionClusteringProperties,
    private val objectMapper: ObjectMapper,
    private val transactionManager: PlatformTransactionManager,
) {
    private val activeAccountLocks = ConcurrentHashMap.newKeySet<UUID>()
    private val scorer = PositionClusterSimilarityScorer()
    private val graphBuilder = RelaxedMnnGraphBuilder(scorer)
    private val communityDetector = PositionLeidenCommunityDetector()
    private val centroidEvaluator = PositionClusterCentroidEvaluator()

    companion object {
        private val COMPLETED_STATUSES =
            setOf(
                PositionClusterSnapshotStatus.ACTIVE,
                PositionClusterSnapshotStatus.SUPERSEDED,
                PositionClusterSnapshotStatus.EXPERIMENTAL,
            )
    }

    fun <T> withAccountLock(
        accountId: UUID,
        block: () -> T,
    ): T {
        if (!activeAccountLocks.add(accountId)) {
            throw IllegalStateException("Clustering generation already in progress for account $accountId")
        }
        return try {
            block()
        } finally {
            activeAccountLocks.remove(accountId)
        }
    }

    fun generateClusterSnapshot(
        accountId: UUID,
        promoteToActive: Boolean = true,
    ): CompletableFuture<PositionClusterSnapshot> =
        CompletableFuture.supplyAsync {
            withAccountLock(accountId) {
                executePipeline(accountId, promoteToActive)
            }
        }

    private fun executePipeline(
        accountId: UUID,
        promoteToActive: Boolean,
    ): PositionClusterSnapshot {
        val account =
            accountRepository.findById(accountId).orElseThrow {
                IllegalArgumentException("Chess account $accountId not found")
            }

        val txTemplate = TransactionTemplate(transactionManager)
        val snapshot =
            txTemplate.execute {
                createInitialSnapshot(accountId)
            } ?: throw IllegalStateException("Failed to initialize snapshot")

        return try {
            val positions = corpusProvider.loadPersonalPositions(account)
            if (positions.isEmpty()) {
                txTemplate.execute {
                    publishEmptySnapshot(snapshot.id, accountId, promoteToActive)
                } ?: throw IllegalStateException("Failed to publish empty snapshot")
            } else {
                val evaluation = computeClustering(positions)
                txTemplate.execute {
                    publishCompletedSnapshot(snapshot.id, accountId, promoteToActive, positions, evaluation)
                } ?: throw IllegalStateException("Failed to publish completed snapshot")
            }
        } catch (e: Exception) {
            txTemplate.execute {
                recordFailure(snapshot.id, e.message ?: "Unknown error")
            }
            throw e
        }
    }

    private fun createInitialSnapshot(accountId: UUID): PositionClusterSnapshot {
        val maxVersion = snapshotRepository.findMaxVersionByAccountId(accountId)
        val snapshot =
            PositionClusterSnapshot(
                accountId = accountId,
                snapshotVersion = maxVersion + 1,
                status = PositionClusterSnapshotStatus.GENERATING,
                alpha = properties.alpha,
                topK = properties.topK,
                floorThreshold = properties.floorThreshold,
                top1Threshold = properties.top1Threshold,
                resolution = properties.rbResolution,
                centroidThreshold = properties.centroidThreshold,
            )
        return snapshotRepository.save(snapshot)
    }

    private fun publishEmptySnapshot(
        snapshotId: UUID,
        accountId: UUID,
        promoteToActive: Boolean,
    ): PositionClusterSnapshot {
        val snapshot = snapshotRepository.findById(snapshotId).orElseThrow()
        if (promoteToActive) {
            snapshotRepository.updateStatusForAccount(
                accountId,
                PositionClusterSnapshotStatus.ACTIVE,
                PositionClusterSnapshotStatus.SUPERSEDED,
            )
            snapshot.status = PositionClusterSnapshotStatus.ACTIVE
        } else {
            snapshot.status = PositionClusterSnapshotStatus.EXPERIMENTAL
        }
        snapshot.totalPositions = 0
        snapshot.clusterCount = 0
        snapshot.unassignedCount = 0
        snapshot.completedAt = Instant.now()
        return snapshotRepository.save(snapshot)
    }

    private fun computeClustering(positions: List<PositionDocument>): ClusterEvaluationResult {
        val totalDocs = positions.size
        val dfMap = mutableMapOf<String, Int>()
        positions.forEach { p ->
            p.terms.keys.forEach { t -> dfMap.merge(t, 1, Int::plus) }
        }
        val idfMap =
            dfMap.mapValues { (_, df) ->
                ln(1.0 + ((totalDocs - df + 0.5) / (df + 0.5)))
            }

        val vectors =
            positions.map { doc ->
                scorer.buildVector(
                    positionId = doc.positionId,
                    canonicalFen = doc.canonicalFen,
                    terms = doc.terms,
                    idfs = idfMap,
                    alpha = properties.alpha,
                )
            }

        val invertedIndex = PositionInvertedIndex()
        invertedIndex.index(positions)

        val candidateMap =
            positions.associate { doc ->
                val candidates =
                    invertedIndex.generateCandidates(
                        queryTerms = doc.terms.keys,
                        activeTurn = doc.sideToMove,
                        maxCandidates = properties.candidateLimit,
                        alpha = properties.alpha,
                    )
                doc.positionId to candidates.map { it.positionId }
            }

        val graph =
            graphBuilder.buildGraph(
                vectors = vectors,
                candidateMap = candidateMap,
                topK = properties.topK,
                floorThreshold = properties.floorThreshold,
                top1Threshold = properties.top1Threshold,
            )

        val detectionResult =
            communityDetector.detectCommunities(
                graph = graph,
                rbResolution = properties.rbResolution,
            )

        return centroidEvaluator.evaluateClusters(
            vectors = vectors,
            communityAssignments = detectionResult.assignments,
            centroidThreshold = properties.centroidThreshold,
        )
    }

    private fun publishCompletedSnapshot(
        snapshotId: UUID,
        accountId: UUID,
        promoteToActive: Boolean,
        positions: List<PositionDocument>,
        evaluation: ClusterEvaluationResult,
    ): PositionClusterSnapshot {
        val clusterIdByIndex = mutableMapOf<Int, UUID>()
        for (c in evaluation.clusters) {
            val jsonVector = objectMapper.writeValueAsString(c.centroidVector)
            val clusterEntity =
                PositionCluster(
                    snapshotId = snapshotId,
                    clusterIndex = c.clusterIndex,
                    size = c.size,
                    centroidVector = jsonVector,
                )
            val saved = clusterRepository.save(clusterEntity)
            clusterIdByIndex[c.clusterIndex] = saved.id
        }

        val assignments =
            evaluation.assignments.map { a ->
                val clusterId = if (a.clusterIndex != null) clusterIdByIndex[a.clusterIndex] else null
                PositionClusterAssignment(
                    id = PositionClusterAssignmentId(snapshotId, a.positionId),
                    clusterId = clusterId,
                    assignmentStatus = a.status,
                    centroidSimilarity = a.centroidSimilarity,
                )
            }
        assignmentRepository.saveAll(assignments)

        val snapshot = snapshotRepository.findById(snapshotId).orElseThrow()
        if (promoteToActive) {
            snapshotRepository.updateStatusForAccount(
                accountId,
                PositionClusterSnapshotStatus.ACTIVE,
                PositionClusterSnapshotStatus.SUPERSEDED,
            )
            snapshot.status = PositionClusterSnapshotStatus.ACTIVE
        } else {
            snapshot.status = PositionClusterSnapshotStatus.EXPERIMENTAL
        }

        val unassignedCount =
            evaluation.assignments.count {
                it.status == PositionClusterAssignmentStatus.OUTLIER_EVICTED ||
                    it.status == PositionClusterAssignmentStatus.ISOLATE
            }

        snapshot.totalPositions = positions.size
        snapshot.clusterCount = evaluation.clusters.size
        snapshot.unassignedCount = unassignedCount
        snapshot.completedAt = Instant.now()
        return snapshotRepository.save(snapshot)
    }

    private fun recordFailure(
        snapshotId: UUID,
        errorMessage: String,
    ): PositionClusterSnapshot {
        val snapshot = snapshotRepository.findById(snapshotId).orElseThrow()
        snapshot.status = PositionClusterSnapshotStatus.FAILED
        snapshot.errorMessage = errorMessage
        snapshot.completedAt = Instant.now()
        return snapshotRepository.save(snapshot)
    }

    fun getActiveSnapshot(accountId: UUID): PositionClusterSnapshot? =
        snapshotRepository.findByAccountIdAndStatus(accountId, PositionClusterSnapshotStatus.ACTIVE)

    fun getClustersForSnapshot(snapshotId: UUID): List<PositionCluster> {
        val snapshot = snapshotRepository.findById(snapshotId).orElse(null) ?: return emptyList()
        if (snapshot.status !in COMPLETED_STATUSES) return emptyList()
        return clusterRepository.findBySnapshotIdOrderByClusterIndexAsc(snapshotId)
    }

    fun getPositionsForCluster(clusterId: UUID): List<PositionClusterAssignment> = assignmentRepository.findByClusterId(clusterId)

    fun getUnassignedPositions(snapshotId: UUID): List<PositionClusterAssignment> {
        val snapshot = snapshotRepository.findById(snapshotId).orElse(null) ?: return emptyList()
        if (snapshot.status !in COMPLETED_STATUSES) return emptyList()
        return assignmentRepository.findByIdSnapshotIdAndAssignmentStatusIn(
            snapshotId,
            listOf(PositionClusterAssignmentStatus.OUTLIER_EVICTED, PositionClusterAssignmentStatus.ISOLATE),
        )
    }
}
