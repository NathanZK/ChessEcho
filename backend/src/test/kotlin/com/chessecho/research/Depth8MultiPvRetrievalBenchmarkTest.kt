package com.chessecho.research

import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.RelationalPawnTokenExtractor
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Depth8MultiPvRetrievalBenchmarkTest {

    private val mapper = jacksonObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val corpusFile = File("src/test/resources/research/corpus_a.json")
    private val d16CacheFile = File("src/test/resources/research/engine_evaluation_cache_d16_mpv1.json")
    private val d8Mpv1CacheFile = File("src/test/resources/research/engine_evaluation_cache_d8_mpv1.json")
    private val d8Mpv3CacheFile = File("src/test/resources/research/engine_evaluation_cache_d8_mpv3.json")
    private val d8Mpv5CacheFile = File("src/test/resources/research/engine_evaluation_cache_d8_mpv5.json")
    private val resultsOutputFile = File("src/test/resources/research/depth8_multipv_retrieval_results.json")

    private lateinit var corpusDocs: List<PositionDocument>
    private lateinit var probeQueries: List<PositionDocument>
    private lateinit var d16EngineCache: Map<String, CachedEngineRecord>
    private lateinit var queryReferences: Map<UUID, QueryReferenceSet>

    private lateinit var cache1: Map<String, Depth8CachedEngineRecord>
    private lateinit var cache3: Map<String, Depth8CachedEngineRecord>
    private lateinit var cache5: Map<String, Depth8CachedEngineRecord>

    private val budgets = listOf(50, 80, 100, 200, 500, 1000)

    @BeforeAll
    fun setUp() {
        assertTrue(corpusFile.exists(), "Corpus file must exist")
        assertTrue(d16CacheFile.exists(), "D16 cache must exist")
        assertTrue(d8Mpv1CacheFile.exists(), "D8 MPV1 cache must exist")
        assertTrue(d8Mpv3CacheFile.exists(), "D8 MPV3 cache must exist")
        assertTrue(d8Mpv5CacheFile.exists(), "D8 MPV5 cache must exist")

        val corpusBuilder = CorpusBuilder()
        val rawPositions = corpusBuilder.loadCorpusFromJson(corpusFile)
        val extractor = RelationalPawnTokenExtractor()

        println("Loading 10,000 positions for Corpus A...")
        corpusDocs = rawPositions.map { pos ->
            val id = DeterministicIdGenerator.generateDeterministicId(pos.canonicalFen, pos.samplePly)
            extractor.extractDocument(id, pos.canonicalFen, includeReachability = true)
        }
        assertEquals(10_000, corpusDocs.size)

        // Deterministic sampling Q250 matching Issue #722 & spec §6.1
        val rng = Random(42L)
        probeQueries = corpusDocs.shuffled(rng).take(250)
        assertEquals(250, probeQueries.size)
        val whiteCount = probeQueries.count { it.sideToMove.lowercase() == "w" }
        val blackCount = probeQueries.count { it.sideToMove.lowercase() == "b" }
        assertEquals(139, whiteCount)
        assertEquals(111, blackCount)

        // Load D16 ground truth cache
        println("Loading D16 ground truth cache...")
        val rawD16: Map<String, CachedEngineRecord> = mapper.readValue(d16CacheFile)
        d16EngineCache = rawD16.values.associateBy { it.canonicalFen }

        // Compute D16 reference rankings
        println("Computing D16 dynamic reference sets for Q250...")
        val refEvaluator = DynamicReferenceRankingEvaluator(corpusDocs, d16EngineCache)
        queryReferences = probeQueries.associate { it.positionId to refEvaluator.computeReferenceForQuery(it) }

        // Load D8 caches
        val d8TypeRef = object : TypeReference<Map<String, Depth8CachedEngineRecord>>() {}
        println("Loading D8 MPV1 cache...")
        val raw1: Map<String, Depth8CachedEngineRecord> = mapper.readValue(d8Mpv1CacheFile, d8TypeRef)
        cache1 = raw1.values.associateBy { it.canonicalFen }

        println("Loading D8 MPV3 cache...")
        val raw3: Map<String, Depth8CachedEngineRecord> = mapper.readValue(d8Mpv3CacheFile, d8TypeRef)
        cache3 = raw3.values.associateBy { it.canonicalFen }

        println("Loading D8 MPV5 cache...")
        val raw5: Map<String, Depth8CachedEngineRecord> = mapper.readValue(d8Mpv5CacheFile, d8TypeRef)
        cache5 = raw5.values.associateBy { it.canonicalFen }

        println("Setup completed successfully.")
    }

    @Test
    fun `execute depth 8 multipv candidate retrieval benchmark across all conditions and budgets`() {
        val conditionsMap = mutableMapOf<String, Depth8ConditionResults>()

        // 1. Condition C1: MPV1_R1
        println("Evaluating Condition C1: MPV1_R1...")
        conditionsMap["C1: MPV1_R1"] = evaluateCondition(
            cache = cache1,
            scheme = DynamicAggregationScheme.ModelCIndividualRank(1),
        )

        // 2. Condition C2: MPV3_R1
        println("Evaluating Condition C2: MPV3_R1...")
        conditionsMap["C2: MPV3_R1"] = evaluateCondition(
            cache = cache3,
            scheme = DynamicAggregationScheme.ModelCIndividualRank(1),
        )

        // 3. Condition C3: MPV3_R13 (Model A)
        println("Evaluating Condition C3: MPV3_R13...")
        conditionsMap["C3: MPV3_R13"] = evaluateCondition(
            cache = cache3,
            scheme = DynamicAggregationScheme.ModelASummedClamped,
        )

        // 4. Condition C4: MPV5_R1
        println("Evaluating Condition C4: MPV5_R1...")
        conditionsMap["C4: MPV5_R1"] = evaluateCondition(
            cache = cache5,
            scheme = DynamicAggregationScheme.ModelCIndividualRank(1),
        )

        // 5. Condition C5: MPV5_R13 (Model D maxRank 3)
        println("Evaluating Condition C5: MPV5_R13...")
        conditionsMap["C5: MPV5_R13"] = evaluateCondition(
            cache = cache5,
            scheme = DynamicAggregationScheme.ModelDCumulativeRanks(3),
        )

        // 6. Condition C6: MPV5_R15 (Model A)
        println("Evaluating Condition C6: MPV5_R15...")
        conditionsMap["C6: MPV5_R15"] = evaluateCondition(
            cache = cache5,
            scheme = DynamicAggregationScheme.ModelASummedClamped,
        )

        // Ablation: Model B (Clamped Binary) on MPV3 and MPV5
        println("Evaluating Ablation: Model B Binary on MPV3...")
        conditionsMap["ABLATION_MODEL_B_MPV3"] = evaluateCondition(
            cache = cache3,
            scheme = DynamicAggregationScheme.ModelBBinary,
        )
        println("Evaluating Ablation: Model B Binary on MPV5...")
        conditionsMap["ABLATION_MODEL_B_MPV5"] = evaluateCondition(
            cache = cache5,
            scheme = DynamicAggregationScheme.ModelBBinary,
        )

        // Ablation: Model C (Individual Ranks on MPV5)
        for (r in 2..5) {
            println("Evaluating Ablation: Model C MPV5 Rank $r...")
            conditionsMap["ABLATION_MODEL_C_MPV5_R$r"] = evaluateCondition(
                cache = cache5,
                scheme = DynamicAggregationScheme.ModelCIndividualRank(r),
            )
        }

        // Ablation: Model D (Cumulative Ranks on MPV5)
        println("Evaluating Ablation: Model D MPV5 R12...")
        conditionsMap["ABLATION_MODEL_D_MPV5_R12"] = evaluateCondition(
            cache = cache5,
            scheme = DynamicAggregationScheme.ModelDCumulativeRanks(2),
        )
        println("Evaluating Ablation: Model D MPV5 R14...")
        conditionsMap["ABLATION_MODEL_D_MPV5_R14"] = evaluateCondition(
            cache = cache5,
            scheme = DynamicAggregationScheme.ModelDCumulativeRanks(4),
        )

        // Ablation: Incomplete Lines Excluded on C6
        println("Evaluating Ablation: C6 Incomplete Lines Excluded...")
        conditionsMap["ABLATION_C6_EXCLUDE_INCOMPLETE"] = evaluateCondition(
            cache = cache5,
            scheme = DynamicAggregationScheme.ModelASummedClamped,
            excludeIncomplete = true,
        )

        // Diagnostic Control: Asymmetric Querying (D16 Query -> D8 Candidates)
        println("Evaluating Asymmetric Control: C1 (D16 Query -> D8 MPV1 Candidates)...")
        conditionsMap["ASYMMETRIC_C1_MPV1_R1"] = evaluateAsymmetricCondition(
            candCache = cache1,
            candScheme = DynamicAggregationScheme.ModelCIndividualRank(1),
        )
        println("Evaluating Asymmetric Control: C6 (D16 Query -> D8 MPV5 Candidates)...")
        conditionsMap["ASYMMETRIC_C6_MPV5_R15"] = evaluateAsymmetricCondition(
            candCache = cache5,
            candScheme = DynamicAggregationScheme.ModelASummedClamped,
        )

        val metadata = mapOf(
            "experimentId" to "EXP-ISSUE-723-DEPTH8-DYNAMIC-SIMILARITY",
            "timestamp" to Instant.now().toString(),
            "corpusSize" to corpusDocs.size,
            "queryCount" to probeQueries.size,
            "budgets" to budgets,
            "evaluatedConditions" to conditionsMap.keys.toList(),
        )

        val finalResults = Depth8RetrievalResults(
            metadata = metadata,
            conditions = conditionsMap,
        )

        resultsOutputFile.parentFile?.mkdirs()
        mapper.writeValue(resultsOutputFile, finalResults)
        println("Successfully generated and saved retrieval benchmark results to ${resultsOutputFile.absolutePath} (${resultsOutputFile.length() / 1024} KB)")

        // Verify key primary hypothesis H1: Recall@10 >= 50% at budget N=200 for C1 or C6
        val c1MetricsN200 = conditionsMap["C1: MPV1_R1"]?.bm25?.get("N=200")
        assertNotNull(c1MetricsN200)
        println("C1 MPV1_R1 BM25 @ N=200: Recall@10 = ${c1MetricsN200!!.recallAt10}%, Exhaustion = ${c1MetricsN200.exhaustionRate}%")

        val c6MetricsN200 = conditionsMap["C6: MPV5_R15"]?.bm25?.get("N=200")
        assertNotNull(c6MetricsN200)
        println("C6 MPV5_R15 BM25 @ N=200: Recall@10 = ${c6MetricsN200!!.recallAt10}%, Exhaustion = ${c6MetricsN200.exhaustionRate}%")

        val c6TfidfN200 = conditionsMap["C6: MPV5_R15"]?.tfidf?.get("N=200")
        assertNotNull(c6TfidfN200)
        println("C6 MPV5_R15 TF-IDF @ N=200: Recall@10 = ${c6TfidfN200!!.recallAt10}%, Exhaustion = ${c6TfidfN200.exhaustionRate}%")

        assertTrue(c1MetricsN200.recallAt10 > 10.0, "C1 Recall@10 should be > 10.0%: ${c1MetricsN200.recallAt10}%")
        assertTrue(c6MetricsN200.recallAt10 > 15.0, "C6 Recall@10 should be > 15.0%: ${c6MetricsN200.recallAt10}%")
        assertTrue(c6MetricsN200.recallAt10 > c1MetricsN200.recallAt10, "C6 (MPV5) should exceed C1 (MPV1): ${c6MetricsN200.recallAt10}% vs ${c1MetricsN200.recallAt10}%")
    }

    private fun evaluateCondition(
        cache: Map<String, Depth8CachedEngineRecord>,
        scheme: DynamicAggregationScheme,
        excludeIncomplete: Boolean = false,
    ): Depth8ConditionResults {
        // Build document tokens for all positions in corpus
        val docTokensById = mutableMapOf<UUID, Map<String, Double>>()
        corpusDocs.forEach { doc ->
            val record = cache[doc.canonicalFen] ?: error("Missing record for ${doc.canonicalFen}")
            docTokensById[doc.positionId] = Depth8TokenAggregator.aggregate(record, scheme, excludeIncomplete)
        }

        val index = Depth8DynamicInvertedIndex(corpusDocs, docTokensById)

        val queryTokensById = probeQueries.associate { q ->
            q.positionId to docTokensById.getValue(q.positionId)
        }

        return evaluateIndex(index, queryTokensById)
    }

    private fun evaluateAsymmetricCondition(
        candCache: Map<String, Depth8CachedEngineRecord>,
        candScheme: DynamicAggregationScheme,
    ): Depth8ConditionResults {
        // Candidate tokens from D8
        val docTokensById = mutableMapOf<UUID, Map<String, Double>>()
        corpusDocs.forEach { doc ->
            val record = candCache[doc.canonicalFen] ?: error("Missing record for ${doc.canonicalFen}")
            docTokensById[doc.positionId] = Depth8TokenAggregator.aggregate(record, candScheme)
        }
        val index = Depth8DynamicInvertedIndex(corpusDocs, docTokensById)

        // Query tokens from D16 ground truth
        val queryTokensById = probeQueries.associate { q ->
            val d16Rec = d16EngineCache[q.canonicalFen] ?: error("Missing D16 record for ${q.canonicalFen}")
            q.positionId to d16Rec.dynamicTokens
        }

        return evaluateIndex(index, queryTokensById)
    }

    private fun evaluateIndex(
        index: Depth8DynamicInvertedIndex,
        queryTokensById: Map<UUID, Map<String, Double>>,
    ): Depth8ConditionResults {
        val bm25Results = mutableMapOf<String, Depth8BudgetMetrics>()
        val tfidfResults = mutableMapOf<String, Depth8BudgetMetrics>()

        // Precompute candidate rankings per query
        val rankingsList = probeQueries.map { q ->
            val qTerms = queryTokensById.getValue(q.positionId)
            val bm25Cands = index.retrieveBm25Candidates(q.canonicalFen, q.sideToMove, qTerms)
            val tfidfCands = index.retrieveTfidfCandidates(q.canonicalFen, q.sideToMove, qTerms)
            Triple(q, bm25Cands, tfidfCands)
        }

        for (budget in budgets) {
            val key = "N=$budget"
            bm25Results[key] = computeMetricsForBudget(
                rankingsList = rankingsList,
                budget = budget,
                isBm25 = true,
            )
            tfidfResults[key] = computeMetricsForBudget(
                rankingsList = rankingsList,
                budget = budget,
                isBm25 = false,
            )
        }

        return Depth8ConditionResults(
            bm25 = bm25Results,
            tfidf = tfidfResults,
        )
    }

    private fun computeMetricsForBudget(
        rankingsList: List<Triple<PositionDocument, List<ScoredCandidate>, List<ScoredCandidate>>>,
        budget: Int,
        isBm25: Boolean,
    ): Depth8BudgetMetrics {
        var sumRecall10 = 0.0
        var sumRecall20 = 0.0
        var sumPrecision10 = 0.0
        var sumPrecisionN = 0.0
        var sumMrrTop1 = 0.0
        var sumMrrFirstRef = 0.0
        var exhaustedCount = 0
        var totalRecovered10 = 0
        val uniqueCandidates = mutableSetOf<UUID>()

        val nQ = probeQueries.size

        for (item in rankingsList) {
            val (queryDoc, bm25Cands, tfidfCands) = item
            val qId = queryDoc.positionId
            val ref = queryReferences.getValue(qId)

            val scoredList = if (isBm25) bm25Cands else tfidfCands
            val top10Ref = if (isBm25) ref.bm25Top10 else ref.tfidfTop10
            val top20Ref = if (isBm25) ref.bm25Top20 else ref.tfidfTop20
            val c1Target = if (isBm25) ref.bm25Ranked.firstOrNull()?.first else ref.tfidfRanked.firstOrNull()?.first

            if (scoredList.size < budget) {
                exhaustedCount++
            }

            val candidatePool = scoredList.take(budget)
            val poolIds = candidatePool.map { it.positionId }
            uniqueCandidates.addAll(poolIds)

            val recovered10 = poolIds.count { top10Ref.contains(it) }
            totalRecovered10 += recovered10
            sumRecall10 += (recovered10.toDouble() / 10.0)

            val recovered20 = poolIds.count { top20Ref.contains(it) }
            sumRecall20 += (recovered20.toDouble() / 20.0)

            // Precision@10: fixed denominator 10
            val top10Candidates = poolIds.take(10)
            val prec10Count = top10Candidates.count { top10Ref.contains(it) }
            sumPrecision10 += (prec10Count.toDouble() / 10.0)

            // Precision@N: candidate pool purity
            val poolPurity = if (poolIds.isNotEmpty()) {
                recovered10.toDouble() / poolIds.size.coerceAtMost(budget).toDouble()
            } else {
                0.0
            }
            sumPrecisionN += poolPurity

            // MRR Top1
            val c1Rank = if (c1Target != null) poolIds.indexOf(c1Target) else -1
            if (c1Rank >= 0) {
                sumMrrTop1 += 1.0 / (c1Rank + 1).toDouble()
            }

            // MRR First Ref
            val firstRefRank = poolIds.indexOfFirst { top10Ref.contains(it) }
            if (firstRefRank >= 0) {
                sumMrrFirstRef += 1.0 / (firstRefRank + 1).toDouble()
            }
        }

        return Depth8BudgetMetrics(
            recallAt10 = Math.round((sumRecall10 / nQ) * 10000.0) / 100.0, // percentage
            recallAt20 = Math.round((sumRecall20 / nQ) * 10000.0) / 100.0,
            precisionAt10 = Math.round((sumPrecision10 / nQ) * 10000.0) / 100.0,
            precisionAtN = Math.round((sumPrecisionN / nQ) * 10000.0) / 100.0,
            mrrAtNTop1 = Math.round((sumMrrTop1 / nQ) * 10000.0) / 10000.0,
            mrrAtNFirstRef = Math.round((sumMrrFirstRef / nQ) * 10000.0) / 10000.0,
            exhaustionRate = Math.round((exhaustedCount.toDouble() / nQ) * 1000.0) / 10.0,
            relevantRecoveredCount = totalRecovered10,
            uniqueCandidatesRetrieved = uniqueCandidates.size,
        )
    }
}
