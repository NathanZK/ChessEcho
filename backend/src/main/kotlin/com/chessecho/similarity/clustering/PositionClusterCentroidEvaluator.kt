package com.chessecho.similarity.clustering

import com.chessecho.domain.clustering.PositionClusterAssignmentStatus
import java.util.UUID
import kotlin.math.sqrt

typealias ClusterAssignmentStatus = PositionClusterAssignmentStatus

data class EvaluatedPositionAssignment(
    val positionId: UUID,
    val clusterIndex: Int?,
    val status: PositionClusterAssignmentStatus,
    val centroidSimilarity: Double?,
)

data class EvaluatedCluster(
    val clusterIndex: Int,
    val size: Int,
    val centroidVector: Map<String, Double>,
    val memberPositionIds: List<UUID>,
)

data class ClusterEvaluationResult(
    val clusters: List<EvaluatedCluster>,
    val assignments: List<EvaluatedPositionAssignment>,
)

class PositionClusterCentroidEvaluator {
    private data class CentroidInfo(
        val terms: Map<String, Double>,
        val norm: Double,
    )

    fun evaluateClusters(
        vectors: List<PositionClusterVector>,
        communityAssignments: Map<UUID, Int?>,
        centroidThreshold: Double = 0.38,
    ): ClusterEvaluationResult {
        require(centroidThreshold.isFinite() && centroidThreshold in 0.0..1.0) {
            "centroidThreshold must be finite and in [0.0, 1.0]"
        }

        if (vectors.isEmpty()) {
            return ClusterEvaluationResult(emptyList(), emptyList())
        }

        val vectorById = vectors.associateBy { it.positionId }
        val clusterMembers = mutableMapOf<Int, MutableList<PositionClusterVector>>()
        val isolates = mutableListOf<UUID>()

        for (v in vectors) {
            val cIdx = communityAssignments[v.positionId]
            if (cIdx != null) {
                clusterMembers.computeIfAbsent(cIdx) { mutableListOf() }.add(v)
            } else {
                isolates.add(v.positionId)
            }
        }

        val evaluatedClusters = mutableListOf<EvaluatedCluster>()
        val assignments = mutableListOf<EvaluatedPositionAssignment>()

        clusterMembers.toSortedMap().forEach { (cIdx, members) ->
            processCluster(cIdx, members, centroidThreshold, evaluatedClusters, assignments)
        }

        isolates.forEach { isoId ->
            assignments.add(
                EvaluatedPositionAssignment(
                    positionId = isoId,
                    clusterIndex = null,
                    status = ClusterAssignmentStatus.ISOLATE,
                    centroidSimilarity = null,
                ),
            )
        }

        return ClusterEvaluationResult(
            clusters = evaluatedClusters,
            assignments = assignments,
        )
    }

    private fun processCluster(
        clusterIndex: Int,
        members: List<PositionClusterVector>,
        centroidThreshold: Double,
        evaluatedClusters: MutableList<EvaluatedCluster>,
        assignments: MutableList<EvaluatedPositionAssignment>,
    ) {
        val centroid = computeCentroid(members)
        val assignedMembers = mutableListOf<UUID>()

        for (u in members) {
            val sim = calculateCentroidSimilarity(u, centroid)
            if (sim >= centroidThreshold) {
                assignedMembers.add(u.positionId)
                assignments.add(
                    EvaluatedPositionAssignment(
                        positionId = u.positionId,
                        clusterIndex = clusterIndex,
                        status = ClusterAssignmentStatus.ASSIGNED,
                        centroidSimilarity = sim,
                    ),
                )
            } else {
                assignments.add(
                    EvaluatedPositionAssignment(
                        positionId = u.positionId,
                        clusterIndex = null,
                        status = ClusterAssignmentStatus.OUTLIER_EVICTED,
                        centroidSimilarity = sim,
                    ),
                )
            }
        }

        if (assignedMembers.isNotEmpty()) {
            evaluatedClusters.add(
                EvaluatedCluster(
                    clusterIndex = clusterIndex,
                    size = assignedMembers.size,
                    centroidVector = centroid.terms,
                    memberPositionIds = assignedMembers,
                ),
            )
        }
    }

    private fun computeCentroid(members: List<PositionClusterVector>): CentroidInfo {
        val kSize = members.size.toDouble()
        val sumTerms = mutableMapOf<String, Double>()

        for (m in members) {
            m.weightedTerms.forEach { (t, w) ->
                sumTerms.merge(t, w, Double::plus)
            }
        }

        val meanTerms = mutableMapOf<String, Double>()
        var sumSquared = 0.0

        sumTerms.forEach { (t, total) ->
            val mean = total / kSize
            if (mean > 0.0) {
                meanTerms[t] = mean
                sumSquared += mean * mean
            }
        }

        return CentroidInfo(
            terms = meanTerms,
            norm = sqrt(sumSquared),
        )
    }

    private fun calculateCentroidSimilarity(
        u: PositionClusterVector,
        centroid: CentroidInfo,
    ): Double {
        if (u.totalNorm == 0.0 || centroid.norm == 0.0) {
            return 0.0
        }

        var dot = 0.0
        u.weightedTerms.forEach { (t, w) ->
            val cw = centroid.terms[t]
            if (cw != null) {
                dot += w * cw
            }
        }

        val denom = u.totalNorm * centroid.norm
        val sim = dot / denom
        return sim.coerceIn(0.0, 1.0)
    }
}
