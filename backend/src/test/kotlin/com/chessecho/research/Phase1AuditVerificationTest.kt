package com.chessecho.research

import com.chessecho.similarity.DynamicTacticalScorer
import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.RelationalPawnTokenExtractor
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase1AuditVerificationTest {
    private val mapper = jacksonObjectMapper()
    private val extractor = RelationalPawnTokenExtractor()
    private val tfidfScorer = DynamicTfidfScorer()
    private val clusterRunner = ClusteringExperimentRunner()

    private val corpusFile = File("src/test/resources/research/corpus_a.json")
    private val frozenPoolsFile = File("src/test/resources/research/tactical-motif-frozen-pools.json")
    private val engineCacheFile = File("src/test/resources/research/engine_evaluation_cache_d16_mpv1.json")

    private lateinit var corpusProvenance: List<PositionProvenanceRecord>
    private lateinit var corpusDocs: List<PositionDocument>
    private lateinit var posById: Map<UUID, PositionDocument>
    private val evaluatedEngineRecords = mutableMapOf<String, CachedEngineRecord>()
    private val probeQueries = mutableListOf<PositionDocument>()
    private val alphas = listOf(0.00, 0.25, 0.50, 0.60, 0.75, 1.00)

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
    }

    @Test
    fun `audit 32 tactical motif benchmark individual query results`() {
        val frozenPoolsData: Map<String, Any> = mapper.readValue(frozenPoolsFile)

        @Suppress("UNCHECKED_CAST")
        val queriesList = frozenPoolsData["queries"] as List<Map<String, Any>>

        println("\n=== AUDIT ITEM 4: 32 INDIVIDUAL TACTICAL BENCHMARK RESULTS ===")
        println("Idx | Query ID | Motif Name | Target Positives | BM25 Rank | TF-IDF Rank | Delta Rank | BM25 Hit@10 | TF-IDF Hit@10")
        println("----------------------------------------------------------------------------------------------------------------")

        var bm25Hit1 = 0
        var bm25Hit5 = 0
        var bm25Hit10 = 0
        var bm25MrrSum = 0.0

        var tfidfHit1 = 0
        var tfidfHit5 = 0
        var tfidfHit10 = 0
        var tfidfMrrSum = 0.0

        queriesList.forEachIndexed { idx, q ->
            val qId = (q["queryId"] ?: q["id"] ?: "query_$idx").toString()
            val motifName = (q["motifName"] ?: q["motif"] ?: "motif").toString()
            val qFen = q["canonicalFen"] as String
            val qRec = evaluatedEngineRecords.getValue(qFen)
            val qTokens = qRec.dynamicTokens
            val qTermSet = qTokens.filterValues { it > 0.0 }.keys

            @Suppress("UNCHECKED_CAST")
            val targetPositives = (q["targetPositives"] as List<String>).toSet()

            @Suppress("UNCHECKED_CAST")
            val candidates = q["candidates"] as List<Map<String, Any>>

            val candTokenList =
                candidates.map { c ->
                    val cFen = c["canonicalFen"] as String
                    val cPuzId = c["puzzleId"] as String
                    val cRec = evaluatedEngineRecords.getValue(cFen)
                    Triple(cPuzId, cFen, cRec.dynamicTokens)
                }

            val poolDocFreqs = mutableMapOf<String, Int>()
            candTokenList.forEach { (_, _, tokens) ->
                tokens.filterValues { it > 0.0 }.keys.forEach { t ->
                    poolDocFreqs.merge(t, 1, Int::plus)
                }
            }
            val poolDocLengths = candTokenList.associate { it.first to it.third.values.sum() }
            val poolAvgLen = poolDocLengths.values.average()
            val poolIdfs = tfidfScorer.computeIdf(candTokenList.size, poolDocFreqs)

            // BM25
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

            val bm25Idx = rankedBm25.indexOfFirst { targetPositives.contains(it.first) }
            val bm25Rank = if (bm25Idx != -1) bm25Idx + 1 else null
            if (bm25Rank != null && bm25Rank <= 1) bm25Hit1++
            if (bm25Rank != null && bm25Rank <= 5) bm25Hit5++
            if (bm25Rank != null && bm25Rank <= 10) bm25Hit10++
            if (bm25Rank != null) bm25MrrSum += 1.0 / bm25Rank

            // TF-IDF
            val qVec = tfidfScorer.encodeVector(qTokens, poolIdfs)
            val qNorm = tfidfScorer.vectorNorm(qVec)
            val rankedTfidf =
                candTokenList
                    .map { (puzId, _, tokens) ->
                        val docVec = tfidfScorer.encodeVector(tokens, poolIdfs)
                        val sim = if (qNorm > 0.0) tfidfScorer.cosineSimilarity(qVec, qNorm, docVec) else 0.0
                        puzId to sim
                    }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })

            val tfidfIdx = rankedTfidf.indexOfFirst { targetPositives.contains(it.first) }
            val tfidfRank = if (tfidfIdx != -1) tfidfIdx + 1 else null
            if (tfidfRank != null && tfidfRank <= 1) tfidfHit1++
            if (tfidfRank != null && tfidfRank <= 5) tfidfHit5++
            if (tfidfRank != null && tfidfRank <= 10) tfidfHit10++
            if (tfidfRank != null) tfidfMrrSum += 1.0 / tfidfRank

            val deltaRank = if (bm25Rank != null && tfidfRank != null) bm25Rank - tfidfRank else null
            println(
                String.format(
                    "%-3d | %-8s | %-15s | %-16s | %-9s | %-11s | %-10s | %-11s | %-12s",
                    idx + 1,
                    qId,
                    motifName.take(15),
                    targetPositives.joinToString(",").take(16),
                    bm25Rank?.toString() ?: "MISS",
                    tfidfRank?.toString() ?: "MISS",
                    deltaRank?.let { if (it > 0) "+$it" else "$it" } ?: "N/A",
                    if (bm25Rank != null && bm25Rank <= 10) "YES" else "NO",
                    if (tfidfRank != null && tfidfRank <= 10) "YES" else "NO",
                ),
            )
        }

        val n = queriesList.size.toDouble()
        println("----------------------------------------------------------------------------------------------------------------")
        println(
            "SUMMARY: BM25   Hit@1 = $bm25Hit1/32 (${"%.2f".format(bm25Hit1 / n * 100)}%), Hit@5 = $bm25Hit5/32 (${"%.2f".format(
                bm25Hit5 / n * 100,
            )}%), Hit@10 = $bm25Hit10/32 (${"%.2f".format(bm25Hit10 / n * 100)}%), MRR = ${"%.4f".format(bm25MrrSum / n)}",
        )
        println(
            "SUMMARY: TF-IDF Hit@1 = $tfidfHit1/32 (${"%.2f".format(tfidfHit1 / n * 100)}%), Hit@5 = $tfidfHit5/32 (${"%.2f".format(
                tfidfHit5 / n * 100,
            )}%), Hit@10 = $tfidfHit10/32 (${"%.2f".format(tfidfHit10 / n * 100)}%), MRR = ${"%.4f".format(tfidfMrrSum / n)}",
        )
    }

    @Test
    fun `audit per-query recall distributions and alpha cluster sizes`() {
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
        val idfMap = tfidfScorer.computeIdf(totalDocs, docFreqs)

        // For each query, precompute reference rankings
        data class QueryRef(
            val queryDoc: PositionDocument,
            val candidateUniverse: List<PositionDocument>,
            val bm25Top10: Set<UUID>,
            val bm25Top20: Set<UUID>,
            val tfidfTop10: Set<UUID>,
            val tfidfTop20: Set<UUID>,
            val mistakeCandidates: Set<UUID>,
        )

        val references = mutableMapOf<UUID, QueryRef>()
        for (q in probeQueries) {
            val qTerms = corpusTermsById.getValue(q.positionId)
            val qTurn = q.sideToMove
            val qScore = evaluatedEngineRecords.getValue(q.canonicalFen).scoreCp
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

            val mistakes =
                universe
                    .filter { cand ->
                        val cScore = evaluatedEngineRecords.getValue(cand.canonicalFen).scoreCp
                        kotlin.math.abs(qScore - cScore) >= 120
                    }.map { it.positionId }
                    .toSet()

            references[q.positionId] =
                QueryRef(
                    queryDoc = q,
                    candidateUniverse = universe,
                    bm25Top10 = bm25Scores.take(10).map { it.first }.toSet(),
                    bm25Top20 = bm25Scores.take(20).map { it.first }.toSet(),
                    tfidfTop10 = tfidfScores.take(10).map { it.first }.toSet(),
                    tfidfTop20 = tfidfScores.take(20).map { it.first }.toSet(),
                    mistakeCandidates = mistakes,
                )
        }

        println("\n=== AUDIT ITEM 1 & 2: PER-QUERY RECALL DISTRIBUTIONS & ALPHA CANDIDATE COUNTS ===")
        for (alpha in alphas) {
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

            val clusterSizes = clustersByIndex.values.map { it.size }
            val nClusters = clustersByIndex.size
            val nAssigned = assigned.size
            val nUnassigned = corpusDocs.size - nAssigned

            val survivingCounts = mutableListOf<Int>()
            val bm25Ret10Counts = mutableListOf<Int>()
            val bm25Ret20Counts = mutableListOf<Int>()
            val tfidfRet10Counts = mutableListOf<Int>()
            val tfidfRet20Counts = mutableListOf<Int>()
            val mistakeRetProportions = mutableListOf<Double>()
            val unassignedQueryCount = probeQueries.count { clusterAssignments[it.positionId] == null }

            for (q in probeQueries) {
                val ref = references.getValue(q.positionId)
                val cIdx = clusterAssignments[q.positionId]
                val survivingIds =
                    if (cIdx != null) {
                        clustersByIndex.getValue(cIdx).filter { it != q.positionId }.toSet()
                    } else {
                        emptySet()
                    }

                survivingCounts.add(survivingIds.size)
                bm25Ret10Counts.add(ref.bm25Top10.intersect(survivingIds).size)
                bm25Ret20Counts.add(ref.bm25Top20.intersect(survivingIds).size)
                tfidfRet10Counts.add(ref.tfidfTop10.intersect(survivingIds).size)
                tfidfRet20Counts.add(ref.tfidfTop20.intersect(survivingIds).size)

                val mTotal = ref.mistakeCandidates.size
                val mRet = ref.mistakeCandidates.intersect(survivingIds).size
                mistakeRetProportions.add(if (mTotal > 0) mRet.toDouble() / mTotal else 1.0)
            }

            println("\n--- ALPHA = $alpha ---")
            println(
                "Clustering summary: Total clusters = $nClusters, Assigned positions = $nAssigned (${"%.1f".format(
                    nAssigned.toDouble() / totalDocs * 100,
                )}%), Unassigned = $nUnassigned",
            )
            println(
                "Cluster size stats: Min = ${clusterSizes.minOrNull()}, Median = ${percentile(
                    clusterSizes,
                    50.0,
                )}, Mean = ${"%.1f".format(
                    clusterSizes.average(),
                )}, Max = ${clusterSizes.maxOrNull()}, P90 = ${percentile(clusterSizes, 90.0)}",
            )
            println(
                "Probe queries unassigned (0 surviving): $unassignedQueryCount / 250 (${"%.1f".format(
                    unassignedQueryCount.toDouble() / 250 * 100,
                )}%)",
            )
            println(
                "Surviving candidates per query: Mean = ${"%.2f".format(
                    survivingCounts.average(),
                )}, Median = ${percentile(
                    survivingCounts,
                    50.0,
                )}, Min = ${survivingCounts.minOrNull()}, Max = ${survivingCounts.maxOrNull()}",
            )

            // Histograms of retained candidates out of 10
            val bm25Histo10 = (0..10).associateWith { count -> bm25Ret10Counts.count { it == count } }
            val tfidfHisto10 = (0..10).associateWith { count -> tfidfRet10Counts.count { it == count } }
            println("BM25 Retained@10 Distribution (Histogram out of 10): $bm25Histo10")
            println("TF-IDF Retained@10 Distribution (Histogram out of 10): $tfidfHisto10")

            val bm25Histo20 = (0..20).associateWith { count -> bm25Ret20Counts.count { it == count } }.filterValues { it > 0 }
            val tfidfHisto20 = (0..20).associateWith { count -> tfidfRet20Counts.count { it == count } }.filterValues { it > 0 }
            println("BM25 Retained@20 Non-zero Distribution: $bm25Histo20")
            println("TF-IDF Retained@20 Non-zero Distribution: $tfidfHisto20")
            println("Mistake retention proportion mean: ${"%.4f".format(mistakeRetProportions.average())}")
        }
    }

    private fun percentile(
        list: List<Int>,
        p: Double,
    ): Double {
        if (list.isEmpty()) return 0.0
        val sorted = list.sorted()
        val index = (p / 100.0 * (sorted.size - 1)).toInt()
        return sorted[index].toDouble()
    }
}
