package com.chessecho.research

import com.chessecho.similarity.DynamicTacticalScorer
import com.chessecho.similarity.PositionDocument
import java.util.UUID
import kotlin.math.ln
import kotlin.math.sqrt

enum class AggregationModelType {
    MODEL_A_SUMMED_CLAMPED,
    MODEL_B_BINARY,
    MODEL_C_INDIVIDUAL_RANK,
    MODEL_D_CUMULATIVE_RANKS,
}

sealed class DynamicAggregationScheme {
    object ModelASummedClamped : DynamicAggregationScheme()
    object ModelBBinary : DynamicAggregationScheme()
    data class ModelCIndividualRank(val rank: Int) : DynamicAggregationScheme()
    data class ModelDCumulativeRanks(val maxRank: Int) : DynamicAggregationScheme()
}

object Depth8TokenAggregator {
    fun aggregate(
        record: Depth8CachedEngineRecord,
        scheme: DynamicAggregationScheme,
        excludeIncomplete: Boolean = false,
    ): Map<String, Double> {
        val pvsByRank = record.principalVariations.associateBy { it.rank }
        val tokensByRank = record.tokensByRank

        val validRanks: List<Int> = when (scheme) {
            is DynamicAggregationScheme.ModelASummedClamped,
            is DynamicAggregationScheme.ModelBBinary -> {
                tokensByRank.keys.mapNotNull { it.toIntOrNull() }.sorted()
            }
            is DynamicAggregationScheme.ModelCIndividualRank -> {
                listOf(scheme.rank)
            }
            is DynamicAggregationScheme.ModelDCumulativeRanks -> {
                tokensByRank.keys.mapNotNull { it.toIntOrNull() }.filter { it <= scheme.maxRank }.sorted()
            }
        }.filter { rank ->
            if (!excludeIncomplete) true
            else {
                val pv = pvsByRank[rank]
                val extReason = pv?.terminationReason
                extReason == null || extReason == "QUIET_STOP" || extReason == "TERMINAL"
            }
        }

        if (validRanks.isEmpty()) return emptyMap()

        return when (scheme) {
            is DynamicAggregationScheme.ModelASummedClamped,
            is DynamicAggregationScheme.ModelDCumulativeRanks -> {
                val aggregated = mutableMapOf<String, Double>()
                for (rank in validRanks) {
                    val rankTokens = tokensByRank[rank.toString()] ?: continue
                    for ((token, freq) in rankTokens) {
                        aggregated.merge(token, freq, Double::plus)
                    }
                }
                // Apply clamped episode flags: ?px, ?ox, ?+, ?=, ?#, ?S
                aggregated.mapValues { (token, sumTf) ->
                    if (token.startsWith("?")) {
                        sumTf.coerceAtMost(1.0)
                    } else {
                        sumTf
                    }
                }
            }
            is DynamicAggregationScheme.ModelBBinary -> {
                val allTokens = mutableSetOf<String>()
                for (rank in validRanks) {
                    val rankTokens = tokensByRank[rank.toString()] ?: continue
                    allTokens.addAll(rankTokens.keys)
                }
                allTokens.associateWith { 1.0 }
            }
            is DynamicAggregationScheme.ModelCIndividualRank -> {
                tokensByRank[scheme.rank.toString()] ?: emptyMap()
            }
        }
    }
}

data class ScoredCandidate(
    val positionId: UUID,
    val canonicalFen: String,
    val score: Double,
)

class Depth8DynamicInvertedIndex(
    val corpusDocs: List<PositionDocument>,
    val docTokensById: Map<UUID, Map<String, Double>>,
) {
    private val docLengths: Map<UUID, Double> = docTokensById.mapValues { (_, terms) -> terms.values.sum() }
    private val totalDocs: Int = corpusDocs.size
    private val avgDocLen: Double = if (totalDocs > 0) docLengths.values.sum() / totalDocs else 0.0

    private val docFreqs: Map<String, Int>
    private val idfMap: Map<String, Double>
    private val vectorsById: Map<UUID, Map<String, Double>>
    private val vectorNormsById: Map<UUID, Double>

    private val tfidfScorer = DynamicTfidfScorer()

    init {
        val freqs = mutableMapOf<String, Int>()
        docTokensById.values.forEach { tokens ->
            tokens.filterValues { it > 0.0 }.keys.forEach { term ->
                freqs.merge(term, 1, Int::plus)
            }
        }
        docFreqs = freqs

        idfMap = tfidfScorer.computeIdf(totalDocs, docFreqs)

        val vecs = mutableMapOf<UUID, Map<String, Double>>()
        val norms = mutableMapOf<UUID, Double>()
        docTokensById.forEach { (id, tokens) ->
            val v = tfidfScorer.encodeVector(tokens, idfMap)
            vecs[id] = v
            norms[id] = tfidfScorer.vectorNorm(v)
        }
        vectorsById = vecs
        vectorNormsById = norms
    }

    fun retrieveBm25Candidates(
        queryFen: String,
        querySideToMove: String,
        queryTerms: Map<String, Double>,
    ): List<ScoredCandidate> {
        val qTermSet = queryTerms.filterValues { it > 0.0 }.keys
        val qTurn = querySideToMove.lowercase()

        // Same-side, canonicalFen != queryFen
        val universe = corpusDocs.filter {
            it.sideToMove.lowercase() == qTurn && it.canonicalFen != queryFen
        }

        if (qTermSet.isEmpty()) return emptyList()

        val scored = ArrayList<ScoredCandidate>(universe.size)
        for (cand in universe) {
            val cId = cand.positionId
            val cTerms = docTokensById[cId].orEmpty()
            val len = docLengths[cId] ?: 0.0

            val score = DynamicTacticalScorer.bm25Score(
                queryTerms = qTermSet,
                documentTerms = cTerms,
                documentFrequencies = docFreqs,
                documentCount = totalDocs,
                documentLength = len,
                averageDocumentLength = avgDocLen,
            )
            if (score > 0.0) {
                scored.add(ScoredCandidate(cId, cand.canonicalFen, score))
            }
        }

        // 3-tuple deterministic tie-breaking: score DESC, canonicalFen ASC, positionId ASC
        scored.sortWith(
            compareByDescending<ScoredCandidate> { it.score }
                .thenBy { it.canonicalFen }
                .thenBy { it.positionId }
        )
        return scored
    }

    fun retrieveTfidfCandidates(
        queryFen: String,
        querySideToMove: String,
        queryTerms: Map<String, Double>,
    ): List<ScoredCandidate> {
        val qTurn = querySideToMove.lowercase()
        val universe = corpusDocs.filter {
            it.sideToMove.lowercase() == qTurn && it.canonicalFen != queryFen
        }

        val qVec = tfidfScorer.encodeVector(queryTerms, idfMap)
        val qNorm = tfidfScorer.vectorNorm(qVec)
        if (qNorm <= 0.0) return emptyList()

        val scored = ArrayList<ScoredCandidate>(universe.size)
        for (cand in universe) {
            val cId = cand.positionId
            val cVec = vectorsById[cId].orEmpty()
            val cNorm = vectorNormsById[cId] ?: 0.0

            if (cNorm > 0.0) {
                val sim = tfidfScorer.cosineSimilarity(qVec, qNorm, cVec)
                if (sim > 0.0) {
                    scored.add(ScoredCandidate(cId, cand.canonicalFen, sim))
                }
            }
        }

        scored.sortWith(
            compareByDescending<ScoredCandidate> { it.score }
                .thenBy { it.canonicalFen }
                .thenBy { it.positionId }
        )
        return scored
    }
}
