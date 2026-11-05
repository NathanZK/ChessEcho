package com.chessecho.research

import com.chessecho.similarity.clustering.PositionClusterVector
import com.chessecho.similarity.clustering.PositionGraph
import com.chessecho.similarity.clustering.PositionGraphEdge
import com.chessecho.similarity.clustering.PositionLeidenCommunityDetector
import java.util.Random
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

data class NullModelRunResult(
    val runIndex: Int,
    val modularityQ: Double,
    val masd: Double,
    val rsd: Double,
    val totalSwapsAttempted: Int,
    val successfulSwaps: Int,
)

data class NullEnsembleSummary(
    val r: Int,
    val empiricalQ: Double,
    val meanNullQ: Double,
    val stdNullQ: Double,
    val deltaQ: Double,
    val zScoreQ: Double,
    val meanMasd: Double,
    val meanRsd: Double,
    val runs: List<NullModelRunResult>,
)

class MaslovSneppenRewirer(
    private val detector: PositionLeidenCommunityDetector = PositionLeidenCommunityDetector(),
) {

    /**
     * Executes R color-constrained, weight-decile stratified Maslov-Sneppen edge-rewired null models.
     */
    fun evaluateEnsemble(
        graph: PositionGraph,
        empiricalQ: Double,
        rbResolution: Double = 1.25,
        rRuns: Int = 20,
        baseSeed: Long = 1000L,
    ): NullEnsembleSummary {
        val totalM = graph.edges.sumOf { it.weight }
        val twoM = 2.0 * totalM

        val positions = graph.positions
        val vectorById = positions.associateBy { it.positionId }

        // Bipartition: group by turn (first field of canonicalFen)
        val edgesW = mutableListOf<PositionGraphEdge>()
        val edgesB = mutableListOf<PositionGraphEdge>()

        for (e in graph.edges) {
            val src = vectorById[e.sourceId]
            val turn = src?.canonicalFen?.split(" ")?.getOrNull(1)?.lowercase() ?: "w"
            if (turn == "w") edgesW.add(e) else edgesB.add(e)
        }

        // Original node strengths
        val origStrengths = computeNodeStrengths(positions, graph.edges)

        val results = mutableListOf<NullModelRunResult>()

        for (r in 0 until rRuns) {
            val seed = baseSeed + r * 37L
            val rng = Random(seed)

            val (rewiredW, wSwaps, wAttempts) = rewireSubgraphStratified(edgesW, rng)
            val (rewiredB, bSwaps, bAttempts) = rewireSubgraphStratified(edgesB, rng)

            val combinedEdges = rewiredW + rewiredB
            val nullGraph = PositionGraph(positions, combinedEdges)

            val nullStrengths = computeNodeStrengths(positions, combinedEdges)

            // Compute MASD and RSD
            var totalAbsDev = 0.0
            for (p in positions) {
                val sOrig = origStrengths[p.positionId] ?: 0.0
                val sNull = nullStrengths[p.positionId] ?: 0.0
                totalAbsDev += abs(sNull - sOrig)
            }
            val masd = totalAbsDev / positions.size
            val rsd = if (twoM > 0.0) totalAbsDev / twoM else 0.0

            // Leiden detection on null model
            val leidenRes = detector.detectCommunities(nullGraph, rbResolution, seed)

            results.add(
                NullModelRunResult(
                    runIndex = r + 1,
                    modularityQ = leidenRes.quality,
                    masd = masd,
                    rsd = rsd,
                    totalSwapsAttempted = wAttempts + bAttempts,
                    successfulSwaps = wSwaps + bSwaps,
                ),
            )
        }

        val qValues = results.map { it.modularityQ }
        val meanQ = qValues.average()
        val stdQ = if (rRuns > 1) {
            val variance = qValues.sumOf { (it - meanQ) * (it - meanQ) } / (rRuns - 1)
            sqrt(variance)
        } else 0.0

        val deltaQ = empiricalQ - meanQ
        val zScore = if (stdQ > 1e-12) deltaQ / stdQ else 0.0

        val meanMasd = results.map { it.masd }.average()
        val meanRsd = results.map { it.rsd }.average()

        return NullEnsembleSummary(
            r = rRuns,
            empiricalQ = empiricalQ,
            meanNullQ = meanQ,
            stdNullQ = stdQ,
            deltaQ = deltaQ,
            zScoreQ = zScore,
            meanMasd = meanMasd,
            meanRsd = meanRsd,
            runs = results,
        )
    }

    private fun rewireSubgraphStratified(
        edges: List<PositionGraphEdge>,
        rng: Random,
    ): Triple<List<PositionGraphEdge>, Int, Int> {
        if (edges.size < 4) return Triple(edges, 0, 0)

        // Global set of existing edges across all deciles
        val globalExisting = mutableSetOf<Pair<UUID, UUID>>()
        for (e in edges) {
            globalExisting.add(orderedPair(e.sourceId, e.targetId))
        }

        val sorted = edges.sortedBy { it.weight }
        val nBins = 10
        val binSize = (sorted.size + nBins - 1) / nBins

        val rewiredEdges = mutableListOf<PositionGraphEdge>()
        var totalSuccessfulSwaps = 0
        var totalAttempts = 0

        for (binIdx in 0 until nBins) {
            val start = binIdx * binSize
            val end = minOf(start + binSize, sorted.size)
            if (start >= end) continue

            val binEdges = sorted.subList(start, end).toMutableList()
            val (swappedBin, successful, attempts) = rewireEdgeList(binEdges, globalExisting, rng)
            rewiredEdges.addAll(swappedBin)
            totalSuccessfulSwaps += successful
            totalAttempts += attempts
        }

        return Triple(rewiredEdges, totalSuccessfulSwaps, totalAttempts)
    }

    private fun rewireEdgeList(
        binEdges: MutableList<PositionGraphEdge>,
        globalExisting: MutableSet<Pair<UUID, UUID>>,
        rng: Random,
    ): Triple<List<PositionGraphEdge>, Int, Int> {
        val m = binEdges.size
        if (m < 2) return Triple(binEdges, 0, 0)

        val targetAttempts = 4 * m
        var successfulSwaps = 0

        for (attempt in 0 until targetAttempts) {
            val i = rng.nextInt(m)
            val j = rng.nextInt(m)
            if (i == j) continue

            val e1 = binEdges[i]
            val e2 = binEdges[j]

            val u = e1.sourceId
            val v = e1.targetId
            val x = e2.sourceId
            val y = e2.targetId

            // Check 4 distinct endpoints
            if (u == x || u == y || v == x || v == y) continue

            // Candidate swap: (u, y) and (x, v)
            val pair1 = orderedPair(u, y)
            val pair2 = orderedPair(x, v)

            if (!globalExisting.contains(pair1) && !globalExisting.contains(pair2)) {
                globalExisting.remove(orderedPair(u, v))
                globalExisting.remove(orderedPair(x, y))
                globalExisting.add(pair1)
                globalExisting.add(pair2)

                val (s1, t1) = if (u.compareTo(y) <= 0) Pair(u, y) else Pair(y, u)
                val (s2, t2) = if (x.compareTo(v) <= 0) Pair(x, v) else Pair(v, x)

                binEdges[i] = PositionGraphEdge(s1, t1, e1.weight)
                binEdges[j] = PositionGraphEdge(s2, t2, e2.weight)
                successfulSwaps++
            }
        }

        return Triple(binEdges, successfulSwaps, targetAttempts)
    }

    private fun orderedPair(a: UUID, b: UUID): Pair<UUID, UUID> {
        return if (a.compareTo(b) <= 0) Pair(a, b) else Pair(b, a)
    }

    private fun computeNodeStrengths(
        positions: List<PositionClusterVector>,
        edges: List<PositionGraphEdge>,
    ): Map<UUID, Double> {
        val strengths = positions.associate { it.positionId to 0.0 }.toMutableMap()
        for (e in edges) {
            strengths[e.sourceId] = (strengths[e.sourceId] ?: 0.0) + e.weight
            strengths[e.targetId] = (strengths[e.targetId] ?: 0.0) + e.weight
        }
        return strengths
    }
}
