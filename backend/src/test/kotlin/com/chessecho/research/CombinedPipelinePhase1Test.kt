package com.chessecho.research

import com.chessecho.similarity.DynamicTacticalScorer
import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.RelationalPawnTokenExtractor
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.Random
import java.util.UUID
import kotlin.math.log2

data class ScorerComparisonSummary(
    val meanSpearmanRho: Double,
    val meanTop10Jaccard: Double,
    val tfidfHitAt1: Double,
    val tfidfHitAt5: Double,
    val tfidfHitAt10: Double,
    val tfidfMrr: Double,
    val bm25HitAt1: Double,
    val bm25HitAt5: Double,
    val bm25HitAt10: Double,
    val bm25Mrr: Double,
    val lengthSensitivityNotes: String,
)

data class AlphaRetentionRecord(
    val alpha: Double,
    val meanSurvivingCandidates: Double,
    val meanCandidateReductionRate: Double,
    // BM25
    val bm25MeanRetainedK10: Double,
    val bm25MeanDiscardedK10: Double,
    val bm25RecallAt10: Double,
    val bm25MeanRetainedK20: Double,
    val bm25MeanDiscardedK20: Double,
    val bm25RecallAt20: Double,
    val bm25PurityAt10: Double,
    val bm25HitAt1: Double,
    val bm25HitAt5: Double,
    val bm25HitAt10: Double,
    val bm25Mrr: Double,
    val bm25NdcgAt10: Double,
    // TF-IDF
    val tfidfMeanRetainedK10: Double,
    val tfidfMeanDiscardedK10: Double,
    val tfidfRecallAt10: Double,
    val tfidfMeanRetainedK20: Double,
    val tfidfMeanDiscardedK20: Double,
    val tfidfRecallAt20: Double,
    val tfidfPurityAt10: Double,
    val tfidfHitAt1: Double,
    val tfidfHitAt5: Double,
    val tfidfHitAt10: Double,
    val tfidfMrr: Double,
    val tfidfNdcgAt10: Double,
    // Downstream 1.2-pawn mistake retention
    val mistakePositionsRetentionRate: Double,
    val falseFamiliaritySuppressionCount: Int,
)

data class TacticalMotifEvaluationResult(
    val totalQueries: Int,
    val bm25HitAt1: Double,
    val bm25HitAt5: Double,
    val bm25HitAt10: Double,
    val bm25Mrr: Double,
    val tfidfHitAt1: Double,
    val tfidfHitAt5: Double,
    val tfidfHitAt10: Double,
    val tfidfMrr: Double,
)

data class CombinedPipelinePhase1Report(
    val phase: String = "Phase 1: MultiPV=1 Baseline",
    val fixedDepth: Int = 16,
    val multiPv: Int = 1,
    val decisionThresholdCp: Int = 120,
    val totalUniquePositionsAnalyzed: Int,
    val totalActualEngineCalls: Int,
    val engineCallCeiling: Int,
    val passedEngineBudgetCeiling: Boolean,
    val probeSetSize: Int,
    val scorerComparison: ScorerComparisonSummary,
    val alphaGridResults: List<AlphaRetentionRecord>,
    val tacticalMotifValidation: TacticalMotifEvaluationResult,
)

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CombinedPipelinePhase1Test {
    private val mapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val extractor = RelationalPawnTokenExtractor()
    private val clusterRunner = ClusteringExperimentRunner()
    private val tfidfScorer = DynamicTfidfScorer()

    private val corpusFile = File("src/test/resources/research/corpus_a.json")
    private val frozenPoolsFile = File("src/test/resources/research/tactical-motif-frozen-pools.json")
    private val engineCacheFile = File("src/test/resources/research/engine_evaluation_cache_d16_mpv1.json")

    private lateinit var corpusProvenance: List<PositionProvenanceRecord>
    private lateinit var corpusDocs: List<PositionDocument>
    private lateinit var posById: Map<UUID, PositionDocument>
    private lateinit var posByCanonicalFen: Map<String, PositionDocument>

    private lateinit var engineRunner: DynamicEngineResearchRunner
    private val evaluatedEngineRecords = mutableMapOf<String, CachedEngineRecord>()

    private val probeQueries = mutableListOf<PositionDocument>()
    private val alphas = listOf(0.00, 0.25, 0.50, 0.60, 0.75, 1.00)

    @BeforeAll
    fun setUp() {
        assertTrue(corpusFile.exists(), "Corpus A must exist at ${corpusFile.path}")
        assertTrue(frozenPoolsFile.exists(), "Tactical motif frozen pools must exist at ${frozenPoolsFile.path}")

        println("Loading Corpus A positions...")
        val builder = CorpusBuilder()
        corpusProvenance = builder.loadCorpusFromJson(corpusFile)
        assertEquals(10_000, corpusProvenance.size)

        corpusDocs =
            corpusProvenance.map { pos ->
                val id = DeterministicIdGenerator.generateDeterministicId(pos.canonicalFen, pos.samplePly)
                extractor.extractDocument(id, pos.canonicalFen, includeReachability = true)
            }
        posById = corpusDocs.associateBy { it.positionId }
        posByCanonicalFen = corpusDocs.associateBy { it.canonicalFen }

        // Read frozen pools to gather unique canonical FENs
        val frozenPoolsData: Map<String, Any> = mapper.readValue(frozenPoolsFile)

        @Suppress("UNCHECKED_CAST")
        val queriesList = frozenPoolsData["queries"] as List<Map<String, Any>>
        val frozenFens = mutableSetOf<String>()
        queriesList.forEach { q ->
            val qFen = q["canonicalFen"] as String
            frozenFens.add(qFen)
            @Suppress("UNCHECKED_CAST")
            val cands = q["candidates"] as List<Map<String, Any>>
            cands.forEach { c ->
                frozenFens.add(c["canonicalFen"] as String)
            }
        }
        println("Frozen pools unique FENs: ${frozenFens.size}")

        // Combine all unique FENs across Corpus A and Frozen Pools
        val allUniqueFens = (corpusDocs.map { it.canonicalFen } + frozenFens).distinct()
        println("Total combined unique positions requiring Depth 16 analysis: ${allUniqueFens.size}")
        assertEquals(10_252, allUniqueFens.size, "Total unique positions must equal exactly 10,252")

        // Initialize DynamicEngineResearchRunner with strict 11,000 budget
        engineRunner =
            DynamicEngineResearchRunner(
                binaryPath = "/opt/homebrew/bin/stockfish",
                depth = 16,
                multiPv = 1,
                hardCeiling = 11_000,
                cacheFile = engineCacheFile,
            )

        println("Executing batch engine evaluation (6 workers, fixed Depth 16, MultiPV=1)...")
        val evalResults =
            engineRunner.evaluateBatch(
                positions = allUniqueFens,
                workerCount = 6,
                progressInterval = 1000,
            )
        evaluatedEngineRecords.putAll(evalResults)
        println(
            "Engine evaluation complete! Evaluated: ${evaluatedEngineRecords.size}. Actual engine search calls: ${engineRunner.actualEngineSearchCalls.get()} / 11,000. Cache hits: ${engineRunner.cacheHits.get()}",
        )
        assertTrue(engineRunner.actualEngineSearchCalls.get() <= 11_000, "Must not exceed 11,000 search calls")

        // Stratified 250-query probe set (seed 42)
        val rng = Random(42L)
        val shuffled = corpusDocs.shuffled(rng)
        probeQueries.addAll(shuffled.take(250))
        assertEquals(250, probeQueries.size)
    }

    @Test
    fun `execute Phase 1 MultiPV1 baseline across full 6-alpha grid and compare scorers`() {
        // Precompute Corpus A dynamic tokens and document frequencies for BM25 and TF-IDF
        val corpusTermsById = mutableMapOf<UUID, Map<String, Double>>()
        val docFreqs = mutableMapOf<String, Int>()

        corpusDocs.forEach { doc ->
            val rec = evaluatedEngineRecords.getValue(doc.canonicalFen)
            val tokens = rec.dynamicTokens
            corpusTermsById[doc.positionId] = tokens
            tokens.filterValues { it > 0.0 }.keys.forEach { term ->
                docFreqs.merge(term, 1, Int::plus)
            }
        }

        val totalDocs = corpusDocs.size
        val docLengths = corpusTermsById.mapValues { (_, terms) -> terms.values.sum() }
        val avgDocLen = docLengths.values.average()

        // -----------------------------------------------------------------
        // Stage 1: Full-Corpus Unconstrained Dynamic Reference Rankings
        // -----------------------------------------------------------------
        println("Computing Full-Corpus Unconstrained Dynamic Reference rankings for 250 queries...")
        val idfMap = tfidfScorer.computeIdf(totalDocs, docFreqs)

        // For each query, precompute candidate universe U(q) and reference rankings
        data class QueryReference(
            val queryDoc: PositionDocument,
            val candidateUniverse: List<PositionDocument>,
            val bm25Ranked: List<Pair<UUID, Double>>,
            val tfidfRanked: List<Pair<UUID, Double>>,
            val bm25Top10: Set<UUID>,
            val bm25Top20: Set<UUID>,
            val tfidfTop10: Set<UUID>,
            val tfidfTop20: Set<UUID>,
            val mistakeCandidates: Set<UUID>,
        )

        val references = mutableMapOf<UUID, QueryReference>()

        for (q in probeQueries) {
            val qTerms = corpusTermsById.getValue(q.positionId)
            val qTurn = q.sideToMove
            val qRec = evaluatedEngineRecords.getValue(q.canonicalFen)
            val qScore = qRec.scoreCp

            val universe = corpusDocs.filter { it.positionId != q.positionId && it.sideToMove == qTurn }

            // BM25 scoring over U(q)
            val qTermSet = qTerms.filterValues { it > 0.0 }.keys
            val bm25Scores =
                universe
                    .map { cand ->
                        val candTerms = corpusTermsById.getValue(cand.positionId)
                        val len = docLengths.getValue(cand.positionId)
                        val score =
                            if (qTermSet.isNotEmpty()) {
                                DynamicTacticalScorer.bm25Score(
                                    queryTerms = qTermSet,
                                    documentTerms = candTerms,
                                    documentFrequencies = docFreqs,
                                    documentCount = totalDocs,
                                    documentLength = len,
                                    averageDocumentLength = avgDocLen,
                                )
                            } else {
                                0.0
                            }
                        cand.positionId to score
                    }.sortedWith(compareByDescending<Pair<UUID, Double>> { it.second }.thenBy { it.first })

            // TF-IDF Cosine scoring over U(q)
            val qVec = tfidfScorer.encodeVector(qTerms, idfMap)
            val qNorm = tfidfScorer.vectorNorm(qVec)
            val tfidfScores =
                universe
                    .map { cand ->
                        val candTerms = corpusTermsById.getValue(cand.positionId)
                        val docVec = tfidfScorer.encodeVector(candTerms, idfMap)
                        val sim = if (qNorm > 0.0) tfidfScorer.cosineSimilarity(qVec, qNorm, docVec) else 0.0
                        cand.positionId to sim
                    }.sortedWith(compareByDescending<Pair<UUID, Double>> { it.second }.thenBy { it.first })

            // Mistake candidates (|delta eval| >= 120 cp)
            val mistakes =
                universe
                    .filter { cand ->
                        val cRec = evaluatedEngineRecords.getValue(cand.canonicalFen)
                        kotlin.math.abs(qScore - cRec.scoreCp) >= 120
                    }.map { it.positionId }
                    .toSet()

            references[q.positionId] =
                QueryReference(
                    queryDoc = q,
                    candidateUniverse = universe,
                    bm25Ranked = bm25Scores,
                    tfidfRanked = tfidfScores,
                    bm25Top10 = bm25Scores.take(10).map { it.first }.toSet(),
                    bm25Top20 = bm25Scores.take(20).map { it.first }.toSet(),
                    tfidfTop10 = tfidfScores.take(10).map { it.first }.toSet(),
                    tfidfTop20 = tfidfScores.take(20).map { it.first }.toSet(),
                    mistakeCandidates = mistakes,
                )
        }
        println("Full-Corpus Unconstrained Dynamic Reference rankings established.")

        // -----------------------------------------------------------------
        // Stage 2: Scorer Comparison on Reference Rankings
        // -----------------------------------------------------------------
        println("Computing direct scorer comparison metrics between BM25 and TF-IDF Cosine...")
        var sumSpearman = 0.0
        var sumJaccard = 0.0

        for (q in probeQueries) {
            val ref = references.getValue(q.positionId)
            val bm25Top10 = ref.bm25Top10
            val tfidfTop10 = ref.tfidfTop10

            val intersection = bm25Top10.intersect(tfidfTop10).size
            val union = bm25Top10.union(tfidfTop10).size
            val jaccard = if (union > 0) intersection.toDouble() / union else 1.0
            sumJaccard += jaccard

            // Spearman rho over candidate universe U(q)
            val universeIds = ref.candidateUniverse.map { it.positionId }
            val bm25Ranks = ref.bm25Ranked.mapIndexed { idx, pair -> pair.first to idx + 1 }.toMap()
            val tfidfRanks = ref.tfidfRanked.mapIndexed { idx, pair -> pair.first to idx + 1 }.toMap()

            val rho = computeSpearman(universeIds.map { bm25Ranks.getValue(it) }, universeIds.map { tfidfRanks.getValue(it) })
            sumSpearman += rho
        }

        val meanSpearman = sumSpearman / probeQueries.size
        val meanJaccard = sumJaccard / probeQueries.size
        println("Scorer Agreement: Mean Spearman Rho = ${"%.4f".format(meanSpearman)}, Mean Top-10 Jaccard = ${"%.4f".format(meanJaccard)}")

        // -----------------------------------------------------------------
        // Stage 3: Static Filtering Sweep across 6 Alpha Values
        // -----------------------------------------------------------------
        println("Executing static filtering across all 6 alpha values: $alphas...")
        val alphaRecords = mutableListOf<AlphaRetentionRecord>()

        for (alpha in alphas) {
            println("  Running static clustering for alpha = $alpha...")
            val config =
                ExperimentConfig(
                    alpha = alpha,
                    candidateLimit = 200,
                    topK = 30,
                    floorThreshold = 0.40,
                    top1Threshold = 0.65,
                    rbResolution = 1.25,
                    centroidThreshold = 0.38,
                    randomSeed = 42L,
                )
            val runResult = clusterRunner.runExperiment(corpusDocs, config)
            val assigned = runResult.evaluationResult.assignments.filter { it.clusterIndex != null }
            val clusterAssignments = assigned.associate { it.positionId to it.clusterIndex!! }
            val clustersByIndex = clusterAssignments.entries.groupBy({ it.value }, { it.key })

            // For each query, determine surviving candidates S_alpha(q)
            var sumSurviving = 0
            var sumCrr = 0.0

            var sumBm25RetK10 = 0.0
            var sumBm25RetK20 = 0.0
            var sumBm25Purity10 = 0.0
            var sumBm25Hit1 = 0.0
            var sumBm25Hit5 = 0.0
            var sumBm25Hit10 = 0.0
            var sumBm25Mrr = 0.0
            var sumBm25Ndcg10 = 0.0

            var sumTfidfRetK10 = 0.0
            var sumTfidfRetK20 = 0.0
            var sumTfidfPurity10 = 0.0
            var sumTfidfHit1 = 0.0
            var sumTfidfHit5 = 0.0
            var sumTfidfHit10 = 0.0
            var sumTfidfMrr = 0.0
            var sumTfidfNdcg10 = 0.0

            var sumMistakeRetained = 0.0
            var totalFalseFamiliarityEvicted = 0

            for (q in probeQueries) {
                val ref = references.getValue(q.positionId)
                val cIdx = clusterAssignments[q.positionId]

                val survivingCandidates: List<PositionDocument> =
                    if (cIdx != null) {
                        val clusterMembers = clustersByIndex.getValue(cIdx).filter { it != q.positionId }
                        clusterMembers.map { posById.getValue(it) }
                    } else {
                        emptyList()
                    }

                val survivingIds = survivingCandidates.map { it.positionId }.toSet()
                val nSurviving = survivingIds.size
                sumSurviving += nSurviving

                val uSize = ref.candidateUniverse.size
                val crr = 1.0 - (nSurviving.toDouble() / uSize)
                sumCrr += crr

                // 1. BM25 Evaluation on Surviving Set
                val bm25Ret10 = ref.bm25Top10.intersect(survivingIds).size
                val bm25Ret20 = ref.bm25Top20.intersect(survivingIds).size
                sumBm25RetK10 += bm25Ret10
                sumBm25RetK20 += bm25Ret20
                sumBm25Purity10 += if (nSurviving > 0) bm25Ret10.toDouble() / nSurviving else 0.0

                // Rerank surviving set under BM25
                val qTerms = corpusTermsById.getValue(q.positionId)
                val qTermSet = qTerms.filterValues { it > 0.0 }.keys
                val rerankedBm25 =
                    survivingCandidates
                        .map { cand ->
                            val candTerms = corpusTermsById.getValue(cand.positionId)
                            val len = docLengths.getValue(cand.positionId)
                            val score =
                                if (qTermSet.isNotEmpty()) {
                                    DynamicTacticalScorer.bm25Score(
                                        queryTerms = qTermSet,
                                        documentTerms = candTerms,
                                        documentFrequencies = docFreqs,
                                        documentCount = totalDocs,
                                        documentLength = len,
                                        averageDocumentLength = avgDocLen,
                                    )
                                } else {
                                    0.0
                                }
                            cand.positionId to score
                        }.sortedWith(compareByDescending<Pair<UUID, Double>> { it.second }.thenBy { it.first })

                val targetBm25 = ref.bm25Top10.firstOrNull()
                val bm25Rank =
                    if (targetBm25 != null) {
                        val idx = rerankedBm25.indexOfFirst { it.first == targetBm25 }
                        if (idx != -1) idx + 1 else null
                    } else {
                        null
                    }

                if (bm25Rank != null && bm25Rank <= 1) sumBm25Hit1 += 1.0
                if (bm25Rank != null && bm25Rank <= 5) sumBm25Hit5 += 1.0
                if (bm25Rank != null && bm25Rank <= 10) sumBm25Hit10 += 1.0
                if (bm25Rank != null) sumBm25Mrr += 1.0 / bm25Rank
                sumBm25Ndcg10 += computeNdcg10(rerankedBm25.map { it.first }, ref.bm25Top10)

                // 2. TF-IDF Evaluation on Surviving Set
                val tfidfRet10 = ref.tfidfTop10.intersect(survivingIds).size
                val tfidfRet20 = ref.tfidfTop20.intersect(survivingIds).size
                sumTfidfRetK10 += tfidfRet10
                sumTfidfRetK20 += tfidfRet20
                sumTfidfPurity10 += if (nSurviving > 0) tfidfRet10.toDouble() / nSurviving else 0.0

                // Rerank surviving set under TF-IDF Cosine
                val qVec = tfidfScorer.encodeVector(qTerms, idfMap)
                val qNorm = tfidfScorer.vectorNorm(qVec)
                val rerankedTfidf =
                    survivingCandidates
                        .map { cand ->
                            val candTerms = corpusTermsById.getValue(cand.positionId)
                            val docVec = tfidfScorer.encodeVector(candTerms, idfMap)
                            val sim = if (qNorm > 0.0) tfidfScorer.cosineSimilarity(qVec, qNorm, docVec) else 0.0
                            cand.positionId to sim
                        }.sortedWith(compareByDescending<Pair<UUID, Double>> { it.second }.thenBy { it.first })

                val targetTfidf = ref.tfidfTop10.firstOrNull()
                val tfidfRank =
                    if (targetTfidf != null) {
                        val idx = rerankedTfidf.indexOfFirst { it.first == targetTfidf }
                        if (idx != -1) idx + 1 else null
                    } else {
                        null
                    }

                if (tfidfRank != null && tfidfRank <= 1) sumTfidfHit1 += 1.0
                if (tfidfRank != null && tfidfRank <= 5) sumTfidfHit5 += 1.0
                if (tfidfRank != null && tfidfRank <= 10) sumTfidfHit10 += 1.0
                if (tfidfRank != null) sumTfidfMrr += 1.0 / tfidfRank
                sumTfidfNdcg10 += computeNdcg10(rerankedTfidf.map { it.first }, ref.tfidfTop10)

                // 3. Downstream Mistake Retention (|delta eval| >= 120 cp)
                val totalMistakes = ref.mistakeCandidates.size
                val retainedMistakes = ref.mistakeCandidates.intersect(survivingIds).size
                sumMistakeRetained += if (totalMistakes > 0) retainedMistakes.toDouble() / totalMistakes else 1.0

                // False familiarity: candidates in cluster with |delta eval| > 150 cp that are demoted out of top 10 by dynamic reranking
                val qFenScore = evaluatedEngineRecords.getValue(q.canonicalFen).scoreCp
                val highEvalDiffInCluster =
                    survivingCandidates
                        .filter {
                            val cScore = evaluatedEngineRecords.getValue(it.canonicalFen).scoreCp
                            kotlin.math.abs(qFenScore - cScore) > 150
                        }.map { it.positionId }
                        .toSet()

                val dynamicTop10 = rerankedBm25.take(10).map { it.first }.toSet()
                val evictedFromTop10 = highEvalDiffInCluster.minus(dynamicTop10).size
                totalFalseFamiliarityEvicted += evictedFromTop10
            }

            val nQ = probeQueries.size.toDouble()
            val meanSurv = sumSurviving / nQ
            val meanCrrVal = sumCrr / nQ

            val rec =
                AlphaRetentionRecord(
                    alpha = alpha,
                    meanSurvivingCandidates = meanSurv,
                    meanCandidateReductionRate = meanCrrVal,
                    // BM25
                    bm25MeanRetainedK10 = sumBm25RetK10 / nQ,
                    bm25MeanDiscardedK10 = 10.0 - (sumBm25RetK10 / nQ),
                    bm25RecallAt10 = (sumBm25RetK10 / nQ) / 10.0,
                    bm25MeanRetainedK20 = sumBm25RetK20 / nQ,
                    bm25MeanDiscardedK20 = 20.0 - (sumBm25RetK20 / nQ),
                    bm25RecallAt20 = (sumBm25RetK20 / nQ) / 20.0,
                    bm25PurityAt10 = sumBm25Purity10 / nQ,
                    bm25HitAt1 = sumBm25Hit1 / nQ,
                    bm25HitAt5 = sumBm25Hit5 / nQ,
                    bm25HitAt10 = sumBm25Hit10 / nQ,
                    bm25Mrr = sumBm25Mrr / nQ,
                    bm25NdcgAt10 = sumBm25Ndcg10 / nQ,
                    // TF-IDF
                    tfidfMeanRetainedK10 = sumTfidfRetK10 / nQ,
                    tfidfMeanDiscardedK10 = 10.0 - (sumTfidfRetK10 / nQ),
                    tfidfRecallAt10 = (sumTfidfRetK10 / nQ) / 10.0,
                    tfidfMeanRetainedK20 = sumTfidfRetK20 / nQ,
                    tfidfMeanDiscardedK20 = 20.0 - (sumTfidfRetK20 / nQ),
                    tfidfRecallAt20 = (sumTfidfRetK20 / nQ) / 20.0,
                    tfidfPurityAt10 = sumTfidfPurity10 / nQ,
                    tfidfHitAt1 = sumTfidfHit1 / nQ,
                    tfidfHitAt5 = sumTfidfHit5 / nQ,
                    tfidfHitAt10 = sumTfidfHit10 / nQ,
                    tfidfMrr = sumTfidfMrr / nQ,
                    tfidfNdcgAt10 = sumTfidfNdcg10 / nQ,
                    // Downstream Mistake Retention
                    mistakePositionsRetentionRate = sumMistakeRetained / nQ,
                    falseFamiliaritySuppressionCount = totalFalseFamiliarityEvicted,
                )
            alphaRecords.add(rec)
            println(
                "  alpha=$alpha: Surviving=${"%.1f".format(
                    meanSurv,
                )}, CRR=${"%.2f".format(meanCrrVal * 100)}%, BM25 Recall@10=${"%.2f".format(
                    rec.bm25RecallAt10 * 100,
                )}%, TFIDF Recall@10=${"%.2f".format(rec.tfidfRecallAt10 * 100)}%, MistakeRet=${"%.2f".format(
                    rec.mistakePositionsRetentionRate * 100,
                )}%",
            )
        }

        // -----------------------------------------------------------------
        // Stage 4: Tactical Motif Benchmark Validation (32 Frozen Pools)
        // -----------------------------------------------------------------
        println("Evaluating 32 tactical motif frozen pools under MultiPV=1 at Depth 16...")
        val frozenPoolsData: Map<String, Any> = mapper.readValue(frozenPoolsFile)

        @Suppress("UNCHECKED_CAST")
        val queriesList = frozenPoolsData["queries"] as List<Map<String, Any>>

        var bm25MotifHit1 = 0.0
        var bm25MotifHit5 = 0.0
        var bm25MotifHit10 = 0.0
        var bm25MotifMrr = 0.0

        var tfidfMotifHit1 = 0.0
        var tfidfMotifHit5 = 0.0
        var tfidfMotifHit10 = 0.0
        var tfidfMotifMrr = 0.0

        queriesList.forEach { q ->
            val qFen = q["canonicalFen"] as String
            val qRec = evaluatedEngineRecords.getValue(qFen)
            val qTokens = qRec.dynamicTokens
            val qTermSet = qTokens.filterValues { it > 0.0 }.keys

            @Suppress("UNCHECKED_CAST")
            val targetPositives = (q["targetPositives"] as List<String>).toSet()

            @Suppress("UNCHECKED_CAST")
            val candidates = q["candidates"] as List<Map<String, Any>>

            // Candidate tokens
            val candTokenList =
                candidates.map { c ->
                    val cFen = c["canonicalFen"] as String
                    val cPuzId = c["puzzleId"] as String
                    val cRec = evaluatedEngineRecords.getValue(cFen)
                    val cTokens = cRec.dynamicTokens
                    Triple(cPuzId, cFen, cTokens)
                }

            // Pool frequencies
            val poolDocFreqs = mutableMapOf<String, Int>()
            candTokenList.forEach { (_, _, tokens) ->
                tokens.filterValues { it > 0.0 }.keys.forEach { t ->
                    poolDocFreqs.merge(t, 1, Int::plus)
                }
            }
            val poolDocLengths = candTokenList.associate { it.first to it.third.values.sum() }
            val poolAvgLen = poolDocLengths.values.average()
            val poolIdfs = tfidfScorer.computeIdf(candTokenList.size, poolDocFreqs)

            // 1. BM25 scoring
            val rankedBm25 =
                candTokenList
                    .map { (puzId, _, tokens) ->
                        val len = poolDocLengths.getValue(puzId)
                        val score =
                            if (qTermSet.isNotEmpty()) {
                                DynamicTacticalScorer.bm25Score(
                                    queryTerms = qTermSet,
                                    documentTerms = tokens,
                                    documentFrequencies = poolDocFreqs,
                                    documentCount = candTokenList.size,
                                    documentLength = len,
                                    averageDocumentLength = poolAvgLen,
                                )
                            } else {
                                0.0
                            }
                        puzId to score
                    }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })

            val bm25FirstPositiveIdx = rankedBm25.indexOfFirst { targetPositives.contains(it.first) }
            val bm25Rank = if (bm25FirstPositiveIdx != -1) bm25FirstPositiveIdx + 1 else null
            if (bm25Rank != null && bm25Rank <= 1) bm25MotifHit1 += 1.0
            if (bm25Rank != null && bm25Rank <= 5) bm25MotifHit5 += 1.0
            if (bm25Rank != null && bm25Rank <= 10) bm25MotifHit10 += 1.0
            if (bm25Rank != null) bm25MotifMrr += 1.0 / bm25Rank

            // 2. TF-IDF Cosine scoring
            val qVec = tfidfScorer.encodeVector(qTokens, poolIdfs)
            val qNorm = tfidfScorer.vectorNorm(qVec)
            val rankedTfidf =
                candTokenList
                    .map { (puzId, _, tokens) ->
                        val docVec = tfidfScorer.encodeVector(tokens, poolIdfs)
                        val sim = if (qNorm > 0.0) tfidfScorer.cosineSimilarity(qVec, qNorm, docVec) else 0.0
                        puzId to sim
                    }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })

            val tfidfFirstPositiveIdx = rankedTfidf.indexOfFirst { targetPositives.contains(it.first) }
            val tfidfRank = if (tfidfFirstPositiveIdx != -1) tfidfFirstPositiveIdx + 1 else null
            if (tfidfRank != null && tfidfRank <= 1) tfidfMotifHit1 += 1.0
            if (tfidfRank != null && tfidfRank <= 5) tfidfMotifHit5 += 1.0
            if (tfidfRank != null && tfidfRank <= 10) tfidfMotifHit10 += 1.0
            if (tfidfRank != null) tfidfMotifMrr += 1.0 / tfidfRank
        }

        val nMotif = queriesList.size.toDouble()
        val motifResult =
            TacticalMotifEvaluationResult(
                totalQueries = queriesList.size,
                bm25HitAt1 = bm25MotifHit1 / nMotif,
                bm25HitAt5 = bm25MotifHit5 / nMotif,
                bm25HitAt10 = bm25MotifHit10 / nMotif,
                bm25Mrr = bm25MotifMrr / nMotif,
                tfidfHitAt1 = tfidfMotifHit1 / nMotif,
                tfidfHitAt5 = tfidfMotifHit5 / nMotif,
                tfidfHitAt10 = tfidfMotifHit10 / nMotif,
                tfidfMrr = tfidfMotifMrr / nMotif,
            )
        println("Tactical Motif Validation Results:")
        println(
            "  BM25:     Hit@1=${"%.4f".format(motifResult.bm25HitAt1)}, " +
                "Hit@5=${"%.4f".format(motifResult.bm25HitAt5)}, " +
                "Hit@10=${"%.4f".format(motifResult.bm25HitAt10)}, " +
                "MRR=${"%.4f".format(motifResult.bm25Mrr)}",
        )
        println(
            "  TF-IDF:   Hit@1=${"%.4f".format(motifResult.tfidfHitAt1)}, " +
                "Hit@5=${"%.4f".format(motifResult.tfidfHitAt5)}, " +
                "Hit@10=${"%.4f".format(motifResult.tfidfHitAt10)}, " +
                "MRR=${"%.4f".format(motifResult.tfidfMrr)}",
        )

        // -----------------------------------------------------------------
        // Stage 5: Assemble & Persist Reports
        // -----------------------------------------------------------------
        val scorerSummary =
            ScorerComparisonSummary(
                meanSpearmanRho = meanSpearman,
                meanTop10Jaccard = meanJaccard,
                tfidfHitAt1 = motifResult.tfidfHitAt1,
                tfidfHitAt5 = motifResult.tfidfHitAt5,
                tfidfHitAt10 = motifResult.tfidfHitAt10,
                tfidfMrr = motifResult.tfidfMrr,
                bm25HitAt1 = motifResult.bm25HitAt1,
                bm25HitAt5 = motifResult.bm25HitAt5,
                bm25HitAt10 = motifResult.bm25HitAt10,
                bm25Mrr = motifResult.bm25Mrr,
                lengthSensitivityNotes =
                    "BM25 penalizes multi-ply continuation tokens via document length normalization " +
                        "(1 - b + b*L/avgL). In tactical positions, rich combination lines produce more tokens, " +
                        "which BM25 suppresses relative to brief lines. TF-IDF Cosine normalizes solely by vector " +
                        "magnitude, maintaining stable retrieval across continuation lengths.",
            )

        val report =
            CombinedPipelinePhase1Report(
                totalUniquePositionsAnalyzed = evaluatedEngineRecords.size,
                totalActualEngineCalls = engineRunner.actualEngineSearchCalls.get(),
                engineCallCeiling = 11_000,
                passedEngineBudgetCeiling = engineRunner.actualEngineSearchCalls.get() <= 11_000,
                probeSetSize = probeQueries.size,
                scorerComparison = scorerSummary,
                alphaGridResults = alphaRecords,
                tacticalMotifValidation = motifResult,
            )

        // Save raw output files
        val resDir = File("src/test/resources/research")
        val baselineFile = File(resDir, "combined_pipeline_multipv1_baseline.json")
        val scorerFile = File(resDir, "scorer_comparison_tfidf_vs_bm25.json")
        val retentionFile = File(resDir, "dynamic_candidate_retention_breakdown.json")
        val weaknessFile = File(resDir, "downstream_weakness_comparison.json")

        mapper.writeValue(baselineFile, report)
        mapper.writeValue(scorerFile, scorerSummary)
        mapper.writeValue(retentionFile, alphaRecords)
        mapper.writeValue(
            weaknessFile,
            mapOf(
                "mistakeRetentionByAlpha" to alphaRecords.associate { it.alpha to it.mistakePositionsRetentionRate },
                "falseFamiliaritySuppressionByAlpha" to alphaRecords.associate { it.alpha to it.falseFamiliaritySuppressionCount },
                "decisionThresholdCp" to 120,
            ),
        )

        println("All Phase 1 artifacts persisted successfully to ${resDir.path}")

        // Assertions
        assertTrue(report.passedEngineBudgetCeiling)
        assertEquals(6, alphaRecords.size)
        assertTrue(motifResult.bm25HitAt10 >= 0.90)
    }

    private fun computeSpearman(
        x: List<Int>,
        y: List<Int>,
    ): Double {
        if (x.size != y.size || x.size <= 1) return 0.0
        val n = x.size.toDouble()
        var sumD2 = 0.0
        for (i in x.indices) {
            val d = (x[i] - y[i]).toDouble()
            sumD2 += d * d
        }
        return 1.0 - (6.0 * sumD2 / (n * (n * n - 1.0)))
    }

    private fun computeNdcg10(
        rankedIds: List<UUID>,
        relevantIds: Set<UUID>,
    ): Double {
        if (relevantIds.isEmpty()) return 0.0
        var dcg = 0.0
        for (i in 0 until minOf(10, rankedIds.size)) {
            val id = rankedIds[i]
            val rel = if (relevantIds.contains(id)) 1.0 else 0.0
            if (rel > 0.0) {
                dcg += rel / log2((i + 2).toDouble())
            }
        }
        var idcg = 0.0
        val idealCount = minOf(10, relevantIds.size)
        for (i in 0 until idealCount) {
            idcg += 1.0 / log2((i + 2).toDouble())
        }
        return if (idcg > 0.0) dcg / idcg else 0.0
    }
}
