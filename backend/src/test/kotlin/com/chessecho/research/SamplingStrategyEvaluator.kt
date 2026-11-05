package com.chessecho.research

import com.chessecho.similarity.clustering.PositionClusterVector
import java.util.Random
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

enum class SamplingStrategy {
    CENTROID_GREEDY,
    STRATIFIED_TIERED,
    BOUNDARY_WEIGHTED,
    ADAPTIVE_SEQUENTIAL,
}

data class ClusterSamplingResult(
    val clusterIndex: Int,
    val clusterSize: Int,
    val strategy: SamplingStrategy,
    val sampleSize: Int,
    val sampledPositionIds: Set<UUID>,
    val censusMeanEvalCp: Double,
    val sampleMeanEvalCp: Double,
    val evalErrorCp: Double,
    val censusStdDevCp: Double,
    val sampleStdDevCp: Double,
    val stdDevErrorCp: Double,
)

data class SamplingStrategyBenchmark(
    val strategy: SamplingStrategy,
    val totalClustersEvaluated: Int,
    val meanSampleSize: Double,
    val meanSquaredErrorCp: Double,
    val rootMeanSquaredErrorCp: Double,
    val meanAbsoluteErrorCp: Double,
    val meanBiasCp: Double,
    val stdDevEstimationErrorCp: Double,
)

class SamplingStrategyEvaluator {

    /**
     * Calculates sample size adjusted by Finite Population Correction (FPC).
     * n0 = (z / E)^2 * p * (1 - p)
     * nadj = n0 / (1 + (n0 - 1) / N)
     */
    fun calculateFpcSampleSize(clusterSize: Int, targetMarginCp: Double = 30.0, assumedStdDevCp: Double = 60.0): Int {
        if (clusterSize <= 3) return clusterSize
        val z = 1.96 // 95% confidence
        val n0 = ((z * assumedStdDevCp) / targetMarginCp).pow(2.0)
        val nadj = n0 / (1.0 + (n0 - 1.0) / clusterSize.toDouble())
        return ceil(nadj).toInt().coerceIn(3, clusterSize)
    }

    /**
     * Evaluates the 4 sampling strategies against complete census ground truth evaluations.
     */
    fun evaluateStrategies(
        clusterIndex: Int,
        positions: List<UUID>,
        centroidSimilarities: Map<UUID, Double>,
        censusEvals: Map<UUID, Double>, // positionId -> eval cp
        randomSeed: Long = 42L,
    ): Map<SamplingStrategy, ClusterSamplingResult> {
        val n = positions.size
        require(n > 0) { "Cluster must not be empty" }
        require(positions.all { censusEvals.containsKey(it) }) { "All positions must have census evaluations" }

        val censusVals = positions.map { censusEvals.getValue(it) }
        val censusMean = censusVals.average()
        val censusStd = calculateStdDev(censusVals, censusMean)

        val targetSampleSize = calculateFpcSampleSize(n)
        val rng = Random(randomSeed + clusterIndex)

        val sortedBySimDesc = positions.sortedByDescending { centroidSimilarities[it] ?: 0.0 }

        val results = mutableMapOf<SamplingStrategy, ClusterSamplingResult>()

        for (strategy in SamplingStrategy.values()) {
            val sampledIds = when (strategy) {
                SamplingStrategy.CENTROID_GREEDY -> {
                    // Top-k closest to centroid
                    sortedBySimDesc.take(targetSampleSize).toSet()
                }

                SamplingStrategy.STRATIFIED_TIERED -> {
                    // Divide into 3 equal tiers: Core (top 33%), Intermediate (mid 33%), Perimeter (bottom 33%)
                    val tierSize = (n / 3).coerceAtLeast(1)
                    val core = sortedBySimDesc.take(tierSize)
                    val mid = sortedBySimDesc.drop(tierSize).take(tierSize)
                    val perimeter = sortedBySimDesc.drop(tierSize * 2)

                    val kPerTier = (targetSampleSize / 3).coerceAtLeast(1)
                    val sample = mutableSetOf<UUID>()
                    sample.addAll(core.shuffled(rng).take(kPerTier))
                    sample.addAll(mid.shuffled(rng).take(kPerTier))
                    sample.addAll(perimeter.shuffled(rng).take(targetSampleSize - sample.size))
                    sample
                }

                SamplingStrategy.BOUNDARY_WEIGHTED -> {
                    // Oversample positions near the centroid threshold boundary (lowest similarities)
                    sortedBySimDesc.reversed().take(targetSampleSize).toSet()
                }

                SamplingStrategy.ADAPTIVE_SEQUENTIAL -> {
                    // Start with an initial pilot sample of size max(3, targetSampleSize / 2) from core
                    val pilotSize = (targetSampleSize / 2).coerceAtLeast(3)
                    val pilot = sortedBySimDesc.take(pilotSize).toMutableList()
                    val pilotEvals = pilot.map { censusEvals.getValue(it) }
                    val pilotStd = calculateStdDev(pilotEvals, pilotEvals.average())

                    // If variance is high (> 50 cp), draw remainder from perimeter/boundary, otherwise from intermediate
                    val remainingCount = targetSampleSize - pilot.size
                    val remainingPool = if (pilotStd > 50.0) {
                        sortedBySimDesc.reversed().filterNot { pilot.contains(it) }
                    } else {
                        sortedBySimDesc.drop(pilotSize).filterNot { pilot.contains(it) }
                    }
                    pilot.addAll(remainingPool.take(remainingCount))
                    pilot.toSet()
                }
            }

            val sampleVals = sampledIds.map { censusEvals.getValue(it) }
            val sampleMean = sampleVals.average()
            val sampleStd = calculateStdDev(sampleVals, sampleMean)

            val evalError = sampleMean - censusMean
            val stdDevError = sampleStd - censusStd

            results[strategy] = ClusterSamplingResult(
                clusterIndex = clusterIndex,
                clusterSize = n,
                strategy = strategy,
                sampleSize = sampledIds.size,
                sampledPositionIds = sampledIds,
                censusMeanEvalCp = censusMean,
                sampleMeanEvalCp = sampleMean,
                evalErrorCp = evalError,
                censusStdDevCp = censusStd,
                sampleStdDevCp = sampleStd,
                stdDevErrorCp = stdDevError,
            )
        }

        return results
    }

    /**
     * Aggregates benchmarks across multiple evaluated clusters.
     */
    fun summarizeBenchmarks(clusterResults: List<Map<SamplingStrategy, ClusterSamplingResult>>): Map<SamplingStrategy, SamplingStrategyBenchmark> {
        val byStrategy = SamplingStrategy.values().associateWith { strat ->
            clusterResults.mapNotNull { it[strat] }
        }

        return byStrategy.mapValues { (strat, results) ->
            val m = results.size
            if (m == 0) {
                SamplingStrategyBenchmark(strat, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            } else {
                val meanSampleSize = results.map { it.sampleSize }.average()
                val mse = results.map { it.evalErrorCp.pow(2.0) }.average()
                val rmse = sqrt(mse)
                val mae = results.map { abs(it.evalErrorCp) }.average()
                val bias = results.map { it.evalErrorCp }.average()
                val stdDevErr = results.map { abs(it.stdDevErrorCp) }.average()

                SamplingStrategyBenchmark(
                    strategy = strat,
                    totalClustersEvaluated = m,
                    meanSampleSize = meanSampleSize,
                    meanSquaredErrorCp = mse,
                    rootMeanSquaredErrorCp = rmse,
                    meanAbsoluteErrorCp = mae,
                    meanBiasCp = bias,
                    stdDevEstimationErrorCp = stdDevErr,
                )
            }
        }
    }

    private fun calculateStdDev(values: List<Double>, mean: Double): Double {
        if (values.size <= 1) return 0.0
        val sumSquares = values.sumOf { (it - mean).pow(2.0) }
        return sqrt(sumSquares / (values.size - 1))
    }
}
