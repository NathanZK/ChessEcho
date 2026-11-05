package com.chessecho.research

import java.util.UUID
import kotlin.math.ln
import kotlin.math.sqrt

data class ScoredDynamicCandidate(
    val positionId: UUID,
    val canonicalFen: String,
    val score: Double,
    val rank: Int,
)

/**
 * Dynamic TF-IDF vector encoder and Cosine Similarity scorer.
 * Operates on unblocked dynamic tactical tokens extracted by Bizjak methodology.
 *
 * Implements:
 * 1. Robertson-Spärck Jones IDF matching Robertson-Jones BM25:
 *    idf(t) = ln(1.0 + (N - df(t) + 0.5) / (df(t) + 0.5))
 * 2. Vector weights: w(t, d) = tf(t, d) * idf(t)
 * 3. Cosine similarity: dot(q, d) / (||q||_2 * ||d||_2)
 */
class DynamicTfidfScorer {
    fun computeIdf(
        documentCount: Int,
        documentFrequencies: Map<String, Int>,
    ): Map<String, Double> {
        if (documentCount <= 0) return emptyMap()
        return documentFrequencies.mapValues { (_, df) ->
            ln(1.0 + (documentCount - df + 0.5) / (df + 0.5)).coerceAtLeast(0.0)
        }
    }

    fun encodeVector(
        terms: Map<String, Double>,
        idfMap: Map<String, Double>,
    ): Map<String, Double> {
        val weighted = mutableMapOf<String, Double>()
        for ((term, tf) in terms) {
            if (tf <= 0.0) continue
            val idf = idfMap[term] ?: 0.0
            if (idf > 0.0) {
                weighted[term] = tf * idf
            }
        }
        return weighted
    }

    fun vectorNorm(vector: Map<String, Double>): Double {
        var sumSq = 0.0
        for (v in vector.values) {
            sumSq += v * v
        }
        return sqrt(sumSq)
    }

    fun cosineSimilarity(
        queryVector: Map<String, Double>,
        queryNorm: Double,
        docVector: Map<String, Double>,
    ): Double {
        if (queryNorm == 0.0) return 0.0
        val docNorm = vectorNorm(docVector)
        if (docNorm == 0.0) return 0.0

        var dot = 0.0
        for ((term, qWeight) in queryVector) {
            val dWeight = docVector[term]
            if (dWeight != null) {
                dot += qWeight * dWeight
            }
        }
        return (dot / (queryNorm * docNorm)).coerceIn(0.0, 1.0)
    }

    fun scoreAndRank(
        queryTerms: Map<String, Double>,
        candidateDocs: List<Pair<UUID, String>>, // (positionId, canonicalFen)
        candidateTerms: Map<UUID, Map<String, Double>>,
        documentFrequencies: Map<String, Int>,
        documentCount: Int,
    ): List<ScoredDynamicCandidate> {
        if (candidateDocs.isEmpty()) return emptyList()

        val idfMap = computeIdf(documentCount, documentFrequencies)
        val queryVector = encodeVector(queryTerms, idfMap)
        val queryNorm = vectorNorm(queryVector)

        val scored =
            candidateDocs.map { (id, fen) ->
                val terms = candidateTerms[id].orEmpty()
                val docVec = encodeVector(terms, idfMap)
                val sim = if (queryNorm > 0.0) cosineSimilarity(queryVector, queryNorm, docVec) else 0.0
                id to Pair(fen, sim)
            }

        return scored
            .sortedWith(
                compareByDescending<Pair<UUID, Pair<String, Double>>> { it.second.second }
                    .thenBy { it.second.first },
            ).mapIndexed { index, (id, pair) ->
                ScoredDynamicCandidate(
                    positionId = id,
                    canonicalFen = pair.first,
                    score = pair.second,
                    rank = index + 1,
                )
            }
    }
}
