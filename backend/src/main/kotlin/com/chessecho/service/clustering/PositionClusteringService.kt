package com.chessecho.service.clustering

import com.chessecho.config.PositionClusteringProperties
import com.chessecho.domain.clustering.ClusterType
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
import com.chessecho.service.similarity.PersonalDynamicCorpusProvider
import com.chessecho.service.similarity.PersonalPositionCorpusProvider
import com.chessecho.similarity.DynamicInvertedIndex
import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.PositionInvertedIndex
import com.chessecho.similarity.clustering.ClusterEvaluationResult
import com.chessecho.similarity.clustering.PositionClusterCentroidEvaluator
import com.chessecho.similarity.clustering.PositionClusterSimilarityScorer
import com.chessecho.similarity.clustering.PositionClusterVector
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
import kotlin.math.sqrt

@Service
class PositionClusteringService(
    private val snapshotRepository: PositionClusterSnapshotRepository,
    private val clusterRepository: PositionClusterRepository,
    private val assignmentRepository: PositionClusterAssignmentRepository,
    private val accountRepository: ChessAccountRepository,
    private val corpusProvider: PersonalPositionCorpusProvider,
    private val dynamicCorpusProvider: PersonalDynamicCorpusProvider,
    private val properties: PositionClusteringProperties,
    private val objectMapper: ObjectMapper,
    private val transactionManager: PlatformTransactionManager,
) {
    private data class AccountLockKey(
        val accountId: UUID,
        val clusterType: ClusterType,
    )

    private val activeAccountLocks = ConcurrentHashMap.newKeySet<AccountLockKey>()
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
        clusterType: ClusterType = ClusterType.STRUCTURAL,
        block: () -> T,
    ): T {
        val key = AccountLockKey(accountId, clusterType)
        if (!activeAccountLocks.add(key)) {
            throw IllegalStateException(
                "Clustering generation already in progress for account $accountId and cluster type $clusterType",
            )
        }
        return try {
            block()
        } finally {
            activeAccountLocks.remove(key)
        }
    }

    fun <T> withAccountLock(
        accountId: UUID,
        block: () -> T,
    ): T = withAccountLock(accountId, ClusterType.STRUCTURAL, block)

    fun generateClusterSnapshot(
        accountId: UUID,
        clusterType: ClusterType = ClusterType.STRUCTURAL,
        promoteToActive: Boolean = true,
    ): CompletableFuture<PositionClusterSnapshot> =
        CompletableFuture.supplyAsync {
            withAccountLock(accountId, clusterType) {
                executePipeline(accountId, clusterType, promoteToActive)
            }
        }

    fun generateClusterSnapshot(
        accountId: UUID,
        promoteToActive: Boolean,
    ): CompletableFuture<PositionClusterSnapshot> = generateClusterSnapshot(accountId, ClusterType.STRUCTURAL, promoteToActive)

    private fun executePipeline(
        accountId: UUID,
        clusterType: ClusterType,
        promoteToActive: Boolean,
    ): PositionClusterSnapshot {
        val account =
            accountRepository.findById(accountId).orElseThrow {
                IllegalArgumentException("Chess account $accountId not found")
            }

        val txTemplate = TransactionTemplate(transactionManager)
        val snapshot =
            txTemplate.execute {
                createInitialSnapshot(accountId, clusterType)
            } ?: throw IllegalStateException("Failed to initialize snapshot")

        return try {
            val positions =
                when (clusterType) {
                    ClusterType.STRUCTURAL -> corpusProvider.loadPersonalPositions(account)
                    ClusterType.DYNAMIC -> dynamicCorpusProvider.loadPersonalDynamicPositions(account)
                }
            if (positions.isEmpty()) {
                txTemplate.execute {
                    publishEmptySnapshot(snapshot.id, accountId, promoteToActive)
                } ?: throw IllegalStateException("Failed to publish empty snapshot")
            } else {
                val evaluation = computeClustering(positions, clusterType)
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

    private fun createInitialSnapshot(
        accountId: UUID,
        clusterType: ClusterType,
    ): PositionClusterSnapshot {
        val maxVersion = snapshotRepository.findMaxVersionByAccountIdAndClusterType(accountId, clusterType)
        val snapshot =
            PositionClusterSnapshot(
                accountId = accountId,
                snapshotVersion = maxVersion + 1,
                clusterType = clusterType,
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
            snapshotRepository.updateStatusForAccountAndClusterType(
                accountId = accountId,
                clusterType = snapshot.clusterType,
                oldStatus = PositionClusterSnapshotStatus.ACTIVE,
                newStatus = PositionClusterSnapshotStatus.SUPERSEDED,
                currentVersion = snapshot.snapshotVersion,
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

    fun computeClustering(
        positions: List<PositionDocument>,
        clusterType: ClusterType = ClusterType.STRUCTURAL,
    ): ClusterEvaluationResult =
        when (clusterType) {
            ClusterType.STRUCTURAL -> computeStructuralClustering(positions)
            ClusterType.DYNAMIC -> computeDynamicClustering(positions)
        }

    private fun computeStructuralClustering(positions: List<PositionDocument>): ClusterEvaluationResult {
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

    private fun computeDynamicClustering(positions: List<PositionDocument>): ClusterEvaluationResult {
        val vectors = buildDynamicVectors(positions)
        val candidateMap = buildDynamicCandidateMap(positions)

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

    fun buildDynamicVectors(positions: List<PositionDocument>): List<PositionClusterVector> {
        val evaluatedPositions = positions.filter { it.terms.isNotEmpty() }
        val totalDocs = evaluatedPositions.size
        val dfMap = mutableMapOf<String, Int>()
        evaluatedPositions.forEach { p ->
            p.terms.keys.forEach { t -> dfMap.merge(t, 1, Int::plus) }
        }
        val idfMap =
            dfMap.mapValues { (_, df) ->
                ln(1.0 + ((totalDocs - df + 0.5) / (df + 0.5)))
            }

        return positions.map { doc ->
            buildSingleDynamicVector(doc, idfMap)
        }
    }

    private fun buildSingleDynamicVector(
        doc: PositionDocument,
        idfMap: Map<String, Double>,
    ): PositionClusterVector {
        if (doc.terms.isEmpty()) {
            return PositionClusterVector(
                positionId = doc.positionId,
                canonicalFen = doc.canonicalFen,
                rawTerms = emptyMap(),
                weightedTerms = emptyMap(),
                pawnSubvectorNorm = 0.0,
                pieceSubvectorNorm = 0.0,
                totalNorm = 0.0,
            )
        }
        val weighted = mutableMapOf<String, Double>()
        doc.terms.forEach { (term, tf) ->
            val idf = idfMap[term] ?: 0.0
            val w = tf * idf
            if (w > 0.0) weighted[term] = w
        }
        val rawNorm = sqrt(weighted.values.sumOf { it * it })
        if (rawNorm == 0.0) {
            return PositionClusterVector(
                positionId = doc.positionId,
                canonicalFen = doc.canonicalFen,
                rawTerms = doc.terms,
                weightedTerms = emptyMap(),
                pawnSubvectorNorm = 0.0,
                pieceSubvectorNorm = 0.0,
                totalNorm = 0.0,
            )
        }
        val normalized = weighted.mapValues { (_, w) -> w / rawNorm }
        return PositionClusterVector(
            positionId = doc.positionId,
            canonicalFen = doc.canonicalFen,
            rawTerms = doc.terms,
            weightedTerms = normalized,
            pawnSubvectorNorm = 0.0,
            pieceSubvectorNorm = 1.0,
            totalNorm = 1.0,
        )
    }

    private fun buildDynamicCandidateMap(positions: List<PositionDocument>): Map<UUID, List<UUID>> {
        val evaluatedDocs = positions.filter { it.terms.isNotEmpty() }
        if (evaluatedDocs.isEmpty()) return emptyMap()

        val dynamicIndex = DynamicInvertedIndex(evaluatedDocs)
        return positions.associate { doc ->
            if (doc.terms.isEmpty()) {
                doc.positionId to emptyList()
            } else {
                val candidates =
                    dynamicIndex.retrieveCandidates(
                        queryTerms = doc.terms.keys,
                        activeTurn = doc.sideToMove,
                        maxCandidates = properties.candidateLimit,
                    )
                doc.positionId to candidates.map { it.positionId }
            }
        }
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
            snapshotRepository.updateStatusForAccountAndClusterType(
                accountId = accountId,
                clusterType = snapshot.clusterType,
                oldStatus = PositionClusterSnapshotStatus.ACTIVE,
                newStatus = PositionClusterSnapshotStatus.SUPERSEDED,
                currentVersion = snapshot.snapshotVersion,
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

    fun getActiveSnapshot(
        accountId: UUID,
        clusterType: ClusterType = ClusterType.STRUCTURAL,
    ): PositionClusterSnapshot? =
        snapshotRepository.findByAccountIdAndClusterTypeAndStatus(
            accountId,
            clusterType,
            PositionClusterSnapshotStatus.ACTIVE,
        )

    fun getActiveSnapshot(accountId: UUID): PositionClusterSnapshot? = getActiveSnapshot(accountId, ClusterType.STRUCTURAL)

    fun getOrGenerateActiveSnapshot(
        accountId: UUID,
        clusterType: ClusterType = ClusterType.STRUCTURAL,
    ): PositionClusterSnapshot =
        getActiveSnapshot(accountId, clusterType)
            ?: generateClusterSnapshot(accountId, clusterType, promoteToActive = true).join()

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
