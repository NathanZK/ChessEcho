package com.chessecho.research

import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

data class ClusterDynamicMetrics(
    val clusterIndex: Int,
    val clusterSize: Int,
    val evaluatedCount: Int,
    val meanEvaluationCp: Double,
    val stdDevEvaluationCp: Double,
    val withinClusterVariance: Double,
    val meanTop2GapCp: Double,
    val stdDevTop2GapCp: Double,
    val bestMoveAgreementDepth8Vs16: Double,
    val bestMoveAgreementDepth12Vs16: Double,
)

data class DynamicCoherenceSummary(
    val totalClustersAnalyzed: Int,
    val totalPositionsAnalyzed: Int,
    val pooledWithinVariance: Double,
    val betweenVariance: Double,
    val varianceRatioEtaSquared: Double,
    val meanClusterStdDevCp: Double,
    val meanBestMoveAgreementD8Vs16: Double,
    val meanBestMoveAgreementD12Vs16: Double,
    val hypothesis6Satisfied: Boolean,
    val clusterMetrics: List<ClusterDynamicMetrics>,
)

class DynamicCoherenceEvaluator {

    fun evaluateClusterCoherence(
        clusterIndex: Int,
        positions: List<UUID>,
        evaluationsByDepth: Map<Int, Map<UUID, EngineEvaluation>>,
    ): ClusterDynamicMetrics {
        val d16Evals = evaluationsByDepth[16]?.filterKeys { positions.contains(it) } ?: emptyMap()
        val d8Evals = evaluationsByDepth[8]?.filterKeys { positions.contains(it) } ?: emptyMap()
        val d12Evals = evaluationsByDepth[12]?.filterKeys { positions.contains(it) } ?: emptyMap()

        val validPositions = positions.filter { d16Evals.containsKey(it) }
        val n = validPositions.size
        if (n == 0) {
            return ClusterDynamicMetrics(clusterIndex, positions.size, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        val evalsCp = validPositions.map { d16Evals.getValue(it).scoreCpWhite.toDouble().coerceIn(-1000.0, 1000.0) }
        val meanEval = evalsCp.average()
        val varWithin = if (n > 1) evalsCp.sumOf { (it - meanEval).pow(2.0) } / (n - 1) else 0.0
        val stdWithin = sqrt(varWithin)

        val top2Gaps = validPositions.map { d16Evals.getValue(it).top2GapCp.toDouble().coerceIn(0.0, 1000.0) }
        val meanTop2 = top2Gaps.average()
        val stdTop2 = if (n > 1) sqrt(top2Gaps.sumOf { (it - meanTop2).pow(2.0) } / (n - 1)) else 0.0

        val commonD8 = validPositions.filter { d8Evals.containsKey(it) }
        val agreeD8 = if (commonD8.isNotEmpty()) {
            commonD8.count { d8Evals.getValue(it).bestMove == d16Evals.getValue(it).bestMove }.toDouble() / commonD8.size
        } else 0.0

        val commonD12 = validPositions.filter { d12Evals.containsKey(it) }
        val agreeD12 = if (commonD12.isNotEmpty()) {
            commonD12.count { d12Evals.getValue(it).bestMove == d16Evals.getValue(it).bestMove }.toDouble() / commonD12.size
        } else 0.0

        return ClusterDynamicMetrics(
            clusterIndex = clusterIndex,
            clusterSize = positions.size,
            evaluatedCount = n,
            meanEvaluationCp = meanEval,
            stdDevEvaluationCp = stdWithin,
            withinClusterVariance = varWithin,
            meanTop2GapCp = meanTop2,
            stdDevTop2GapCp = stdTop2,
            bestMoveAgreementDepth8Vs16 = agreeD8,
            bestMoveAgreementDepth12Vs16 = agreeD12,
        )
    }

    fun summarizeCoherence(clusterMetrics: List<ClusterDynamicMetrics>): DynamicCoherenceSummary {
        val nonTrivial = clusterMetrics.filter { it.evaluatedCount >= 2 }
        if (nonTrivial.isEmpty()) {
            return DynamicCoherenceSummary(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false, clusterMetrics)
        }

        val totalPositions = nonTrivial.sumOf { it.evaluatedCount }
        val grandMean = nonTrivial.sumOf { it.evaluatedCount * it.meanEvaluationCp } / totalPositions

        // Standard One-Way ANOVA:
        // SS_between = sum_i n_i * (mean_i - grandMean)^2
        val ssBetween = nonTrivial.sumOf { it.evaluatedCount * (it.meanEvaluationCp - grandMean).pow(2.0) }
        // SS_within = sum_i (n_i - 1) * s_i^2
        val ssWithin = nonTrivial.sumOf { (it.evaluatedCount - 1) * it.withinClusterVariance }
        val ssTotal = ssBetween + ssWithin

        val dfBetween = nonTrivial.size - 1
        val dfWithin = totalPositions - nonTrivial.size

        val msBetween = if (dfBetween > 0) ssBetween / dfBetween else 0.0
        val msWithin = if (dfWithin > 0) ssWithin / dfWithin else 0.0
        val etaSquared = if (ssTotal > 0.0) ssBetween / ssTotal else 0.0

        val meanStdDev = nonTrivial.map { it.stdDevEvaluationCp }.average()
        val meanAgreeD8 = nonTrivial.map { it.bestMoveAgreementDepth8Vs16 }.average()
        val meanAgreeD12 = nonTrivial.map { it.bestMoveAgreementDepth12Vs16 }.average()

        return DynamicCoherenceSummary(
            totalClustersAnalyzed = clusterMetrics.size,
            totalPositionsAnalyzed = totalPositions,
            pooledWithinVariance = msWithin,
            betweenVariance = msBetween,
            varianceRatioEtaSquared = etaSquared,
            meanClusterStdDevCp = meanStdDev,
            meanBestMoveAgreementD8Vs16 = meanAgreeD8,
            meanBestMoveAgreementD12Vs16 = meanAgreeD12,
            hypothesis6Satisfied = meanAgreeD8 >= 0.70,
            clusterMetrics = clusterMetrics,
        )
    }
}
