package com.chessprobe.similarity

import com.chessprobe.similarity.clustering.isPawnToken
import java.util.UUID
import kotlin.math.ln

class PositionInvertedIndex : PositionSimilarityCandidateGenerator {
    private val documents = mutableMapOf<UUID, PositionDocument>()
    private val postings = mutableMapOf<String, MutableList<UUID>>()

    fun index(docs: Collection<PositionDocument>) {
        docs.forEach { doc ->
            documents[doc.positionId] = doc
            doc.terms.keys.forEach { term ->
                postings.computeIfAbsent(term) { mutableListOf() }.add(doc.positionId)
            }
        }
    }

    fun queryCandidates(
        queryTerms: Set<String>,
        activeTurn: String,
        maxCandidates: Int = PositionSimilarityCandidateGenerator.DEFAULT_MAX_CANDIDATES,
    ): List<PositionDocument> = generateCandidates(queryTerms, activeTurn, maxCandidates)

    fun queryCandidates(
        queryTerms: Set<String>,
        maxCandidates: Int = PositionSimilarityCandidateGenerator.DEFAULT_MAX_CANDIDATES,
    ): List<PositionDocument> = generateCandidates(queryTerms, maxCandidates)

    override fun generateCandidates(
        queryTerms: Set<String>,
        activeTurn: String,
        maxCandidates: Int,
    ): List<PositionDocument> = generateCandidates(queryTerms, activeTurn, maxCandidates, alpha = null)

    fun generateCandidates(
        queryTerms: Set<String>,
        activeTurn: String,
        maxCandidates: Int = PositionSimilarityCandidateGenerator.DEFAULT_MAX_CANDIDATES,
        alpha: Double? = null,
    ): List<PositionDocument> {
        if (alpha != null) {
            require(alpha.isFinite() && alpha in 0.0..1.0) { "alpha must be finite and in [0.0, 1.0]" }
        }

        val totalDocs = documents.size
        if (queryTerms.isEmpty() || totalDocs == 0) return emptyList()

        val candidateScores = accumulatePostingScores(queryTerms, totalDocs, alpha)
        if (candidateScores.isEmpty()) return emptyList()

        val normalizedActiveTurn = activeTurn.lowercase()
        return candidateScores.entries
            .asSequence()
            .mapNotNull { (id, score) -> documents[id]?.let { it to score } }
            .filter { (doc, _) -> doc.sideToMove == normalizedActiveTurn }
            .sortedWith(
                compareByDescending<Pair<PositionDocument, Double>> { it.second }
                    .thenBy { it.first.canonicalFen },
            ).take(maxCandidates)
            .map { it.first }
            .toList()
    }

    private fun accumulatePostingScores(
        queryTerms: Set<String>,
        totalDocs: Int,
        alpha: Double?,
    ): Map<UUID, Double> {
        val pawnMultiplier = computePawnMultiplier(queryTerms, alpha)
        val pieceMultiplier = computePieceMultiplier(queryTerms, alpha)
        val candidateScores = mutableMapOf<UUID, Double>()

        queryTerms.forEach { term ->
            val factor = resolveTermFactor(term, alpha, pawnMultiplier, pieceMultiplier)
            if (factor > 0.0) {
                accumulateTermPostings(term, factor, totalDocs, candidateScores)
            }
        }
        return candidateScores
    }

    private fun computePawnMultiplier(
        queryTerms: Set<String>,
        alpha: Double?,
    ): Double {
        if (alpha == null) return 1.0
        val count = queryTerms.count { isPawnToken(it) }
        return if (count > 0 && alpha > 0.0) alpha / count.toDouble() else 0.0
    }

    private fun computePieceMultiplier(
        queryTerms: Set<String>,
        alpha: Double?,
    ): Double {
        if (alpha == null) return 1.0
        val pawnCount = queryTerms.count { isPawnToken(it) }
        val pieceCount = queryTerms.size - pawnCount
        return if (pieceCount > 0 && alpha < 1.0) (1.0 - alpha) / pieceCount.toDouble() else 0.0
    }

    private fun resolveTermFactor(
        term: String,
        alpha: Double?,
        pawnMultiplier: Double,
        pieceMultiplier: Double,
    ): Double =
        when {
            alpha == null -> 1.0
            isPawnToken(term) -> pawnMultiplier
            else -> pieceMultiplier
        }

    private fun accumulateTermPostings(
        term: String,
        factor: Double,
        totalDocs: Int,
        scores: MutableMap<UUID, Double>,
    ) {
        val postingList = postings[term] ?: return
        val df = postingList.size
        val idf = ln(1.0 + ((totalDocs - df + 0.5) / (df + 0.5)))
        val weightedIdf = factor * idf
        postingList.forEach { id ->
            scores.merge(id, weightedIdf, Double::plus)
        }
    }
}
