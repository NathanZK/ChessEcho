package com.chessecho.similarity

import kotlin.math.ln

data class RankedPositionMatch(
    val document: PositionDocument,
    val score: Double,
    val rank: Int,
)

class PositionSimilarityScorer {
    fun score(
        query: PositionDocument,
        candidates: Collection<PositionDocument>,
    ): List<RankedPositionMatch> {
        val eligibleCandidates =
            candidates
                .filter { it.sideToMove.equals(query.sideToMove, ignoreCase = true) && it.canonicalFen != query.canonicalFen }
                .distinctBy { it.canonicalFen }

        val poolSize = eligibleCandidates.size
        if (poolSize == 0) return emptyList()

        val avgDocumentLength = eligibleCandidates.sumOf { it.documentLength } / poolSize.toDouble()
        val documentFrequencies = computeDocumentFrequencies(eligibleCandidates)

        val queryTermKeys = query.terms.keys

        return eligibleCandidates
            .map { candidate ->
                val bm25 = calculateBm25(queryTermKeys, candidate, poolSize, avgDocumentLength, documentFrequencies)
                candidate to bm25
            }
            .sortedWith(
                compareByDescending<Pair<PositionDocument, Double>> { it.second }
                    .thenBy { it.first.canonicalFen },
            )
            .mapIndexed { index, (doc, score) ->
                RankedPositionMatch(
                    document = doc,
                    score = score,
                    rank = index + 1,
                )
            }
    }

    private fun computeDocumentFrequencies(candidates: List<PositionDocument>): Map<String, Int> {
        val frequencies = mutableMapOf<String, Int>()
        candidates.forEach { doc ->
            doc.terms.keys.forEach { term ->
                frequencies.merge(term, 1, Int::plus)
            }
        }
        return frequencies
    }

    private fun calculateBm25(
        queryTerms: Set<String>,
        candidate: PositionDocument,
        poolSize: Int,
        avgdl: Double,
        documentFrequencies: Map<String, Int>,
    ): Double {
        var totalScore = 0.0
        val candidateTerms = candidate.terms
        val docLength = candidate.documentLength

        val denominatorFactor =
            if (avgdl > 0.0) {
                BM25_K1 * (1.0 - BM25_B + (BM25_B * (docLength / avgdl)))
            } else {
                BM25_K1
            }

        for (term in queryTerms) {
            val tf = candidateTerms[term] ?: continue
            val df = documentFrequencies[term] ?: continue
            val idf = ln(1.0 + ((poolSize - df + 0.5) / (df + 0.5)))
            val tfWeight = (tf * (BM25_K1 + 1.0)) / (tf + denominatorFactor)
            totalScore += idf * tfWeight
        }

        return totalScore
    }

    companion object {
        private const val BM25_K1 = 1.2
        private const val BM25_B = 0.75
    }
}
