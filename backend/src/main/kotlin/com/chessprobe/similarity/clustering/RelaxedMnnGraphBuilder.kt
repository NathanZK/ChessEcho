package com.chessprobe.similarity.clustering

import java.util.UUID

data class PositionGraphEdge(
    val sourceId: UUID,
    val targetId: UUID,
    val weight: Double,
)

data class PositionGraph(
    val positions: List<PositionClusterVector>,
    val edges: List<PositionGraphEdge>,
)

class RelaxedMnnGraphBuilder(
    private val similarityScorer: PositionClusterSimilarityScorer = PositionClusterSimilarityScorer(),
) {
    data class CandidateRank(
        val vector: PositionClusterVector,
        val similarity: Double,
    )

    fun buildGraph(
        vectors: List<PositionClusterVector>,
        candidateMap: Map<UUID, Collection<UUID>>? = null,
        topK: Int = 30,
        floorThreshold: Double = 0.40,
        top1Threshold: Double = 0.65,
    ): PositionGraph {
        if (vectors.isEmpty()) {
            return PositionGraph(emptyList(), emptyList())
        }

        val rankedNeighbors = computeRankedNeighbors(vectors, candidateMap)
        val topKMap =
            rankedNeighbors.mapValues { (_, ranks) ->
                ranks.take(topK).map { it.vector.positionId }.toSet()
            }
        val top1Map = rankedNeighbors.mapValues { (_, ranks) -> ranks.firstOrNull() }

        val edges =
            collectEdges(
                vectors = vectors,
                rankedNeighbors = rankedNeighbors,
                topKMap = topKMap,
                top1Map = top1Map,
                floorThreshold = floorThreshold,
                top1Threshold = top1Threshold,
            )

        return PositionGraph(positions = vectors, edges = edges)
    }

    private fun computeRankedNeighbors(
        vectors: List<PositionClusterVector>,
        candidateMap: Map<UUID, Collection<UUID>>?,
    ): Map<UUID, List<CandidateRank>> {
        val vectorById = vectors.associateBy { it.positionId }
        val result = mutableMapOf<UUID, List<CandidateRank>>()

        for (u in vectors) {
            val candidateIds = candidateMap?.get(u.positionId)
            val candidateVectors =
                if (candidateIds != null) {
                    candidateIds.mapNotNull { vectorById[it] }.filter { it.positionId != u.positionId }
                } else {
                    vectors.filter { it.positionId != u.positionId }
                }

            result[u.positionId] = rankCandidates(u, candidateVectors)
        }
        return result
    }

    private fun rankCandidates(
        u: PositionClusterVector,
        candidates: List<PositionClusterVector>,
    ): List<CandidateRank> =
        candidates
            .map { v ->
                CandidateRank(
                    vector = v,
                    similarity = similarityScorer.cosineSimilarity(u, v),
                )
            }.sortedWith(
                compareByDescending<CandidateRank> { it.similarity }
                    .thenBy { it.vector.canonicalFen }
                    .thenBy { it.vector.positionId },
            )

    private fun collectEdges(
        vectors: List<PositionClusterVector>,
        rankedNeighbors: Map<UUID, List<CandidateRank>>,
        topKMap: Map<UUID, Set<UUID>>,
        top1Map: Map<UUID, CandidateRank?>,
        floorThreshold: Double,
        top1Threshold: Double,
    ): List<PositionGraphEdge> {
        val addedEdges = mutableMapOf<Pair<UUID, UUID>, Double>()

        for (u in vectors) {
            val ranks = rankedNeighbors[u.positionId].orEmpty()
            collectNodeEdges(u, ranks, topKMap, top1Map, floorThreshold, top1Threshold, addedEdges)
        }

        return addedEdges.map { (pair, weight) ->
            PositionGraphEdge(pair.first, pair.second, weight)
        }
    }

    private fun collectNodeEdges(
        u: PositionClusterVector,
        ranks: List<CandidateRank>,
        topKMap: Map<UUID, Set<UUID>>,
        top1Map: Map<UUID, CandidateRank?>,
        floorThreshold: Double,
        top1Threshold: Double,
        addedEdges: MutableMap<Pair<UUID, UUID>, Double>,
    ) {
        for (rank in ranks) {
            val v = rank.vector
            if (u.positionId == v.positionId) continue

            val eligible =
                isEligibleEdge(
                    uId = u.positionId,
                    vId = v.positionId,
                    sim = rank.similarity,
                    topKMap = topKMap,
                    top1Map = top1Map,
                    floorThreshold = floorThreshold,
                    top1Threshold = top1Threshold,
                )

            if (eligible) {
                val key = canonicalPair(u.positionId, v.positionId)
                addedEdges.putIfAbsent(key, rank.similarity)
            }
        }
    }

    private fun isEligibleEdge(
        uId: UUID,
        vId: UUID,
        sim: Double,
        topKMap: Map<UUID, Set<UUID>>,
        top1Map: Map<UUID, CandidateRank?>,
        floorThreshold: Double,
        top1Threshold: Double,
    ): Boolean {
        val isMutualTopK =
            sim >= floorThreshold &&
                topKMap[uId]?.contains(vId) == true &&
                topKMap[vId]?.contains(uId) == true

        if (isMutualTopK) return true

        return sim >= top1Threshold &&
            (
                top1Map[uId]?.vector?.positionId == vId ||
                    top1Map[vId]?.vector?.positionId == uId
            )
    }

    private fun canonicalPair(
        u: UUID,
        v: UUID,
    ): Pair<UUID, UUID> = if (u < v) u to v else v to u
}
