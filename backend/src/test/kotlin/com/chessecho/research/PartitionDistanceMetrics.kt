package com.chessecho.research

import java.util.UUID
import kotlin.math.ln
import kotlin.math.max

data class PartitionMetrics(
    val ami: Double,
    val ari: Double,
    val jaccardAssigned: Double,
    val variationOfInformation: Double,
    val normalizedVI: Double,
    val commonAssignedCount: Int,
    val totalUniverseCount: Int,
)

object PartitionDistanceMetrics {

    /**
     * Computes partition distance and similarity metrics between two clusterings.
     *
     * @param clusteringA Map of positionId to clusterId (null indicates isolate/outlier)
     * @param clusteringB Map of positionId to clusterId (null indicates isolate/outlier)
     */
    fun evaluate(
        clusteringA: Map<UUID, Int?>,
        clusteringB: Map<UUID, Int?>,
    ): PartitionMetrics {
        val universe = clusteringA.keys.intersect(clusteringB.keys)
        val nUniverse = universe.size

        val assignedA = clusteringA.filterValues { it != null }.keys
        val assignedB = clusteringB.filterValues { it != null }.keys
        val commonAssigned = assignedA.intersect(assignedB)

        val jaccardAssigned = if (assignedA.isEmpty() && assignedB.isEmpty()) {
            1.0
        } else {
            val union = assignedA.union(assignedB).size
            if (union > 0) commonAssigned.size.toDouble() / union else 0.0
        }

        // ARI and AMI on common assigned intersection
        val (ami, ari) = if (commonAssigned.size < 2) {
            Pair(1.0, 1.0)
        } else {
            computeAmiAndAri(
                commonAssigned.map { requireNotNull(clusteringA[it]) },
                commonAssigned.map { requireNotNull(clusteringB[it]) },
            )
        }

        // Variation of Information over entire universe with singletons for unassigned
        val vi = computeVariationOfInformation(universe, clusteringA, clusteringB)
        val maxVi = if (nUniverse > 1) ln(nUniverse.toDouble()) else 1.0
        val normalizedVi = (vi / maxVi).coerceIn(0.0, 1.0)

        return PartitionMetrics(
            ami = ami,
            ari = ari,
            jaccardAssigned = jaccardAssigned,
            variationOfInformation = vi,
            normalizedVI = normalizedVi,
            commonAssignedCount = commonAssigned.size,
            totalUniverseCount = nUniverse,
        )
    }

    private fun computeAmiAndAri(labelsA: List<Int>, labelsB: List<Int>): Pair<Double, Double> {
        val n = labelsA.size
        val mapA = labelsA.distinct().mapIndexed { idx, v -> v to idx }.toMap()
        val mapB = labelsB.distinct().mapIndexed { idx, v -> v to idx }.toMap()

        val r = mapA.size
        val c = mapB.size

        val contingency = Array(r) { IntArray(c) }
        val aSums = IntArray(r)
        val bSums = IntArray(c)

        for (k in 0 until n) {
            val i = mapA.getValue(labelsA[k])
            val j = mapB.getValue(labelsB[k])
            contingency[i][j]++
            aSums[i]++
            bSums[j]++
        }

        // ARI
        var sumNij2 = 0.0
        for (i in 0 until r) {
            for (j in 0 until c) {
                val nij = contingency[i][j]
                if (nij >= 2) sumNij2 += nij.toDouble() * (nij - 1) / 2.0
            }
        }

        var sumAi2 = 0.0
        for (i in 0 until r) {
            val ai = aSums[i]
            if (ai >= 2) sumAi2 += ai.toDouble() * (ai - 1) / 2.0
        }

        var sumBj2 = 0.0
        for (j in 0 until c) {
            val bj = bSums[j]
            if (bj >= 2) sumBj2 += bj.toDouble() * (bj - 1) / 2.0
        }

        val totalPairs = n.toDouble() * (n - 1) / 2.0
        val expectedIndex = (sumAi2 * sumBj2) / totalPairs
        val maxIndex = (sumAi2 + sumBj2) / 2.0
        val ari = if (maxIndex == expectedIndex) 1.0 else (sumNij2 - expectedIndex) / (maxIndex - expectedIndex)

        // Entropies and Mutual Information
        var ha = 0.0
        for (ai in aSums) {
            if (ai > 0) {
                val p = ai.toDouble() / n
                ha -= p * ln(p)
            }
        }

        var hb = 0.0
        for (bj in bSums) {
            if (bj > 0) {
                val p = bj.toDouble() / n
                hb -= p * ln(p)
            }
        }

        var mi = 0.0
        for (i in 0 until r) {
            for (j in 0 until c) {
                val nij = contingency[i][j]
                if (nij > 0) {
                    val p = nij.toDouble() / n
                    val expectedP = (aSums[i].toDouble() * bSums[j]) / (n.toDouble() * n)
                    mi += p * ln(p / expectedP)
                }
            }
        }

        // Expected MI (approximate or hypergeometric)
        val emi = computeExpectedMutualInformation(aSums, bSums, n)
        val denom = max(ha, hb) - emi
        val ami = if (denom <= 1e-12) 1.0 else (mi - emi) / denom

        return Pair(ami.coerceIn(0.0, 1.0), ari.coerceIn(-1.0, 1.0))
    }

    private fun computeVariationOfInformation(
        universe: Set<UUID>,
        clusteringA: Map<UUID, Int?>,
        clusteringB: Map<UUID, Int?>,
    ): Double {
        val n = universe.size
        if (n <= 1) return 0.0

        // Map unassigned to unique negative integers
        var nextNegative = -1
        val mappedA = mutableMapOf<UUID, Int>()
        val mappedB = mutableMapOf<UUID, Int>()

        for (u in universe) {
            val a = clusteringA[u]
            mappedA[u] = a ?: nextNegative--
            val b = clusteringB[u]
            mappedB[u] = b ?: nextNegative--
        }

        val countsA = mappedA.values.groupingBy { it }.eachCount()
        val countsB = mappedB.values.groupingBy { it }.eachCount()

        var ha = 0.0
        for (count in countsA.values) {
            val p = count.toDouble() / n
            ha -= p * ln(p)
        }

        var hb = 0.0
        for (count in countsB.values) {
            val p = count.toDouble() / n
            hb -= p * ln(p)
        }

        val jointCounts = mutableMapOf<Pair<Int, Int>, Int>()
        for (u in universe) {
            val pair = Pair(mappedA.getValue(u), mappedB.getValue(u))
            jointCounts[pair] = jointCounts.getOrDefault(pair, 0) + 1
        }

        var mi = 0.0
        for ((pair, count) in jointCounts) {
            val p = count.toDouble() / n
            val pa = countsA.getValue(pair.first).toDouble() / n
            val pb = countsB.getValue(pair.second).toDouble() / n
            mi += p * ln(p / (pa * pb))
        }

        return (ha + hb - 2.0 * mi).coerceAtLeast(0.0)
    }

    private fun computeExpectedMutualInformation(aSums: IntArray, bSums: IntArray, n: Int): Double {
        // Log factorials table
        val logFact = DoubleArray(n + 1)
        for (i in 2..n) {
            logFact[i] = logFact[i - 1] + ln(i.toDouble())
        }

        var emi = 0.0
        for (ai in aSums) {
            if (ai == 0) continue
            for (bj in bSums) {
                if (bj == 0) continue
                val minNij = max(1, ai + bj - n)
                val maxNij = minOf(ai, bj)
                if (minNij > maxNij) continue

                for (nij in minNij..maxNij) {
                    val logP = logFact[ai] + logFact[n - ai] + logFact[bj] + logFact[n - bj] -
                        logFact[n] - logFact[nij] - logFact[ai - nij] - logFact[bj - nij] - logFact[n - ai - bj + nij]
                    val p = kotlin.math.exp(logP)
                    val term = (nij.toDouble() / n) * ln((n.toDouble() * nij) / (ai.toDouble() * bj))
                    emi += p * term
                }
            }
        }
        return emi.coerceAtLeast(0.0)
    }
}
