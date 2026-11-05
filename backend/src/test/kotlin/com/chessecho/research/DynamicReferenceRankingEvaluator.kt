package com.chessecho.research

import com.chessecho.similarity.DynamicTacticalScorer
import com.chessecho.similarity.PositionDocument
import java.util.UUID

data class QueryReferenceSet(
    val queryId: UUID,
    val queryFen: String,
    val sideToMove: String,
    val universeSize: Int,
    val bm25Ranked: List<Pair<UUID, Double>>,
    val tfidfRanked: List<Pair<UUID, Double>>,
    val bm25Top10: Set<UUID>,
    val bm25Top20: Set<UUID>,
    val tfidfTop10: Set<UUID>,
    val tfidfTop20: Set<UUID>,
    val bm25PositiveCount: Int,
    val tfidfPositiveCount: Int,
)

class DynamicReferenceRankingEvaluator(
    private val corpusDocs: List<PositionDocument>,
    cacheRecordsInput: Map<String, CachedEngineRecord>,
) {
    private val tfidfScorer = DynamicTfidfScorer()

    val recordsByFen: Map<String, CachedEngineRecord> =
        if (corpusDocs.isNotEmpty() && cacheRecordsInput.containsKey(corpusDocs.first().canonicalFen)) {
            cacheRecordsInput
        } else {
            cacheRecordsInput.values.associateBy { it.canonicalFen }
        }

    val corpusTermsById: Map<UUID, Map<String, Double>>
    val docLengths: Map<UUID, Double>
    val docFreqs: Map<String, Int>
    val totalDocs: Int
    val avgDocLen: Double
    val idfMap: Map<String, Double>
    val corpusVectorsById: Map<UUID, Map<String, Double>>
    val corpusVectorNormsById: Map<UUID, Double>

    init {
        val termsMap = mutableMapOf<UUID, Map<String, Double>>()
        val lengthsMap = mutableMapOf<UUID, Double>()
        val freqs = mutableMapOf<String, Int>()

        corpusDocs.forEach { doc ->
            val rec = recordsByFen[doc.canonicalFen]
                ?: error("Missing engine evaluation cache for FEN: ${doc.canonicalFen}")
            val tokens = rec.dynamicTokens
            termsMap[doc.positionId] = tokens
            lengthsMap[doc.positionId] = tokens.values.sum()
            tokens.filterValues { it > 0.0 }.keys.forEach { term ->
                freqs.merge(term, 1, Int::plus)
            }
        }

        corpusTermsById = termsMap
        docLengths = lengthsMap
        docFreqs = freqs
        totalDocs = corpusDocs.size
        avgDocLen = if (totalDocs > 0) docLengths.values.sum() / totalDocs else 0.0
        idfMap = tfidfScorer.computeIdf(totalDocs, docFreqs)

        val vectors = mutableMapOf<UUID, Map<String, Double>>()
        val norms = mutableMapOf<UUID, Double>()
        corpusDocs.forEach { doc ->
            val terms = corpusTermsById.getValue(doc.positionId)
            val vec = tfidfScorer.encodeVector(terms, idfMap)
            vectors[doc.positionId] = vec
            norms[doc.positionId] = tfidfScorer.vectorNorm(vec)
        }
        corpusVectorsById = vectors
        corpusVectorNormsById = norms
    }

    fun computeReferenceForQuery(queryDoc: PositionDocument): QueryReferenceSet {
        val qId = queryDoc.positionId
        val qTurn = queryDoc.sideToMove.lowercase()
        val qTerms = corpusTermsById.getValue(qId)
        val qTermSet = qTerms.filterValues { it > 0.0 }.keys
        val qVec = corpusVectorsById.getValue(qId)
        val qNorm = corpusVectorNormsById.getValue(qId)

        // Same-side eligible candidate universe U(q)
        val universe = corpusDocs.filter { it.positionId != qId && it.sideToMove.lowercase() == qTurn }

        // BM25 Ranking with deterministic tie-breaking (score DESC, canonicalFen ASC, positionId ASC)
        val bm25Scored = universe.map { cand ->
            val cId = cand.positionId
            val cTerms = corpusTermsById.getValue(cId)
            val len = docLengths.getValue(cId)
            val score = if (qTermSet.isNotEmpty()) {
                DynamicTacticalScorer.bm25Score(
                    queryTerms = qTermSet,
                    documentTerms = cTerms,
                    documentFrequencies = docFreqs,
                    documentCount = totalDocs,
                    documentLength = len,
                    averageDocumentLength = avgDocLen,
                )
            } else {
                0.0
            }
            Triple(cId, cand.canonicalFen, score)
        }

        val bm25Sorted = bm25Scored.sortedWith(
            compareByDescending<Triple<UUID, String, Double>> { it.third }
                .thenBy { it.second }
                .thenBy { it.first }
        )

        // TF-IDF Cosine Ranking with deterministic tie-breaking (sim DESC, canonicalFen ASC, positionId ASC)
        val tfidfScored = universe.map { cand ->
            val cId = cand.positionId
            val cVec = corpusVectorsById.getValue(cId)
            val sim = if (qNorm > 0.0) tfidfScorer.cosineSimilarity(qVec, qNorm, cVec) else 0.0
            Triple(cId, cand.canonicalFen, sim)
        }

        val tfidfSorted = tfidfScored.sortedWith(
            compareByDescending<Triple<UUID, String, Double>> { it.third }
                .thenBy { it.second }
                .thenBy { it.first }
        )

        val bm25RankedPairs = bm25Sorted.map { it.first to it.third }
        val tfidfRankedPairs = tfidfSorted.map { it.first to it.third }

        val bm25Positive = bm25RankedPairs.count { it.second > 0.0 }
        val tfidfPositive = tfidfRankedPairs.count { it.second > 0.0 }

        return QueryReferenceSet(
            queryId = qId,
            queryFen = queryDoc.canonicalFen,
            sideToMove = qTurn,
            universeSize = universe.size,
            bm25Ranked = bm25RankedPairs,
            tfidfRanked = tfidfRankedPairs,
            bm25Top10 = bm25RankedPairs.take(10).map { it.first }.toSet(),
            bm25Top20 = bm25RankedPairs.take(20).map { it.first }.toSet(),
            tfidfTop10 = tfidfRankedPairs.take(10).map { it.first }.toSet(),
            tfidfTop20 = tfidfRankedPairs.take(20).map { it.first }.toSet(),
            bm25PositiveCount = bm25Positive,
            tfidfPositiveCount = tfidfPositive,
        )
    }

    fun buildAuditReport(references: List<QueryReferenceSet>): DynamicReferenceAuditReport {
        require(references.isNotEmpty()) { "References list must not be empty" }
        val nQ = references.size

        // BM25 stats
        val bm25PosList = references.map { it.bm25PositiveCount }
        val bm25ZeroIn10 = references.count { ref ->
            ref.bm25Ranked.take(10).any { it.second <= 0.0 }
        }
        val bm25ZeroIn20 = references.count { ref ->
            ref.bm25Ranked.take(20).any { it.second <= 0.0 }
        }
        val bm25Top10Scores = references.map { it.bm25Ranked.getOrNull(9)?.second ?: 0.0 }.sorted()
        val bm25Top20Scores = references.map { it.bm25Ranked.getOrNull(19)?.second ?: 0.0 }.sorted()

        val bm25Audit = ScorerAuditSummary(
            meanPositiveScoreCandidates = bm25PosList.average(),
            minPositiveScoreCandidates = bm25PosList.minOrNull() ?: 0,
            medianPositiveScoreCandidates = bm25PosList.sorted()[nQ / 2],
            maxPositiveScoreCandidates = bm25PosList.maxOrNull() ?: 0,
            zeroScoreInTop10Count = bm25ZeroIn10,
            zeroScoreInTop20Count = bm25ZeroIn20,
            minTop10Score = bm25Top10Scores.first(),
            medianTop10Score = bm25Top10Scores[nQ / 2],
            minTop20Score = bm25Top20Scores.first(),
            medianTop20Score = bm25Top20Scores[nQ / 2],
        )

        // TF-IDF stats
        val tfidfPosList = references.map { it.tfidfPositiveCount }
        val tfidfZeroIn10 = references.count { ref ->
            ref.tfidfRanked.take(10).any { it.second <= 0.0 }
        }
        val tfidfZeroIn20 = references.count { ref ->
            ref.tfidfRanked.take(20).any { it.second <= 0.0 }
        }
        val tfidfTop10Scores = references.map { it.tfidfRanked.getOrNull(9)?.second ?: 0.0 }.sorted()
        val tfidfTop20Scores = references.map { it.tfidfRanked.getOrNull(19)?.second ?: 0.0 }.sorted()

        val tfidfAudit = ScorerAuditSummary(
            meanPositiveScoreCandidates = tfidfPosList.average(),
            minPositiveScoreCandidates = tfidfPosList.minOrNull() ?: 0,
            medianPositiveScoreCandidates = tfidfPosList.sorted()[nQ / 2],
            maxPositiveScoreCandidates = tfidfPosList.maxOrNull() ?: 0,
            zeroScoreInTop10Count = tfidfZeroIn10,
            zeroScoreInTop20Count = tfidfZeroIn20,
            minTop10Score = tfidfTop10Scores.first(),
            medianTop10Score = tfidfTop10Scores[nQ / 2],
            minTop20Score = tfidfTop20Scores.first(),
            medianTop20Score = tfidfTop20Scores[nQ / 2],
        )

        return DynamicReferenceAuditReport(
            totalProbeQueries = nQ,
            bm25Audit = bm25Audit,
            tfidfAudit = tfidfAudit,
        )
    }
}
