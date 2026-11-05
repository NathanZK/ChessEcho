package com.chessecho.research

import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.PositionInvertedIndex
import com.chessecho.similarity.RelationalPawnTokenExtractor
import com.chessecho.similarity.clustering.ClusterEvaluationResult
import com.chessecho.similarity.clustering.CommunityDetectionResult
import com.chessecho.similarity.clustering.PositionClusterCentroidEvaluator
import com.chessecho.similarity.clustering.PositionClusterSimilarityScorer
import com.chessecho.similarity.clustering.PositionClusterVector
import com.chessecho.similarity.clustering.PositionGraph
import com.chessecho.similarity.clustering.PositionGraphEdge
import com.chessecho.similarity.clustering.PositionLeidenCommunityDetector
import com.chessecho.similarity.clustering.RelaxedMnnGraphBuilder
import java.util.UUID
import kotlin.math.ln
import kotlin.system.measureTimeMillis

data class ExperimentConfig(
    val alpha: Double = 0.60,
    val candidateLimit: Int = 200,
    val topK: Int = 30,
    val floorThreshold: Double = 0.40,
    val top1Threshold: Double = 0.65,
    val rbResolution: Double = 1.25,
    val centroidThreshold: Double = 0.38,
    val randomSeed: Long = 42L,
    val splitBySideToMove: Boolean = false,
)

data class ExperimentRunResult(
    val config: ExperimentConfig,
    val metrics: ClusteringSummaryMetrics,
    val graph: PositionGraph,
    val leidenResult: CommunityDetectionResult,
    val evaluationResult: ClusterEvaluationResult,
    val vectors: Map<UUID, PositionClusterVector>,
    val idfMap: Map<String, Double>,
    val candidateMap: Map<UUID, Collection<UUID>>,
)

class ClusteringExperimentRunner(
    private val extractor: RelationalPawnTokenExtractor = RelationalPawnTokenExtractor(),
    private val scorer: PositionClusterSimilarityScorer = PositionClusterSimilarityScorer(),
    private val graphBuilder: RelaxedMnnGraphBuilder = RelaxedMnnGraphBuilder(scorer),
    private val communityDetector: PositionLeidenCommunityDetector = PositionLeidenCommunityDetector(),
    private val centroidEvaluator: PositionClusterCentroidEvaluator = PositionClusterCentroidEvaluator(),
) {
    fun runExperiment(
        positions: List<PositionDocument>,
        config: ExperimentConfig = ExperimentConfig(),
        precomputedCandidates: Map<UUID, Collection<UUID>>? = null,
    ): ExperimentRunResult {
        val phaseTimings = mutableMapOf<String, Long>()
        val startTotal = System.currentTimeMillis()

        // 1. Compute IDF
        val idfMap: Map<String, Double>
        val idfDuration = measureTimeMillis {
            idfMap = computeIdfMap(positions)
        }
        phaseTimings["idfComputation"] = idfDuration

        // 2. Index in Inverted Index (only if candidates not precomputed)
        val invertedIndex = PositionInvertedIndex()
        val indexDuration = measureTimeMillis {
            if (precomputedCandidates == null) {
                invertedIndex.index(positions)
            }
        }
        phaseTimings["invertedIndexing"] = indexDuration

        // 3. Build vectors
        val vectors: Map<UUID, PositionClusterVector>
        val vectorDuration = measureTimeMillis {
            vectors = positions.associate { doc ->
                doc.positionId to scorer.buildVector(
                    positionId = doc.positionId,
                    canonicalFen = doc.canonicalFen,
                    terms = doc.terms,
                    idfs = idfMap,
                    alpha = config.alpha,
                )
            }
        }
        phaseTimings["vectorBuilding"] = vectorDuration

        // 4. Candidate Retrieval
        val candidateMap: Map<UUID, Collection<UUID>>
        val candidateDuration = measureTimeMillis {
            candidateMap = precomputedCandidates ?: positions.associate { doc ->
                val cands = invertedIndex.generateCandidates(
                    queryTerms = doc.terms.keys,
                    activeTurn = doc.sideToMove,
                    maxCandidates = config.candidateLimit,
                    alpha = config.alpha,
                ).filter { it.positionId != doc.positionId }
                doc.positionId to cands.map { it.positionId }
            }
        }
        phaseTimings["candidateRetrieval"] = candidateDuration

        // 5. Graph Construction (Unified vs Separated Subgraphs)
        val graph: PositionGraph
        val leidenResult: CommunityDetectionResult
        val graphDuration: Long
        val leidenDuration: Long

        if (!config.splitBySideToMove) {
            val vList = vectors.values.toList()
            graphDuration = measureTimeMillis {
                graph = graphBuilder.buildGraph(
                    vectors = vList,
                    candidateMap = candidateMap,
                    topK = config.topK,
                    floorThreshold = config.floorThreshold,
                    top1Threshold = config.top1Threshold,
                )
            }
            leidenDuration = measureTimeMillis {
                leidenResult = communityDetector.detectCommunities(
                    graph = graph,
                    rbResolution = config.rbResolution,
                    randomSeed = config.randomSeed,
                )
            }
        } else {
            // Separate into White and Black subgraphs
            val docsByTurn = positions.groupBy { it.sideToMove.lowercase() }
            val wDocs = docsByTurn["w"].orEmpty()
            val bDocs = docsByTurn["b"].orEmpty()

            val wVectors = wDocs.mapNotNull { vectors[it.positionId] }
            val bVectors = bDocs.mapNotNull { vectors[it.positionId] }

            val wCandMap = candidateMap.filterKeys { id -> wDocs.any { it.positionId == id } }
            val bCandMap = candidateMap.filterKeys { id -> bDocs.any { it.positionId == id } }

            val wGraph: PositionGraph
            val bGraph: PositionGraph
            graphDuration = measureTimeMillis {
                wGraph = graphBuilder.buildGraph(wVectors, wCandMap, config.topK, config.floorThreshold, config.top1Threshold)
                bGraph = graphBuilder.buildGraph(bVectors, bCandMap, config.topK, config.floorThreshold, config.top1Threshold)
            }

            val wLeiden: CommunityDetectionResult
            val bLeiden: CommunityDetectionResult
            leidenDuration = measureTimeMillis {
                wLeiden = communityDetector.detectCommunities(wGraph, config.rbResolution, config.randomSeed)
                bLeiden = communityDetector.detectCommunities(bGraph, config.rbResolution, config.randomSeed + 1)
            }

            // Combine subgraphs and offset Black cluster IDs
            val wMaxClusterId = (wLeiden.assignments.values.filterNotNull().maxOrNull() ?: -1) + 1
            val combinedAssignments = mutableMapOf<UUID, Int?>()
            combinedAssignments.putAll(wLeiden.assignments)
            bLeiden.assignments.forEach { (id, cId) ->
                combinedAssignments[id] = if (cId != null) cId + wMaxClusterId else null
            }

            val allEdges = mutableListOf<PositionGraphEdge>()
            allEdges.addAll(wGraph.edges)
            allEdges.addAll(bGraph.edges)
            graph = PositionGraph(vectors.values.toList(), allEdges)
            leidenResult = CommunityDetectionResult(
                assignments = combinedAssignments,
                quality = wLeiden.quality + bLeiden.quality,
            )
        }
        phaseTimings["graphConstruction"] = graphDuration
        phaseTimings["leidenDetection"] = leidenDuration

        // 6. Centroid Evaluation & Outlier Eviction
        val evaluationResult: ClusterEvaluationResult
        val centroidDuration = measureTimeMillis {
            evaluationResult = centroidEvaluator.evaluateClusters(
                vectors = vectors.values.toList(),
                communityAssignments = leidenResult.assignments,
                centroidThreshold = config.centroidThreshold,
            )
        }
        phaseTimings["centroidEvaluation"] = centroidDuration

        val totalDuration = System.currentTimeMillis() - startTotal
        val runtime = Runtime.getRuntime()
        val memoryUsedMb = (runtime.totalMemory() - runtime.freeMemory()).toDouble() / (1024.0 * 1024.0)

        val metrics = ResearchMetrics.summarize(
            totalPositions = positions.size,
            graph = graph,
            leidenResult = leidenResult,
            evaluationResult = evaluationResult,
            timings = phaseTimings,
            totalDurationMs = totalDuration,
            heapMemoryUsedMb = memoryUsedMb,
        )

        return ExperimentRunResult(
            config = config,
            metrics = metrics,
            graph = graph,
            leidenResult = leidenResult,
            evaluationResult = evaluationResult,
            vectors = vectors,
            idfMap = idfMap,
            candidateMap = candidateMap,
        )
    }

    private fun computeIdfMap(positions: List<PositionDocument>): Map<String, Double> {
        val dfMap = mutableMapOf<String, Int>()
        positions.forEach { p ->
            p.terms.keys.forEach { t -> dfMap.merge(t, 1, Int::plus) }
        }
        val totalDocs = positions.size
        return dfMap.mapValues { (_, df) ->
            ln(1.0 + ((totalDocs - df + 0.5) / (df + 0.5)))
        }
    }
}
