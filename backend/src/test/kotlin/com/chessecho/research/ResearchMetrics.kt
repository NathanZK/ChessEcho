package com.chessecho.research

import com.chessecho.domain.clustering.PositionClusterAssignmentStatus
import com.chessecho.similarity.clustering.ClusterEvaluationResult
import com.chessecho.similarity.clustering.CommunityDetectionResult
import com.chessecho.similarity.clustering.PositionGraph
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class SizeDistributionStats(
    val count: Int,
    val mean: Double,
    val median: Double,
    val mode: Int,
    val min: Int,
    val max: Int,
    val p10: Double,
    val p25: Double,
    val p50: Double,
    val p75: Double,
    val p90: Double,
    val p99: Double,
)

data class SizeCategoryStats(
    val singletons: Int, // size 1
    val small: Int, // 2..5
    val medium: Int, // 6..25
    val large: Int, // 26..100
    val veryLarge: Int, // 101..500
    val mega: Int, // >500
    val totalClusters: Int,
    val singletonPct: Double,
    val smallPct: Double,
    val mediumPct: Double,
    val largePct: Double,
    val veryLargePct: Double,
    val megaPct: Double,
)

data class ClusteringSummaryMetrics(
    val totalPositions: Int,
    val assignedPositions: Int,
    val assignedPct: Double,
    val isolatePositions: Int,
    val isolatePct: Double,
    val outlierPositions: Int,
    val outlierPct: Double,
    val totalClusters: Int,
    val distribution: SizeDistributionStats,
    val categories: SizeCategoryStats,
    val totalEdges: Int,
    val leidenQuality: Double,
    val phaseTimingsMs: Map<String, Long>,
    val totalDurationMs: Long,
    val heapMemoryUsedMb: Double,
) {
    fun toJson(): String {
        return buildString {
            append("{\n")
            append("  \"totalPositions\": $totalPositions,\n")
            append("  \"assignedPositions\": $assignedPositions,\n")
            append("  \"assignedPct\": ${"%.2f".format(assignedPct)},\n")
            append("  \"isolatePositions\": $isolatePositions,\n")
            append("  \"isolatePct\": ${"%.2f".format(isolatePct)},\n")
            append("  \"outlierPositions\": $outlierPositions,\n")
            append("  \"outlierPct\": ${"%.2f".format(outlierPct)},\n")
            append("  \"totalClusters\": $totalClusters,\n")
            append("  \"totalEdges\": $totalEdges,\n")
            append("  \"leidenQuality\": ${"%.6f".format(leidenQuality)},\n")
            append("  \"distribution\": {\n")
            append("    \"mean\": ${"%.2f".format(distribution.mean)},\n")
            append("    \"median\": ${"%.2f".format(distribution.median)},\n")
            append("    \"mode\": ${distribution.mode},\n")
            append("    \"min\": ${distribution.min},\n")
            append("    \"max\": ${distribution.max},\n")
            append("    \"p10\": ${"%.2f".format(distribution.p10)},\n")
            append("    \"p25\": ${"%.2f".format(distribution.p25)},\n")
            append("    \"p50\": ${"%.2f".format(distribution.p50)},\n")
            append("    \"p75\": ${"%.2f".format(distribution.p75)},\n")
            append("    \"p90\": ${"%.2f".format(distribution.p90)},\n")
            append("    \"p99\": ${"%.2f".format(distribution.p99)}\n")
            append("  },\n")
            append("  \"categories\": {\n")
            append("    \"singletons\": ${categories.singletons} (${"%.1f".format(categories.singletonPct)}%),\n")
            append("    \"small\": ${categories.small} (${"%.1f".format(categories.smallPct)}%),\n")
            append("    \"medium\": ${categories.medium} (${"%.1f".format(categories.mediumPct)}%),\n")
            append("    \"large\": ${categories.large} (${"%.1f".format(categories.largePct)}%),\n")
            append("    \"veryLarge\": ${categories.veryLarge} (${"%.1f".format(categories.veryLargePct)}%),\n")
            append("    \"mega\": ${categories.mega} (${"%.1f".format(categories.megaPct)}%)\n")
            append("  },\n")
            append("  \"phaseTimingsMs\": {\n")
            phaseTimingsMs.entries.forEachIndexed { idx, (k, v) ->
                val comma = if (idx == phaseTimingsMs.size - 1) "" else ","
                append("    \"$k\": $v$comma\n")
            }
            append("  },\n")
            append("  \"totalDurationMs\": $totalDurationMs,\n")
            append("  \"heapMemoryUsedMb\": ${"%.2f".format(heapMemoryUsedMb)}\n")
            append("}")
        }
    }
}

object ResearchMetrics {
    fun computeDistributionStats(sizes: List<Int>): SizeDistributionStats {
        if (sizes.isEmpty()) {
            return SizeDistributionStats(0, 0.0, 0.0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val sorted = sizes.sorted()
        val count = sorted.size
        val mean = sorted.average()
        val median = percentile(sorted, 50.0)
        val mode = sorted.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: sorted.first()
        val min = sorted.first()
        val max = sorted.last()

        return SizeDistributionStats(
            count = count,
            mean = mean,
            median = median,
            mode = mode,
            min = min,
            max = max,
            p10 = percentile(sorted, 10.0),
            p25 = percentile(sorted, 25.0),
            p50 = median,
            p75 = percentile(sorted, 75.0),
            p90 = percentile(sorted, 90.0),
            p99 = percentile(sorted, 99.0),
        )
    }

    fun computeCategoryStats(sizes: List<Int>): SizeCategoryStats {
        val total = sizes.size
        if (total == 0) {
            return SizeCategoryStats(0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        var singletons = 0
        var small = 0
        var medium = 0
        var large = 0
        var veryLarge = 0
        var mega = 0

        for (s in sizes) {
            when {
                s == 1 -> singletons++
                s in 2..5 -> small++
                s in 6..25 -> medium++
                s in 26..100 -> large++
                s in 101..500 -> veryLarge++
                else -> mega++
            }
        }

        return SizeCategoryStats(
            singletons = singletons,
            small = small,
            medium = medium,
            large = large,
            veryLarge = veryLarge,
            mega = mega,
            totalClusters = total,
            singletonPct = (singletons.toDouble() / total) * 100.0,
            smallPct = (small.toDouble() / total) * 100.0,
            mediumPct = (medium.toDouble() / total) * 100.0,
            largePct = (large.toDouble() / total) * 100.0,
            veryLargePct = (veryLarge.toDouble() / total) * 100.0,
            megaPct = (mega.toDouble() / total) * 100.0,
        )
    }

    fun summarize(
        totalPositions: Int,
        graph: PositionGraph,
        leidenResult: CommunityDetectionResult,
        evaluationResult: ClusterEvaluationResult,
        timings: Map<String, Long>,
        totalDurationMs: Long,
        heapMemoryUsedMb: Double,
    ): ClusteringSummaryMetrics {
        val clusterSizes = evaluationResult.clusters.map { it.size }
        val distribution = computeDistributionStats(clusterSizes)
        val categories = computeCategoryStats(clusterSizes)

        var assigned = 0
        var isolates = 0
        var outliers = 0

        for (assignment in evaluationResult.assignments) {
            when (assignment.status) {
                PositionClusterAssignmentStatus.ASSIGNED -> assigned++
                PositionClusterAssignmentStatus.ISOLATE -> isolates++
                PositionClusterAssignmentStatus.OUTLIER_EVICTED -> outliers++
            }
        }

        return ClusteringSummaryMetrics(
            totalPositions = totalPositions,
            assignedPositions = assigned,
            assignedPct = if (totalPositions > 0) (assigned.toDouble() / totalPositions) * 100.0 else 0.0,
            isolatePositions = isolates,
            isolatePct = if (totalPositions > 0) (isolates.toDouble() / totalPositions) * 100.0 else 0.0,
            outlierPositions = outliers,
            outlierPct = if (totalPositions > 0) (outliers.toDouble() / totalPositions) * 100.0 else 0.0,
            totalClusters = evaluationResult.clusters.size,
            distribution = distribution,
            categories = categories,
            totalEdges = graph.edges.size,
            leidenQuality = leidenResult.quality,
            phaseTimingsMs = timings,
            totalDurationMs = totalDurationMs,
            heapMemoryUsedMb = heapMemoryUsedMb,
        )
    }

    private fun percentile(sorted: List<Int>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0].toDouble()
        val rank = (p / 100.0) * (sorted.size - 1)
        val low = floor(rank).toInt()
        val high = ceil(rank).toInt()
        val weight = rank - low
        return sorted[low] * (1.0 - weight) + sorted[high] * weight
    }
}
