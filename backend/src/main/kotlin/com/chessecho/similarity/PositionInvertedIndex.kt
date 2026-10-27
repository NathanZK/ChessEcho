package com.chessecho.similarity

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
        maxCandidates: Int = PositionSimilarityCandidateGenerator.DEFAULT_MAX_CANDIDATES,
    ): List<PositionDocument> = generateCandidates(queryTerms, maxCandidates)

    override fun generateCandidates(
        queryTerms: Set<String>,
        maxCandidates: Int,
    ): List<PositionDocument> {
        val totalDocs = documents.size
        if (queryTerms.isEmpty() || totalDocs == 0) return emptyList()

        val candidateScores = mutableMapOf<UUID, Double>()
        queryTerms.forEach { term ->
            val postingList = postings[term] ?: return@forEach
            val df = postingList.size
            val idf = ln(1.0 + ((totalDocs - df + 0.5) / (df + 0.5)))
            postingList.forEach { id ->
                candidateScores.merge(id, idf, Double::plus)
            }
        }

        if (candidateScores.isEmpty()) return emptyList()

        return candidateScores.entries
            .asSequence()
            .mapNotNull { (id, score) -> documents[id]?.let { it to score } }
            .sortedWith(
                compareByDescending<Pair<PositionDocument, Double>> { it.second }
                    .thenBy { it.first.canonicalFen },
            )
            .take(maxCandidates)
            .map { it.first }
            .toList()
    }
}
