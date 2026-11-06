package com.chessecho.research

import com.chessecho.similarity.PositionDocument
import kotlin.math.ln
import kotlin.math.max

data class CandidatePoolResult(
    val requestedBudget: Int,
    val candidates: List<PositionDocument>,
    val actualCandidateCount: Int,
    val positiveCandidateCount: Int,
    val isPoolExhausted: Boolean,
)

class ConnectivityCandidateSelector(
    private val corpus: List<PositionDocument>,
    private val representationExtractor: (PositionDocument) -> Set<String>,
    private val k1: Double = 1.2,
    private val b: Double = 0.75,
) {
    // Precompute terms and document lengths for all corpus documents
    private val docTerms: Map<PositionDocument, Set<String>> = corpus.associateWith { representationExtractor(it) }
    private val docLengths: Map<PositionDocument, Int> = docTerms.mapValues { it.value.size }
    private val corpusSize: Int = max(1, corpus.size)

    // Document frequencies and inverted index postings list
    val docFrequencies: Map<String, Int>
    private val postings: Map<String, List<PositionDocument>>

    init {
        val freqs = mutableMapOf<String, Int>()
        val posts = mutableMapOf<String, MutableList<PositionDocument>>()
        for (doc in corpus) {
            val terms = docTerms.getValue(doc)
            for (term in terms) {
                freqs.merge(term, 1, Int::plus)
                posts.computeIfAbsent(term) { mutableListOf() }.add(doc)
            }
        }
        docFrequencies = freqs
        postings = posts
    }

    // Average document length across the corpus
    val avgdl: Double = if (docLengths.isNotEmpty()) {
        docLengths.values.sum().toDouble() / docLengths.size
    } else {
        1.0
    }

    // Precomputed term weights / IDF
    private val termIdfs: Map<String, Double> = docFrequencies.mapValues { (_, df) ->
        ln(1.0 + (corpusSize - df + 0.5) / (df + 0.5))
    }

    fun score(query: PositionDocument, candidate: PositionDocument): Double {
        val qTerms = docTerms[query] ?: representationExtractor(query)
        val cTerms = docTerms[candidate] ?: representationExtractor(candidate)
        val cLen = docLengths[candidate] ?: cTerms.size

        if (qTerms.isEmpty() || cTerms.isEmpty()) return 0.0

        val sharedTerms = qTerms.intersect(cTerms)
        if (sharedTerms.isEmpty()) return 0.0

        val safeAvgdl = if (avgdl > 0.0) avgdl else 1.0
        var totalScore = 0.0

        for (term in sharedTerms) {
            val idf = termIdfs[term] ?: 0.0
            val tf = 1.0 // Binary term frequency
            val denom = tf + k1 * (1.0 - b + b * (cLen / safeAvgdl))
            val termWeight = idf * (tf * (k1 + 1.0)) / denom
            totalScore += termWeight
        }

        return max(0.0, totalScore)
    }

    fun selectCandidates(query: PositionDocument, requestedBudget: Int): CandidatePoolResult {
        val qTerms = docTerms[query] ?: representationExtractor(query)
        val querySide = extractSideToMove(query.fen)
        val safeAvgdl = if (avgdl > 0.0) avgdl else 1.0

        if (qTerms.isEmpty()) {
            return CandidatePoolResult(
                requestedBudget = requestedBudget,
                candidates = emptyList(),
                actualCandidateCount = 0,
                positiveCandidateCount = 0,
                isPoolExhausted = true,
            )
        }

        // Accumulate BM25 scores only for candidates with >= 1 shared term
        val scores = mutableMapOf<PositionDocument, Double>()
        for (term in qTerms) {
            val candidateList = postings[term] ?: continue
            val idf = termIdfs[term] ?: continue
            for (cand in candidateList) {
                if (cand.canonicalFen == query.canonicalFen) continue
                if (extractSideToMove(cand.fen) != querySide) continue

                val cLen = docLengths[cand] ?: 0
                val tf = 1.0
                val denom = tf + k1 * (1.0 - b + b * (cLen / safeAvgdl))
                val termScore = idf * (tf * (k1 + 1.0)) / denom

                scores.merge(cand, termScore, Double::plus)
            }
        }

        val positiveScoring = scores.entries
            .filter { it.value > 0.0 }
            .sortedWith(
                compareByDescending<Map.Entry<PositionDocument, Double>> { it.value }
                    .thenBy { it.key.canonicalFen }
                    .thenBy { it.key.positionId }
            )

        val positiveCount = positiveScoring.size
        val selected = positiveScoring.take(requestedBudget).map { it.key }
        val actualCount = selected.size
        val isExhausted = positiveCount < requestedBudget

        return CandidatePoolResult(
            requestedBudget = requestedBudget,
            candidates = selected,
            actualCandidateCount = actualCount,
            positiveCandidateCount = positiveCount,
            isPoolExhausted = isExhausted,
        )
    }

    private fun extractSideToMove(fen: String): String {
        val parts = fen.trim().split("\\s+".toRegex())
        return if (parts.size >= 2) parts[1].lowercase() else "w"
    }
}
