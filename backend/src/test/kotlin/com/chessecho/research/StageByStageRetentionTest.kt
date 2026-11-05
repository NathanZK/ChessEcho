package com.chessecho.research

import com.chessecho.similarity.DynamicTacticalScorer
import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.RelationalPawnTokenExtractor
import com.chessecho.similarity.clustering.PositionClusterSimilarityScorer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.ArrayDeque
import java.util.Random
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StageByStageRetentionTest {
    private val mapper = jacksonObjectMapper()
    private val extractor = RelationalPawnTokenExtractor()
    private val tfidfScorer = DynamicTfidfScorer()
    private val clusterRunner = ClusteringExperimentRunner()

    private val corpusFile = File("src/test/resources/research/corpus_a.json")
    private val frozenPoolsFile = File("src/test/resources/research/tactical-motif-frozen-pools.json")
    private val engineCacheFile = File("src/test/resources/research/engine_evaluation_cache_d16_mpv1.json")
    private val outputFile = File("src/test/resources/research/stage_by_stage_retention_analysis.json")

    private lateinit var corpusProvenance: List<PositionProvenanceRecord>
    private lateinit var corpusDocs: List<PositionDocument>
    private lateinit var posById: Map<UUID, PositionDocument>
    private val evaluatedEngineRecords = mutableMapOf<String, CachedEngineRecord>()
    private val probeQueries = mutableListOf<PositionDocument>()
    private val alphas = listOf(0.00, 0.25, 0.50, 0.60, 0.75, 1.00)

    data class QueryRef(
        val queryDoc: PositionDocument,
        val candidateUniverse: List<PositionDocument>,
        val bm25RankMap: Map<UUID, Pair<Int, Double>>,
        val tfidfRankMap: Map<UUID, Pair<Int, Double>>,
        val bm25Top10: Set<UUID>,
        val bm25Top20: Set<UUID>,
        val tfidfTop10: Set<UUID>,
        val tfidfTop20: Set<UUID>,
    )

    private val references = mutableMapOf<UUID, QueryRef>()

    @BeforeAll
    fun setUp() {
        assertTrue(corpusFile.exists())
        assertTrue(frozenPoolsFile.exists())
        assertTrue(engineCacheFile.exists())

        val builder = CorpusBuilder()
        corpusProvenance = builder.loadCorpusFromJson(corpusFile)
        corpusDocs =
            corpusProvenance.map { pos ->
                val id = DeterministicIdGenerator.generateDeterministicId(pos.canonicalFen, pos.samplePly)
                extractor.extractDocument(id, pos.canonicalFen, includeReachability = true)
            }
        posById = corpusDocs.associateBy { it.positionId }

        val cacheType =
            mapper.typeFactory.constructMapType(
                HashMap::class.java,
                String::class.java,
                CachedEngineRecord::class.java,
            )
        val loaded: Map<String, CachedEngineRecord> = mapper.readValue(engineCacheFile, cacheType)
        loaded.values.forEach { evaluatedEngineRecords[it.canonicalFen] = it }
        assertEquals(10_252, evaluatedEngineRecords.size)

        val rng = Random(42L)
        val shuffled = corpusDocs.shuffled(rng)
        probeQueries.addAll(shuffled.take(250))
        assertEquals(250, probeQueries.size)

        // Precompute dynamic tokens and reference rankings
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
        val idfMap = tfidfScorer.computeIdf(totalDocs, docFreqs)

        for (q in probeQueries) {
            val qTerms = corpusTermsById.getValue(q.positionId)
            val qTurn = q.sideToMove
            val universe = corpusDocs.filter { it.positionId != q.positionId && it.sideToMove == qTurn }

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

            val bm25RankMap = bm25Scores.mapIndexed { idx, pair -> pair.first to (idx + 1 to pair.second) }.toMap()
            val tfidfRankMap = tfidfScores.mapIndexed { idx, pair -> pair.first to (idx + 1 to pair.second) }.toMap()

            references[q.positionId] =
                QueryRef(
                    queryDoc = q,
                    candidateUniverse = universe,
                    bm25RankMap = bm25RankMap,
                    tfidfRankMap = tfidfRankMap,
                    bm25Top10 = bm25Scores.take(10).map { it.first }.toSet(),
                    bm25Top20 = bm25Scores.take(20).map { it.first }.toSet(),
                    tfidfTop10 = tfidfScores.take(10).map { it.first }.toSet(),
                    tfidfTop20 = tfidfScores.take(20).map { it.first }.toSet(),
                )
        }
    }

    data class StageLossStats(
        val stage1LossCount: Int,
        val stage2DirectLossCount: Int,
        val stage2ComponentLossCount: Int,
        val stage3LeidenLossCount: Int,
        val stage4CentroidLossCount: Int,
        val retainedFinalCount: Int,
        val totalReferenceCandidates: Int,
        // Per-query distributions of retained counts at each milestone
        val s1RetentionCounts: List<Int>,
        val s2DirectRetentionCounts: List<Int>,
        val s2ComponentRetentionCounts: List<Int>,
        val s3LeidenRetentionCounts: List<Int>,
        val s4FinalRetentionCounts: List<Int>,
    )

    data class DiscardExample(
        val alpha: Double,
        val scorer: String,
        val stageLost: String,
        val queryId: UUID,
        val queryFen: String,
        val candidateId: UUID,
        val candidateFen: String,
        val dynamicRank: Int,
        val dynamicScore: Double,
        val staticSimScore: Double,
        val detailReason: String,
    )

    @Test
    fun `execute stage by stage retention waterfall across all six alphas`() {
        println("\n=== STAGE-BY-STAGE RETENTION WATERFALL ANALYSIS ===")

        val fullAnalysisReport = mutableMapOf<String, Any>()
        val concreteExamples = mutableListOf<DiscardExample>()

        for (alpha in alphas) {
            println("\n>>> Evaluating Alpha = $alpha <<<")
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

            // Build graph adjacency and connected components
            val adjacency = mutableMapOf<UUID, MutableSet<UUID>>()
            runResult.graph.positions.forEach { adjacency[it.positionId] = mutableSetOf() }
            runResult.graph.edges.forEach { edge ->
                adjacency.getOrPut(edge.sourceId) { mutableSetOf() }.add(edge.targetId)
                adjacency.getOrPut(edge.targetId) { mutableSetOf() }.add(edge.sourceId)
            }

            // Find connected components
            val componentMap = mutableMapOf<UUID, Int>()
            var compId = 0
            for (node in runResult.graph.positions.map { it.positionId }) {
                if (componentMap.containsKey(node)) continue
                val queue = ArrayDeque<UUID>()
                queue.add(node)
                componentMap[node] = compId
                while (queue.isNotEmpty()) {
                    val curr = queue.removeFirst()
                    val neighbors = adjacency[curr].orEmpty()
                    for (nbr in neighbors) {
                        if (!componentMap.containsKey(nbr)) {
                            componentMap[nbr] = compId
                            queue.add(nbr)
                        }
                    }
                }
                compId++
            }

            val candidateMap = runResult.candidateMap
            val leidenAssignments = runResult.leidenResult.assignments
            val evalAssignments = runResult.evaluationResult.assignments.associate { it.positionId to it.clusterIndex }

            // Evaluate both scorers and both K=10, K=20
            val scorers = listOf("BM25", "TF-IDF")
            val kValues = listOf(10, 20)

            val alphaReport = mutableMapOf<String, Any>()

            for (scorerName in scorers) {
                for (k in kValues) {
                    val label = "${scorerName}_Top$k"

                    var stage1LossTotal = 0
                    var stage2DirectLossTotal = 0
                    var stage2CompLossTotal = 0
                    var stage3LeidenLossTotal = 0
                    var stage4CentroidLossTotal = 0
                    var retainedFinalTotal = 0

                    val s1RetList = mutableListOf<Int>()
                    val s2DirectRetList = mutableListOf<Int>()
                    val s2CompRetList = mutableListOf<Int>()
                    val s3LeidenRetList = mutableListOf<Int>()
                    val s4FinalRetList = mutableListOf<Int>()

                    // Track direct vs component attribution
                    // Attribution waterfall using Direct MNN:
                    // Stage 0: Universe (K)
                    // Stage 1: Inverted Index Top 200
                    // Stage 2: Relaxed MNN Direct Graph Edge (or Component)
                    // Stage 3: Leiden Community
                    // Stage 4: Centroid Cohesion
                    var waterfallStage1 = 0
                    var waterfallStage2Edge = 0
                    var waterfallStage2Comp = 0
                    var waterfallStage3Leiden = 0
                    var waterfallStage4Centroid = 0
                    var waterfallRetained = 0

                    for (q in probeQueries) {
                        val ref = references.getValue(q.positionId)
                        val refCandidates =
                            when {
                                scorerName == "BM25" && k == 10 -> ref.bm25Top10
                                scorerName == "BM25" && k == 20 -> ref.bm25Top20
                                scorerName == "TF-IDF" && k == 10 -> ref.tfidfTop10
                                else -> ref.tfidfTop20
                            }

                        val indexCands = candidateMap[q.positionId]?.toSet() ?: emptySet()
                        val qNeighbors = adjacency[q.positionId] ?: emptySet()
                        val qComp = componentMap[q.positionId]
                        val qLeiden = leidenAssignments[q.positionId]
                        val qEval = evalAssignments[q.positionId]

                        var s1Ret = 0
                        var s2DirRet = 0
                        var s2CompRet = 0
                        var s3LeiRet = 0
                        var s4FinRet = 0

                        for (candId in refCandidates) {
                            val inS1 = indexCands.contains(candId)
                            val inS2Dir = qNeighbors.contains(candId)
                            val inS2Comp = qComp != null && componentMap[candId] == qComp && (qNeighbors.isNotEmpty() && (adjacency[candId]?.isNotEmpty() == true))
                            val inS3 = qLeiden != null && leidenAssignments[candId] == qLeiden
                            val inS4 = qEval != null && evalAssignments[candId] == qEval

                            if (inS1) s1Ret++
                            if (inS2Dir) s2DirRet++
                            if (inS2Comp) s2CompRet++
                            if (inS3) s3LeiRet++
                            if (inS4) s4FinRet++

                            // Strict Component Waterfall Attribution:
                            // S0 (Universe) -> S1 (Index) -> S2 (Graph Component) -> S3 (Leiden Community) -> S4 (Centroid) -> Retained
                            if (!inS1) {
                                waterfallStage1++
                                if (concreteExamples.size < 60 && scorerName == "TF-IDF" && k == 10 && alpha == 0.60) {
                                    concreteExamples.add(
                                        buildExample(alpha, scorerName, "Stage 1 (Inverted Index Truncation)", q, candId, runResult, "Omitted from top-200 inverted index candidate pool"),
                                    )
                                }
                            } else if (!inS2Comp) {
                                waterfallStage2Comp++
                                if (concreteExamples.size < 60 && scorerName == "TF-IDF" && k == 10 && alpha == 0.60) {
                                    concreteExamples.add(
                                        buildExample(alpha, scorerName, "Stage 2 (Graph Component Disconnection)", q, candId, runResult, "In top-200, but failed Relaxed MNN edge criteria with all graph paths connecting to query"),
                                    )
                                }
                            } else if (!inS3) {
                                waterfallStage3Leiden++
                                if (concreteExamples.size < 60 && scorerName == "TF-IDF" && k == 10 && alpha == 0.60) {
                                    concreteExamples.add(
                                        buildExample(alpha, scorerName, "Stage 3 (Leiden Community Partitioning)", q, candId, runResult, "In same graph component, but Leiden partitioned node into a different community or isolated it"),
                                    )
                                }
                            } else if (!inS4) {
                                waterfallStage4Centroid++
                                if (concreteExamples.size < 60 && scorerName == "TF-IDF" && k == 10 && alpha == 0.60) {
                                    concreteExamples.add(
                                        buildExample(alpha, scorerName, "Stage 4 (Centroid Cohesion Eviction)", q, candId, runResult, "In same Leiden community, but evicted as centroid outlier (similarity < 0.38)"),
                                    )
                                }
                            } else {
                                waterfallRetained++
                            }

                            // Also track direct edge loss specifically
                            if (inS1 && !inS2Dir) {
                                waterfallStage2Edge++
                            }
                        }

                        s1RetList.add(s1Ret)
                        s2DirectRetList.add(s2DirRet)
                        s2CompRetList.add(s2CompRet)
                        s3LeidenRetList.add(s3LeiRet)
                        s4FinalRetList.add(s4FinRet)
                    }

                    val totalCandidates = k * probeQueries.size
                    assertEquals(totalCandidates, waterfallStage1 + waterfallStage2Comp + waterfallStage3Leiden + waterfallStage4Centroid + waterfallRetained)

                    val summary =
                        mapOf(
                            "totalCandidates" to totalCandidates,
                            "waterfallStage1Loss" to waterfallStage1,
                            "waterfallStage1LossPct" to "%.2f%%".format(waterfallStage1 * 100.0 / totalCandidates),
                            "waterfallStage2CompLoss" to waterfallStage2Comp,
                            "waterfallStage2CompLossPct" to "%.2f%%".format(waterfallStage2Comp * 100.0 / totalCandidates),
                            "waterfallStage2DirectEdgeMiss" to waterfallStage2Edge,
                            "waterfallStage2DirectEdgeMissPct" to "%.2f%%".format(waterfallStage2Edge * 100.0 / totalCandidates),
                            "waterfallStage3LeidenLoss" to waterfallStage3Leiden,
                            "waterfallStage3LeidenLossPct" to "%.2f%%".format(waterfallStage3Leiden * 100.0 / totalCandidates),
                            "waterfallStage4CentroidLoss" to waterfallStage4Centroid,
                            "waterfallStage4CentroidLossPct" to "%.2f%%".format(waterfallStage4Centroid * 100.0 / totalCandidates),
                            "waterfallRetained" to waterfallRetained,
                            "waterfallRetainedPct" to "%.2f%%".format(waterfallRetained * 100.0 / totalCandidates),
                            // Milestone cumulative surviving candidates
                            "s1SurvivingSum" to s1RetList.sum(),
                            "s1SurvivingPct" to "%.2f%%".format(s1RetList.sum() * 100.0 / totalCandidates),
                            "s1Dist" to computeDistribution(s1RetList),
                            "s2DirectSurvivingSum" to s2DirectRetList.sum(),
                            "s2DirectSurvivingPct" to "%.2f%%".format(s2DirectRetList.sum() * 100.0 / totalCandidates),
                            "s2DirectDist" to computeDistribution(s2DirectRetList),
                            "s2CompSurvivingSum" to s2CompRetList.sum(),
                            "s2CompSurvivingPct" to "%.2f%%".format(s2CompRetList.sum() * 100.0 / totalCandidates),
                            "s2CompDist" to computeDistribution(s2CompRetList),
                            "s3LeidenSurvivingSum" to s3LeidenRetList.sum(),
                            "s3LeidenSurvivingPct" to "%.2f%%".format(s3LeidenRetList.sum() * 100.0 / totalCandidates),
                            "s3LeidenDist" to computeDistribution(s3LeidenRetList),
                            "s4FinalSurvivingSum" to s4FinalRetList.sum(),
                            "s4FinalSurvivingPct" to "%.2f%%".format(s4FinalRetList.sum() * 100.0 / totalCandidates),
                            "s4FinalDist" to computeDistribution(s4FinalRetList),
                        )
                    alphaReport[label] = summary

                    println(
                        "  [$label] Total: $totalCandidates | S1 Loss: $waterfallStage1 (${summary["waterfallStage1LossPct"]}) | S2 Loss: $waterfallStage2Edge (${summary["waterfallStage2EdgeLossPct"]}) | S3 Loss: $waterfallStage3Leiden (${summary["waterfallStage3LeidenLossPct"]}) | S4 Loss: $waterfallStage4Centroid (${summary["waterfallStage4CentroidLossPct"]}) | Retained: $waterfallRetained (${summary["waterfallRetainedPct"]})",
                    )
                }
            }
            fullAnalysisReport["alpha_$alpha"] = alphaReport
        }

        fullAnalysisReport["examples"] = concreteExamples.map { ex ->
            mapOf(
                "alpha" to ex.alpha,
                "scorer" to ex.scorer,
                "stageLost" to ex.stageLost,
                "queryId" to ex.queryId.toString(),
                "queryFen" to ex.queryFen,
                "candidateId" to ex.candidateId.toString(),
                "candidateFen" to ex.candidateFen,
                "dynamicRank" to ex.dynamicRank,
                "dynamicScore" to ex.dynamicScore,
                "staticSimScore" to ex.staticSimScore,
                "detailReason" to ex.detailReason,
            )
        }

        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, fullAnalysisReport)
        println("\nWrote complete stage-by-stage analysis artifact to ${outputFile.path}")
    }

    private fun buildExample(
        alpha: Double,
        scorer: String,
        stage: String,
        q: PositionDocument,
        candId: UUID,
        runResult: ExperimentRunResult,
        reason: String,
    ): DiscardExample {
        val candDoc = posById.getValue(candId)
        val qVec = runResult.vectors[q.positionId]
        val cVec = runResult.vectors[candId]
        val staticSim =
            if (qVec != null && cVec != null) {
                PositionClusterSimilarityScorer().cosineSimilarity(qVec, cVec)
            } else {
                0.0
            }
        val ref = references.getValue(q.positionId)
        val rankScore = if (scorer == "BM25") ref.bm25RankMap[candId] else ref.tfidfRankMap[candId]
        val dynRank = rankScore?.first ?: -1
        val dynScore = rankScore?.second ?: 0.0

        return DiscardExample(
            alpha = alpha,
            scorer = scorer,
            stageLost = stage,
            queryId = q.positionId,
            queryFen = q.canonicalFen,
            candidateId = candId,
            candidateFen = candDoc.canonicalFen,
            dynamicRank = dynRank,
            dynamicScore = dynScore,
            staticSimScore = staticSim,
            detailReason = reason,
        )
    }

    @Test
    fun `extract concrete stage examples for alpha 0_60`() {
        println("\n=== EXTRACTING CONCRETE EXAMPLES FOR ALL 4 STAGES AT ALPHA 0.60 ===")
        val config =
            ExperimentConfig(
                alpha = 0.60,
                candidateLimit = 200,
                topK = 30,
                floorThreshold = 0.40,
                top1Threshold = 0.65,
                rbResolution = 1.25,
                centroidThreshold = 0.38,
                randomSeed = 42L,
            )
        val runResult = clusterRunner.runExperiment(corpusDocs, config)

        val adjacency = mutableMapOf<UUID, MutableSet<UUID>>()
        runResult.graph.positions.forEach { adjacency[it.positionId] = mutableSetOf() }
        runResult.graph.edges.forEach { edge ->
            adjacency.getOrPut(edge.sourceId) { mutableSetOf() }.add(edge.targetId)
            adjacency.getOrPut(edge.targetId) { mutableSetOf() }.add(edge.sourceId)
        }

        val componentMap = mutableMapOf<UUID, Int>()
        var compId = 0
        for (node in runResult.graph.positions.map { it.positionId }) {
            if (componentMap.containsKey(node)) continue
            val queue = ArrayDeque<UUID>()
            queue.add(node)
            componentMap[node] = compId
            while (queue.isNotEmpty()) {
                val curr = queue.removeFirst()
                val neighbors = adjacency[curr].orEmpty()
                for (nbr in neighbors) {
                    if (!componentMap.containsKey(nbr)) {
                        componentMap[nbr] = compId
                        queue.add(nbr)
                    }
                }
            }
            compId++
        }

        val candidateMap = runResult.candidateMap
        val leidenAssignments = runResult.leidenResult.assignments
        val evalAssignments = runResult.evaluationResult.assignments.associate { it.positionId to it.clusterIndex }

        val examplesByStage = mutableMapOf<String, MutableList<String>>()

        for (q in probeQueries) {
            val ref = references.getValue(q.positionId)
            val indexCands = candidateMap[q.positionId]?.toSet() ?: emptySet()
            val qNeighbors = adjacency[q.positionId] ?: emptySet()
            val qComp = componentMap[q.positionId]
            val qLeiden = leidenAssignments[q.positionId]
            val qEval = evalAssignments[q.positionId]

            val qRec = evaluatedEngineRecords.getValue(q.canonicalFen)
            val qPv = qRec.principalVariations.firstOrNull()?.moves?.take(5)?.joinToString(" ") ?: qRec.bestMove

            for (candId in ref.tfidfTop10) {
                val inS1 = indexCands.contains(candId)
                val inS2Dir = qNeighbors.contains(candId)
                val inS2Comp = qComp != null && componentMap[candId] == qComp && qNeighbors.isNotEmpty() && (adjacency[candId]?.isNotEmpty() == true)
                val inS3 = qLeiden != null && leidenAssignments[candId] == qLeiden
                val inS4 = qEval != null && evalAssignments[candId] == qEval

                val cDoc = posById.getValue(candId)
                val cRec = evaluatedEngineRecords.getValue(cDoc.canonicalFen)
                val cPv = cRec.principalVariations.firstOrNull()?.moves?.take(5)?.joinToString(" ") ?: cRec.bestMove
                val qVec = runResult.vectors[q.positionId]
                val cVec = runResult.vectors[candId]
                val staticSim = if (qVec != null && cVec != null) PositionClusterSimilarityScorer().cosineSimilarity(qVec, cVec) else 0.0
                val dynRank = ref.tfidfRankMap[candId]?.first ?: -1
                val dynSim = ref.tfidfRankMap[candId]?.second ?: 0.0

                val stage = when {
                    !inS1 -> "Stage 1 (Inverted Index Truncation)"
                    !inS2Comp -> "Stage 2 (Graph Disconnection)"
                    !inS3 -> "Stage 3 (Leiden Partitioning)"
                    !inS4 -> "Stage 4 (Centroid Eviction)"
                    else -> "Retained Final"
                }

                if (examplesByStage.getOrPut(stage) { mutableListOf() }.size < 3) {
                    val sb = StringBuilder()
                    sb.appendLine("STAGE: $stage")
                    sb.appendLine("  Query FEN: ${q.canonicalFen}")
                    sb.appendLine("  Query PV:  $qPv (Eval: ${qRec.scoreCp} cp)")
                    sb.appendLine("  Cand FEN:  ${cDoc.canonicalFen}")
                    sb.appendLine("  Cand PV:   $cPv (Eval: ${cRec.scoreCp} cp)")
                    sb.appendLine("  Dynamic TF-IDF Rank: #$dynRank (Score: ${"%.4f".format(dynSim)})")
                    sb.appendLine("  Static Similarity:    ${"%.4f".format(staticSim)}")
                    sb.appendLine("  Status: inS1=$inS1, inS2Direct=$inS2Dir, inS2Comp=$inS2Comp, inS3Leiden=$inS3, inS4Final=$inS4")
                    examplesByStage.getValue(stage).add(sb.toString())
                }
            }
        }

        examplesByStage.forEach { (st, list) ->
            println("\n=======================================================")
            println("EXAMPLES FOR: $st")
            println("=======================================================")
            list.forEach { println(it) }
        }
    }

    private fun computeDistribution(values: List<Int>): Map<String, Any> {
        val sorted = values.sorted()
        val n = sorted.size
        val zeroCount = sorted.count { it == 0 }
        return mapOf(
            "min" to sorted.first(),
            "p25" to sorted[(n * 0.25).toInt()],
            "median" to sorted[(n * 0.50).toInt()],
            "p75" to sorted[(n * 0.75).toInt()],
            "p90" to sorted[(n * 0.90).toInt()],
            "max" to sorted.last(),
            "mean" to "%.3f".format(sorted.average()),
            "zeroCount" to zeroCount,
            "zeroPct" to "%.1f%%".format(zeroCount * 100.0 / n),
        )
    }
}
