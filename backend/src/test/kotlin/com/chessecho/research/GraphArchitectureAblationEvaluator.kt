package com.chessecho.research

import com.chessecho.domain.clustering.PositionClusterAssignmentStatus
import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.PositionInvertedIndex
import com.chessecho.similarity.clustering.PositionClusterCentroidEvaluator
import com.chessecho.similarity.clustering.PositionClusterSimilarityScorer
import com.chessecho.similarity.clustering.PositionClusterVector
import com.chessecho.similarity.clustering.PositionGraph
import com.chessecho.similarity.clustering.PositionGraphEdge
import com.chessecho.similarity.clustering.PositionLeidenCommunityDetector
import com.chessecho.similarity.clustering.RelaxedMnnGraphBuilder
import java.util.ArrayDeque
import java.util.UUID

class GraphArchitectureAblationEvaluator(
    private val corpusDocs: List<PositionDocument>,
    private val probeQueries: List<PositionDocument>,
    private val referencesByQuery: Map<UUID, QueryReferenceSet>,
) {
    private val invertedIndex = PositionInvertedIndex().apply { index(corpusDocs) }
    private val graphBuilder = RelaxedMnnGraphBuilder()
    private val communityDetector = PositionLeidenCommunityDetector()
    private val centroidEvaluator = PositionClusterCentroidEvaluator()
    private val scorer = PositionClusterSimilarityScorer()

    private val docsById = corpusDocs.associateBy { it.positionId }

    // Precomputed candidate map (top 200) for all corpus positions across alpha
    // Cache: alpha -> (positionId -> top200 candidate UUIDs)
    private val candidateMapsByAlpha = mutableMapOf<Double, Map<UUID, List<UUID>>>()
    private val vectorsByAlpha = mutableMapOf<Double, Map<UUID, PositionClusterVector>>()

    init {
        val totalDocs = corpusDocs.size
        val docFreqs = mutableMapOf<String, Int>()
        corpusDocs.forEach { doc ->
            doc.terms.keys.forEach { term ->
                docFreqs.merge(term, 1, Int::plus)
            }
        }
        val idfMap = mutableMapOf<String, Double>()
        docFreqs.forEach { (term, df) ->
            idfMap[term] = kotlin.math.ln(1.0 + (totalDocs - df + 0.5) / (df + 0.5)).coerceAtLeast(0.0)
        }

        val alphas = listOf(0.00, 0.25, 0.50, 0.60, 0.75, 1.00)
        alphas.forEach { alpha ->
            val vMap = mutableMapOf<UUID, PositionClusterVector>()
            corpusDocs.forEach { doc ->
                vMap[doc.positionId] = scorer.buildVector(
                    positionId = doc.positionId,
                    canonicalFen = doc.canonicalFen,
                    terms = doc.terms,
                    idfs = idfMap,
                    alpha = alpha,
                )
            }
            vectorsByAlpha[alpha] = vMap

            val cMap = mutableMapOf<UUID, List<UUID>>()
            corpusDocs.forEach { doc ->
                val raw = invertedIndex.generateCandidates(
                    queryTerms = doc.terms.keys,
                    activeTurn = doc.sideToMove.lowercase(),
                    maxCandidates = 200,
                    alpha = alpha,
                )
                cMap[doc.positionId] = raw.map { it.positionId }
            }
            candidateMapsByAlpha[alpha] = cMap
        }
    }

    // Direct top-200 inverted index candidate IDs for query q (for lineage tracking)
    fun getDirectIndexTop200(query: PositionDocument, alpha: Double): Set<UUID> {
        val raw = invertedIndex.generateCandidates(
            queryTerms = query.terms.keys,
            activeTurn = query.sideToMove.lowercase(),
            maxCandidates = 210,
            alpha = alpha,
        )
        return raw.filter { it.positionId != query.positionId && it.sideToMove.lowercase() == query.sideToMove.lowercase() }
            .take(200)
            .map { it.positionId }
            .toSet()
    }

    // --- System 3: Production Clustered Baseline (Unified Graph, splitBySideToMove = false) ---
    fun evaluateSystem3(alphas: List<Double>): SystemAblationRecord {
        val alphaResults = mutableMapOf<String, AlphaSystemMetrics>()

        alphas.forEach { alpha ->
            val alphaKey = "alpha_${alpha}"
            val vectors = vectorsByAlpha.getValue(alpha)
            val candidateMap = candidateMapsByAlpha.getValue(alpha)

            // 1. Build unified graph across all positions
            val graph = graphBuilder.buildGraph(
                vectors = vectors.values.toList(),
                candidateMap = candidateMap,
                topK = 30,
                floorThreshold = 0.40,
                top1Threshold = 0.65,
            )

            // 2. Leiden community detection
            val leiden = communityDetector.detectCommunities(graph, rbResolution = 1.25, randomSeed = 42L)

            // 3. Centroid evaluation
            val eval = centroidEvaluator.evaluateClusters(vectors.values.toList(), leiden.assignments, centroidThreshold = 0.38)
            val clustersByIndex = eval.clusters.associateBy { it.clusterIndex }
            val assignmentsById = eval.assignments.associateBy { it.positionId }

            // Evaluate probe queries
            val candidateSetsByQuery = mutableMapOf<UUID, List<UUID>>()
            var oppositeExcludedCount = 0

            probeQueries.forEach { q ->
                val qTurn = q.sideToMove.lowercase()
                val assign = assignmentsById[q.positionId]
                if (assign != null && assign.status == PositionClusterAssignmentStatus.ASSIGNED && assign.clusterIndex != null) {
                    val cluster = clustersByIndex[assign.clusterIndex]
                    if (cluster != null) {
                        val rawMembers = cluster.memberPositionIds.filter { it != q.positionId }
                        val sameSide = rawMembers.filter { docsById[it]?.sideToMove?.lowercase() == qTurn }
                        val oppSideCount = rawMembers.size - sameSide.size
                        oppositeExcludedCount += oppSideCount
                        candidateSetsByQuery[q.positionId] = sameSide
                    } else {
                        candidateSetsByQuery[q.positionId] = emptyList()
                    }
                } else {
                    candidateSetsByQuery[q.positionId] = emptyList()
                }
            }

            alphaResults[alphaKey] = computeAlphaMetrics(
                alpha = alpha,
                candidateSetsByQuery = candidateSetsByQuery,
                oppositeExcludedCount = oppositeExcludedCount,
            )
        }

        return SystemAblationRecord(
            systemId = "SYSTEM_3_PRODUCTION_CLUSTERED_BASELINE",
            systemType = "PRODUCTION_CLUSTERED_BASELINE",
            targetBudget = null,
            alphaResults = alphaResults,
        )
    }

    // --- Side-Constrained Graph Suite (Family 4, splitBySideToMove = true) ---
    data class SideConstrainedGraphBundle(
        val wGraph: PositionGraph,
        val bGraph: PositionGraph,
        val wAdj: Map<UUID, List<Pair<UUID, Double>>>,
        val bAdj: Map<UUID, List<Pair<UUID, Double>>>,
        val wLeidenAssignments: Map<UUID, Int?>,
        val bLeidenAssignments: Map<UUID, Int?>,
        val wEvalClusters: Map<Int, List<UUID>>,
        val bEvalClusters: Map<Int, List<UUID>>,
        val wAssignments: Map<UUID, PositionClusterAssignmentStatus>,
        val bAssignments: Map<UUID, PositionClusterAssignmentStatus>,
        val wClusterIndices: Map<UUID, Int?>,
        val bClusterIndices: Map<UUID, Int?>,
    )

    fun buildSideConstrainedBundle(alpha: Double): SideConstrainedGraphBundle {
        val vectors = vectorsByAlpha.getValue(alpha)
        val candidateMap = candidateMapsByAlpha.getValue(alpha)

        val docsByTurn = corpusDocs.groupBy { it.sideToMove.lowercase() }
        val wDocs = docsByTurn["w"].orEmpty()
        val bDocs = docsByTurn["b"].orEmpty()

        val wVectors = wDocs.map { vectors.getValue(it.positionId) }
        val bVectors = bDocs.map { vectors.getValue(it.positionId) }

        // Restrict candidateMap to same-side positions prior to edge construction
        val wCandMap = wDocs.associate { doc ->
            doc.positionId to (candidateMap[doc.positionId]?.filter { cId -> docsById[cId]?.sideToMove?.lowercase() == "w" } ?: emptyList())
        }
        val bCandMap = bDocs.associate { doc ->
            doc.positionId to (candidateMap[doc.positionId]?.filter { cId -> docsById[cId]?.sideToMove?.lowercase() == "b" } ?: emptyList())
        }

        val wGraph = graphBuilder.buildGraph(wVectors, wCandMap, topK = 30, floorThreshold = 0.40, top1Threshold = 0.65)
        val bGraph = graphBuilder.buildGraph(bVectors, bCandMap, topK = 30, floorThreshold = 0.40, top1Threshold = 0.65)

        fun buildAdj(graph: PositionGraph): Map<UUID, List<Pair<UUID, Double>>> {
            val adj = mutableMapOf<UUID, MutableList<Pair<UUID, Double>>>()
            graph.edges.forEach { edge ->
                adj.computeIfAbsent(edge.sourceId) { mutableListOf() }.add(edge.targetId to edge.weight)
                adj.computeIfAbsent(edge.targetId) { mutableListOf() }.add(edge.sourceId to edge.weight)
            }
            return adj
        }

        val wAdj = buildAdj(wGraph)
        val bAdj = buildAdj(bGraph)

        val wLeiden = communityDetector.detectCommunities(wGraph, rbResolution = 1.25, randomSeed = 42L)
        val bLeiden = communityDetector.detectCommunities(bGraph, rbResolution = 1.25, randomSeed = 43L)

        val wEval = centroidEvaluator.evaluateClusters(wVectors, wLeiden.assignments, centroidThreshold = 0.38)
        val bEval = centroidEvaluator.evaluateClusters(bVectors, bLeiden.assignments, centroidThreshold = 0.38)

        return SideConstrainedGraphBundle(
            wGraph = wGraph,
            bGraph = bGraph,
            wAdj = wAdj,
            bAdj = bAdj,
            wLeidenAssignments = wLeiden.assignments,
            bLeidenAssignments = bLeiden.assignments,
            wEvalClusters = wEval.clusters.associate { it.clusterIndex to it.memberPositionIds },
            bEvalClusters = bEval.clusters.associate { it.clusterIndex to it.memberPositionIds },
            wAssignments = wEval.assignments.associate { it.positionId to it.status },
            bAssignments = bEval.assignments.associate { it.positionId to it.status },
            wClusterIndices = wEval.assignments.associate { it.positionId to it.clusterIndex },
            bClusterIndices = bEval.assignments.associate { it.positionId to it.clusterIndex },
        )
    }

    // System 4A: 1-Hop MNN Neighbors
    fun retrieveSystem4ACandidates(query: PositionDocument, bundle: SideConstrainedGraphBundle): List<UUID> {
        val adj = if (query.sideToMove.lowercase() == "w") bundle.wAdj else bundle.bAdj
        return adj[query.positionId]?.map { it.first } ?: emptyList()
    }

    // System 4B: Budget-Capped 2-Hop Graph Expansion
    fun retrieveSystem4BCandidates(query: PositionDocument, budget: Int, bundle: SideConstrainedGraphBundle): List<UUID> {
        val qId = query.positionId
        val adj = if (query.sideToMove.lowercase() == "w") bundle.wAdj else bundle.bAdj

        val maxPathWeight = mutableMapOf<UUID, Double>()
        val oneHop = adj[qId].orEmpty()

        oneHop.forEach { (v, w) ->
            if (v != qId) {
                maxPathWeight[v] = maxOf(maxPathWeight[v] ?: 0.0, w)
            }
            // 2-hop
            val twoHop = adj[v].orEmpty()
            twoHop.forEach { (u, w2) ->
                if (u != qId) {
                    val pathW = w * w2
                    maxPathWeight[u] = maxOf(maxPathWeight[u] ?: 0.0, pathW)
                }
            }
        }

        val sorted = maxPathWeight.entries.map { (id, weight) ->
            val fen = docsById[id]?.canonicalFen.orEmpty()
            Triple(id, fen, weight)
        }.sortedWith(
            compareByDescending<Triple<UUID, String, Double>> { it.third }
                .thenBy { it.second }
                .thenBy { it.first }
        )

        return sorted.take(budget).map { it.first }
    }

    // System 4C: Connected Component Upper Bound within G_side,alpha
    fun retrieveSystem4CCandidates(query: PositionDocument, bundle: SideConstrainedGraphBundle): List<UUID> {
        val qId = query.positionId
        val adj = if (query.sideToMove.lowercase() == "w") bundle.wAdj else bundle.bAdj

        val visited = mutableSetOf<UUID>()
        val queue = ArrayDeque<UUID>()
        visited.add(qId)
        queue.add(qId)

        while (queue.isNotEmpty()) {
            val curr = queue.poll()
            adj[curr].orEmpty().forEach { (neighbor, _) ->
                if (visited.add(neighbor)) {
                    queue.add(neighbor)
                }
            }
        }
        visited.remove(qId)
        return visited.toList()
    }

    // System 4D (Unpruned Leiden Community)
    fun retrieveSystem4DLeidenCommunity(query: PositionDocument, bundle: SideConstrainedGraphBundle): List<UUID> {
        val qId = query.positionId
        val isWhite = query.sideToMove.lowercase() == "w"
        val assignments = if (isWhite) bundle.wLeidenAssignments else bundle.bLeidenAssignments
        val cId = assignments[qId] ?: return emptyList()

        return assignments.filter { it.value == cId && it.key != qId }.keys.toList()
    }

    // System 4D (Centroid-Pruned Cluster)
    fun retrieveSystem4DCentroidCluster(query: PositionDocument, bundle: SideConstrainedGraphBundle): List<UUID> {
        val qId = query.positionId
        val isWhite = query.sideToMove.lowercase() == "w"
        val status = if (isWhite) bundle.wAssignments[qId] else bundle.bAssignments[qId]
        val cIndex = if (isWhite) bundle.wClusterIndices[qId] else bundle.bClusterIndices[qId]

        if (status != PositionClusterAssignmentStatus.ASSIGNED || cIndex == null) {
            return emptyList()
        }

        val clusterMembers = if (isWhite) bundle.wEvalClusters[cIndex] else bundle.bEvalClusters[cIndex]
        return clusterMembers?.filter { it != qId } ?: emptyList()
    }

    fun computeAlphaMetrics(
        alpha: Double,
        candidateSetsByQuery: Map<UUID, List<UUID>>,
        oppositeExcludedCount: Int,
    ): AlphaSystemMetrics {
        var totalCands = 0

        val bm25Retained10List = mutableListOf<Int>()
        val bm25Retained20List = mutableListOf<Int>()
        val tfidfRetained10List = mutableListOf<Int>()
        val tfidfRetained20List = mutableListOf<Int>()

        var bm25SumRecall10 = 0.0
        var bm25SumRecall20 = 0.0
        var bm25SumPosRecall10 = 0.0
        var bm25SumPosRecall20 = 0.0
        var bm25SumPurity10 = 0.0
        var bm25SumPurity20 = 0.0

        var tfidfSumRecall10 = 0.0
        var tfidfSumRecall20 = 0.0
        var tfidfSumPosRecall10 = 0.0
        var tfidfSumPosRecall20 = 0.0
        var tfidfSumPurity10 = 0.0
        var tfidfSumPurity20 = 0.0

        // Lineage tracking
        var bm25DirectRecalled10 = 0
        var bm25GraphRecalled10 = 0
        var bm25DirectRecalled20 = 0
        var bm25GraphRecalled20 = 0

        var tfidfDirectRecalled10 = 0
        var tfidfGraphRecalled10 = 0
        var tfidfDirectRecalled20 = 0
        var tfidfGraphRecalled20 = 0

        probeQueries.forEach { q ->
            val ref = referencesByQuery.getValue(q.positionId)
            val cands = candidateSetsByQuery.getValue(q.positionId)
            val candSet = cands.toSet()
            val candCount = cands.size
            totalCands += candCount

            val directTop200 = getDirectIndexTop200(q, alpha)

            // BM25
            val bm25Hits10 = candSet.intersect(ref.bm25Top10)
            val bm25Hits20 = candSet.intersect(ref.bm25Top20)
            val bm25Ret10 = bm25Hits10.size
            val bm25Ret20 = bm25Hits20.size
            bm25Retained10List.add(bm25Ret10)
            bm25Retained20List.add(bm25Ret20)

            bm25Hits10.forEach { id ->
                if (id in directTop200) bm25DirectRecalled10++ else bm25GraphRecalled10++
            }
            bm25Hits20.forEach { id ->
                if (id in directTop200) bm25DirectRecalled20++ else bm25GraphRecalled20++
            }

            bm25SumRecall10 += bm25Ret10 / 10.0
            bm25SumRecall20 += bm25Ret20 / 20.0
            bm25SumPosRecall10 += bm25Ret10 / 10.0
            bm25SumPosRecall20 += bm25Ret20 / 20.0
            bm25SumPurity10 += if (candCount > 0) bm25Ret10.toDouble() / candCount else 0.0
            bm25SumPurity20 += if (candCount > 0) bm25Ret20.toDouble() / candCount else 0.0

            // TF-IDF
            val tfidfHits10 = candSet.intersect(ref.tfidfTop10)
            val tfidfHits20 = candSet.intersect(ref.tfidfTop20)
            val tfidfRet10 = tfidfHits10.size
            val tfidfRet20 = tfidfHits20.size
            tfidfRetained10List.add(tfidfRet10)
            tfidfRetained20List.add(tfidfRet20)

            tfidfHits10.forEach { id ->
                if (id in directTop200) tfidfDirectRecalled10++ else tfidfGraphRecalled10++
            }
            tfidfHits20.forEach { id ->
                if (id in directTop200) tfidfDirectRecalled20++ else tfidfGraphRecalled20++
            }

            tfidfSumRecall10 += tfidfRet10 / 10.0
            tfidfSumRecall20 += tfidfRet20 / 20.0
            tfidfSumPosRecall10 += tfidfRet10 / 10.0
            tfidfSumPosRecall20 += tfidfRet20 / 20.0
            tfidfSumPurity10 += if (candCount > 0) tfidfRet10.toDouble() / candCount else 0.0
            tfidfSumPurity20 += if (candCount > 0) tfidfRet20.toDouble() / candCount else 0.0
        }

        val nQ = probeQueries.size
        val meanCandCount = totalCands.toDouble() / nQ
        val meanUniverseSize = probeQueries.map { referencesByQuery.getValue(it.positionId).universeSize }.average()
        val crr = 1.0 - (meanCandCount / meanUniverseSize)

        fun ratio(num: Int, den: Int): Double = if (den > 0) num.toDouble() / den else 0.0

        val lineage = IndependentLineageMetrics(
            bm25DirectRecalledAt10 = bm25DirectRecalled10,
            bm25GraphRecalledAt10 = bm25GraphRecalled10,
            bm25GraphRecoveryRatioAt10 = ratio(bm25GraphRecalled10, bm25DirectRecalled10 + bm25GraphRecalled10),
            bm25DirectRecalledAt20 = bm25DirectRecalled20,
            bm25GraphRecalledAt20 = bm25GraphRecalled20,
            bm25GraphRecoveryRatioAt20 = ratio(bm25GraphRecalled20, bm25DirectRecalled20 + bm25GraphRecalled20),
            tfidfDirectRecalledAt10 = tfidfDirectRecalled10,
            tfidfGraphRecalledAt10 = tfidfGraphRecalled10,
            tfidfGraphRecoveryRatioAt10 = ratio(tfidfGraphRecalled10, tfidfDirectRecalled10 + tfidfGraphRecalled10),
            tfidfDirectRecalledAt20 = tfidfDirectRecalled20,
            tfidfGraphRecalledAt20 = tfidfGraphRecalled20,
            tfidfGraphRecoveryRatioAt20 = ratio(tfidfGraphRecalled20, tfidfDirectRecalled20 + tfidfGraphRecalled20),
        )

        val bm25Metrics = ScorerAblationMetrics(
            dynamicRecallAt10 = bm25SumRecall10 / nQ,
            dynamicRecallAt20 = bm25SumRecall20 / nQ,
            positiveOnlyRecallAt10 = bm25SumPosRecall10 / nQ,
            positiveOnlyRecallAt20 = bm25SumPosRecall20 / nQ,
            purityAt10 = bm25SumPurity10 / nQ,
            purityAt20 = bm25SumPurity20 / nQ,
            retainedAt10Distribution = MetricDistributionCalculator.calculate(bm25Retained10List),
            retainedAt20Distribution = MetricDistributionCalculator.calculate(bm25Retained20List),
        )

        val tfidfMetrics = ScorerAblationMetrics(
            dynamicRecallAt10 = tfidfSumRecall10 / nQ,
            dynamicRecallAt20 = tfidfSumRecall20 / nQ,
            positiveOnlyRecallAt10 = tfidfSumPosRecall10 / nQ,
            positiveOnlyRecallAt20 = tfidfSumPosRecall20 / nQ,
            purityAt10 = tfidfSumPurity10 / nQ,
            purityAt20 = tfidfSumPurity20 / nQ,
            retainedAt10Distribution = MetricDistributionCalculator.calculate(tfidfRetained10List),
            retainedAt20Distribution = MetricDistributionCalculator.calculate(tfidfRetained20List),
        )

        return AlphaSystemMetrics(
            alpha = alpha,
            meanCandidateCount = meanCandCount,
            candidateReductionRate = crr,
            oppositeSideCandidatesExcludedCount = oppositeExcludedCount,
            bm25Metrics = bm25Metrics,
            tfidfMetrics = tfidfMetrics,
            lineageBreakdown = lineage,
        )
    }
}
