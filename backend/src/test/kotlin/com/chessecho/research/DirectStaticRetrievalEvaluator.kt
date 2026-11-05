package com.chessecho.research

import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.PositionInvertedIndex
import com.chessecho.similarity.clustering.PositionClusterSimilarityScorer
import com.chessecho.similarity.clustering.PositionClusterVector
import java.util.UUID

object MetricDistributionCalculator {
    fun calculate(values: List<Int>): MetricDistribution {
        if (values.isEmpty()) {
            return MetricDistribution(0, 0, 0, 0, 0, 0, 0.0, 0, 0.0)
        }
        val sorted = values.sorted()
        val n = sorted.size
        val zeroCount = sorted.count { it == 0 }
        return MetricDistribution(
            min = sorted.first(),
            p25 = sorted[(n * 0.25).toInt().coerceAtMost(n - 1)],
            median = sorted[n / 2],
            p75 = sorted[(n * 0.75).toInt().coerceAtMost(n - 1)],
            p90 = sorted[(n * 0.90).toInt().coerceAtMost(n - 1)],
            max = sorted.last(),
            mean = sorted.average(),
            zeroCount = zeroCount,
            zeroPct = (zeroCount.toDouble() / n) * 100.0,
        )
    }
}

class DirectStaticRetrievalEvaluator(
    private val corpusDocs: List<PositionDocument>,
    private val probeQueries: List<PositionDocument>,
    private val referencesByQuery: Map<UUID, QueryReferenceSet>,
) {
    private val invertedIndex = PositionInvertedIndex().apply { index(corpusDocs) }
    private val scorer = PositionClusterSimilarityScorer()

    // Precompute corpus vectors across all 6 alphas for System 2B
    private val corpusVectorsByAlpha: Map<Double, Map<UUID, PositionClusterVector>>

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

        val vectorMap = mutableMapOf<Double, Map<UUID, PositionClusterVector>>()
        val alphas = listOf(0.00, 0.25, 0.50, 0.60, 0.75, 1.00)
        alphas.forEach { alpha ->
            val byId = mutableMapOf<UUID, PositionClusterVector>()
            corpusDocs.forEach { doc ->
                byId[doc.positionId] = scorer.buildVector(
                    positionId = doc.positionId,
                    canonicalFen = doc.canonicalFen,
                    terms = doc.terms,
                    idfs = idfMap,
                    alpha = alpha,
                )
            }
            vectorMap[alpha] = byId
        }
        corpusVectorsByAlpha = vectorMap
    }

    fun retrieveSystem2ACandidates(
        query: PositionDocument,
        budget: Int,
        alpha: Double,
    ): List<UUID> {
        val qTurn = query.sideToMove.lowercase()
        // Query slightly more than budget to allow self-match filtering
        val rawCandidates = invertedIndex.generateCandidates(
            queryTerms = query.terms.keys,
            activeTurn = qTurn,
            maxCandidates = budget + 10,
            alpha = alpha,
        )
        // Self-exclusion, side-exclusion (defensive), and truncation to min(budget, eligible)
        return rawCandidates
            .filter { it.positionId != query.positionId && it.sideToMove.lowercase() == qTurn }
            .take(budget)
            .map { it.positionId }
    }

    fun retrieveSystem2BCandidates(
        query: PositionDocument,
        budget: Int,
        alpha: Double,
    ): List<UUID> {
        val qId = query.positionId
        val qTurn = query.sideToMove.lowercase()
        val vectorsForAlpha = corpusVectorsByAlpha.getValue(alpha)
        val qVec = vectorsForAlpha.getValue(qId)

        val universe = corpusDocs.filter { it.positionId != qId && it.sideToMove.lowercase() == qTurn }

        val scored = universe.map { cand ->
            val cVec = vectorsForAlpha.getValue(cand.positionId)
            val sim = scorer.cosineSimilarity(qVec, cVec)
            Triple(cand.positionId, cand.canonicalFen, sim)
        }

        // Deterministic tie-breaking: similarity DESC, canonicalFen ASC, positionId ASC
        val sorted = scored.sortedWith(
            compareByDescending<Triple<UUID, String, Double>> { it.third }
                .thenBy { it.second }
                .thenBy { it.first }
        )

        return sorted.take(budget).map { it.first }
    }

    fun evaluateDirectSystem(
        systemId: String,
        systemType: String,
        budget: Int,
        alphas: List<Double>,
        candidateProvider: (query: PositionDocument, budget: Int, alpha: Double) -> List<UUID>,
    ): SystemAblationRecord {
        val alphaResults = mutableMapOf<String, AlphaSystemMetrics>()

        alphas.forEach { alpha ->
            val alphaKey = "alpha_${alpha}"
            var totalCands = 0
            var totalOppositeExcluded = 0

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

            probeQueries.forEach { q ->
                val ref = referencesByQuery.getValue(q.positionId)
                val cands = candidateProvider(q, budget, alpha)
                val candSet = cands.toSet()
                val candCount = cands.size
                totalCands += candCount

                // BM25 metrics
                val bm25Ret10 = candSet.intersect(ref.bm25Top10).size
                val bm25Ret20 = candSet.intersect(ref.bm25Top20).size
                bm25Retained10List.add(bm25Ret10)
                bm25Retained20List.add(bm25Ret20)

                bm25SumRecall10 += bm25Ret10 / 10.0
                bm25SumRecall20 += bm25Ret20 / 20.0
                bm25SumPosRecall10 += bm25Ret10 / 10.0 // Identical since positiveCount in top10 is 10
                bm25SumPosRecall20 += bm25Ret20 / 20.0
                bm25SumPurity10 += if (candCount > 0) bm25Ret10.toDouble() / candCount else 0.0
                bm25SumPurity20 += if (candCount > 0) bm25Ret20.toDouble() / candCount else 0.0

                // TF-IDF metrics
                val tfidfRet10 = candSet.intersect(ref.tfidfTop10).size
                val tfidfRet20 = candSet.intersect(ref.tfidfTop20).size
                tfidfRetained10List.add(tfidfRet10)
                tfidfRetained20List.add(tfidfRet20)

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

            alphaResults[alphaKey] = AlphaSystemMetrics(
                alpha = alpha,
                meanCandidateCount = meanCandCount,
                candidateReductionRate = crr,
                oppositeSideCandidatesExcludedCount = totalOppositeExcluded,
                bm25Metrics = bm25Metrics,
                tfidfMetrics = tfidfMetrics,
                lineageBreakdown = null,
            )
        }

        return SystemAblationRecord(
            systemId = systemId,
            systemType = systemType,
            targetBudget = budget,
            alphaResults = alphaResults,
        )
    }
}
