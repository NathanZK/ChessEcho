package com.chessprobe.similarity.clustering

import nl.cwts.networkanalysis.LeidenAlgorithm
import nl.cwts.networkanalysis.Network
import nl.cwts.util.LargeDoubleArray
import nl.cwts.util.LargeIntArray
import java.util.Random
import java.util.UUID

data class CommunityDetectionResult(
    val assignments: Map<UUID, Int?>,
    val quality: Double,
)

class PositionLeidenCommunityDetector {
    companion object {
        const val DEFAULT_ITERATIONS = 10
        const val DEFAULT_RANDOMNESS = 0.01
        const val DEFAULT_SEED = 42L
    }

    fun detectCommunities(
        graph: PositionGraph,
        rbResolution: Double,
        randomSeed: Long = DEFAULT_SEED,
    ): CommunityDetectionResult {
        require(rbResolution.isFinite() && rbResolution > 0.0) { "rbResolution must be positive and finite" }

        val positions = graph.positions
        if (positions.isEmpty()) {
            return CommunityDetectionResult(emptyMap(), 0.0)
        }

        val m = graph.edges.sumOf { it.weight }
        val twoM = 2.0 * m

        // Zero-edge guard: If total graph weight 2m == 0.0, community detection is bypassed;
        // all positions are marked isolates (cluster_id = null).
        if (twoM <= 0.0 || graph.edges.isEmpty()) {
            val allIsolates = positions.associate { it.positionId to null }
            return CommunityDetectionResult(allIsolates, 0.0)
        }

        val network = buildCwtsNetwork(positions, graph.edges)
        val cpmResolution = rbResolution / twoM

        val algorithm =
            LeidenAlgorithm(
                cpmResolution,
                DEFAULT_ITERATIONS,
                DEFAULT_RANDOMNESS,
                Random(randomSeed),
            )

        val clustering = algorithm.findClustering(network)
        val quality = algorithm.calcQuality(network, clustering)

        val assignments = mutableMapOf<UUID, Int?>()
        val clusters = clustering.clusters

        for (i in positions.indices) {
            val posId = positions[i].positionId
            if (network.getNNeighbors(i) == 0) {
                assignments[posId] = null
            } else {
                assignments[posId] = clusters[i]
            }
        }

        return CommunityDetectionResult(
            assignments = assignments,
            quality = quality,
        )
    }

    private fun buildCwtsNetwork(
        positions: List<PositionClusterVector>,
        edges: List<PositionGraphEdge>,
    ): Network {
        val idToNodeIndex = positions.mapIndexed { index, pos -> pos.positionId to index }.toMap()
        val nEdges = edges.size
        val sourceNodes = IntArray(nEdges)
        val targetNodes = IntArray(nEdges)
        val weights = DoubleArray(nEdges)

        edges.forEachIndexed { i, edge ->
            val uIdx = requireNotNull(idToNodeIndex[edge.sourceId]) { "Unknown source ${edge.sourceId}" }
            val vIdx = requireNotNull(idToNodeIndex[edge.targetId]) { "Unknown target ${edge.targetId}" }
            sourceNodes[i] = uIdx
            targetNodes[i] = vIdx
            weights[i] = edge.weight
        }

        val edgeArrays =
            arrayOf(
                LargeIntArray(sourceNodes),
                LargeIntArray(targetNodes),
            )
        val edgeWeightsArray = LargeDoubleArray(weights)

        return Network(
            positions.size,
            true, // setNodeWeightsToTotalEdgeWeights
            edgeArrays,
            edgeWeightsArray,
            false, // sortedEdges
            true, // checkIntegrity
        )
    }
}
