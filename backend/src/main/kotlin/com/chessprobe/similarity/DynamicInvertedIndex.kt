package com.chessprobe.similarity

import java.util.UUID
import kotlin.math.ln

data class ScoredCandidate(
    val positionId: UUID,
    val canonicalFen: String,
    val score: Double,
)

data class TraversalMetrics(
    val postingsVisited: Long,
    val candidateCount: Int,
    val traversalLatencyMs: Double,
)

class DynamicInvertedIndex(
    docs: Collection<PositionDocument> = emptyList(),
) {
    private val documents = mutableMapOf<UUID, PositionDocument>()
    private val postings = mutableMapOf<String, MutableList<UUID>>()
    private val docLengths = mutableMapOf<UUID, Double>()
    private val docFrequencies = mutableMapOf<String, Int>()
    private var totalDocs: Int = 0
    private var avgDocLen: Double = 0.0

    val size: Int
        get() = documents.size

    @Volatile
    var lastTraversalMetrics: TraversalMetrics? = null
        private set

    init {
        if (docs.isNotEmpty()) {
            index(docs)
        }
    }

    fun index(docs: Collection<PositionDocument>) {
        docs.forEach { doc ->
            documents[doc.positionId] = doc
            val docLen = doc.terms.values.sum()
            docLengths[doc.positionId] = docLen
            doc.terms.filterValues { it > 0.0 }.keys.forEach { term ->
                postings.computeIfAbsent(term) { mutableListOf() }.add(doc.positionId)
                docFrequencies.merge(term, 1, Int::plus)
            }
        }
        totalDocs = documents.size
        avgDocLen = if (totalDocs > 0) docLengths.values.sum() / totalDocs else 0.0
    }

    fun retrieveCandidates(
        queryTerms: Set<String>,
        activeTurn: String,
        maxCandidates: Int = 100,
    ): List<ScoredCandidate> = retrieveCandidatesWithMetrics(queryTerms, activeTurn, maxCandidates).first

    fun retrieveCandidatesWithMetrics(
        queryTerms: Set<String>,
        activeTurn: String,
        maxCandidates: Int = 100,
    ): Pair<List<ScoredCandidate>, TraversalMetrics> {
        require(maxCandidates > 0) { "maxCandidates must be positive, but was: $maxCandidates" }

        val startNs = System.nanoTime()
        val validTurn = normalizeActiveTurn(activeTurn)

        if (queryTerms.isEmpty() || totalDocs == 0 || validTurn == null) {
            val emptyMetrics = buildMetrics(startNs, postingsVisited = 0L, candidateCount = 0)
            lastTraversalMetrics = emptyMetrics
            return Pair(emptyList(), emptyMetrics)
        }

        val (candidateIds, postingsVisited) = collectCandidates(queryTerms, validTurn)
        val scoredCandidates = scoreAndSortCandidates(candidateIds, queryTerms)
        val result = scoredCandidates.take(maxCandidates)

        val metrics = buildMetrics(startNs, postingsVisited, scoredCandidates.size)
        lastTraversalMetrics = metrics
        return Pair(result, metrics)
    }

    private fun normalizeActiveTurn(activeTurn: String): String? =
        when (activeTurn.trim().lowercase()) {
            "w", "white" -> "w"
            "b", "black" -> "b"
            else -> null
        }

    private fun collectCandidates(
        queryTerms: Set<String>,
        validTurn: String,
    ): Pair<Set<UUID>, Long> {
        var visited = 0L
        val candidateIds = mutableSetOf<UUID>()
        for (term in queryTerms) {
            val postingList = postings[term] ?: continue
            visited += postingList.size
            for (id in postingList) {
                val doc = documents[id] ?: continue
                if (doc.sideToMove == validTurn) {
                    candidateIds.add(id)
                }
            }
        }
        return Pair(candidateIds, visited)
    }

    private fun scoreAndSortCandidates(
        candidateIds: Set<UUID>,
        queryTerms: Set<String>,
    ): List<ScoredCandidate> {
        if (candidateIds.isEmpty() || avgDocLen == 0.0 || totalDocs == 0) return emptyList()

        val candidates = ArrayList<ScoredCandidate>(candidateIds.size)
        val sortedQueryTerms = queryTerms.sorted()
        val idfArray = computeIdfArray(sortedQueryTerms)

        for (id in candidateIds) {
            val doc = documents[id] ?: continue
            val score = scoreDocument(doc, sortedQueryTerms, idfArray)
            if (score > 0.0) {
                candidates.add(ScoredCandidate(id, doc.canonicalFen, score))
            }
        }

        candidates.sortWith(CANDIDATE_COMPARATOR)
        return candidates
    }

    private fun computeIdfArray(sortedQueryTerms: List<String>): DoubleArray {
        val idfArray = DoubleArray(sortedQueryTerms.size)
        for (i in sortedQueryTerms.indices) {
            val df = docFrequencies[sortedQueryTerms[i]] ?: 0
            idfArray[i] = ln(1.0 + (totalDocs - df + 0.5) / (df + 0.5))
        }
        return idfArray
    }

    private fun scoreDocument(
        doc: PositionDocument,
        sortedQueryTerms: List<String>,
        idfArray: DoubleArray,
    ): Double {
        val docLen = docLengths[doc.positionId] ?: doc.terms.values.sum()
        val normalization = K1 * (1.0 - B + B * docLen / avgDocLen)
        var score = 0.0
        for (i in sortedQueryTerms.indices) {
            val tf = doc.terms[sortedQueryTerms[i]] ?: continue
            if (tf <= 0.0) continue
            score += idfArray[i] * (tf * (K1 + 1.0)) / (tf + normalization)
        }
        return score
    }

    private fun buildMetrics(
        startNs: Long,
        postingsVisited: Long,
        candidateCount: Int,
    ): TraversalMetrics =
        TraversalMetrics(
            postingsVisited = postingsVisited,
            candidateCount = candidateCount,
            traversalLatencyMs = (System.nanoTime() - startNs) / 1_000_000.0,
        )

    fun estimatedHeapFootprintBytes(): Long {
        val docCount = documents.size.toLong()
        val totalPostings = postings.values.sumOf { it.size.toLong() }
        return docCount * 630L + totalPostings * 32L + 1024L
    }

    companion object {
        private const val K1 = 1.2
        private const val B = 0.75

        private val CANDIDATE_COMPARATOR =
            Comparator<ScoredCandidate> { a, b ->
                val scoreCmp = b.score.compareTo(a.score)
                if (scoreCmp != 0) return@Comparator scoreCmp
                val fenCmp = a.canonicalFen.compareTo(b.canonicalFen)
                if (fenCmp != 0) return@Comparator fenCmp
                a.positionId.compareTo(b.positionId)
            }
    }
}
