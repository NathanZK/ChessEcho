package com.chessecho.research

import com.chessecho.similarity.PositionDocument
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.time.Instant
import kotlin.math.max

class DefaultConnectivityClosureAblationRunner : ConnectivityClosureAblationRunner {

    private val mapper = jacksonObjectMapper()
    private val extractor = ConnectivityClosureExtractor()
    private val diagnosticEvaluator = ContinuationCaptureDiagnosticEvaluator()

    override fun runAblation(
        corpusDocs: List<PositionDocument>,
        probeQueries: List<PositionDocument>,
        budgets: List<Int>,
        engineCacheFile: File,
        baselineResultsFile: File,
    ): ConnectivityAblationResult {
        // 1. Ingest engine cache
        println("Loading engine evaluation cache from ${engineCacheFile.name}...")
        val rawCache: Map<String, CachedEngineRecord> = mapper.readValue(engineCacheFile)
        val engineCache: Map<String, CachedEngineRecord> = rawCache.values.associateBy { it.canonicalFen }

        // 2. Compute dynamic reference rankings
        println("Computing same-side dynamic reference rankings for ${probeQueries.size} queries...")
        val refEvaluator = DynamicReferenceRankingEvaluator(corpusDocs, engineCache)
        val queryReferences = probeQueries.associate { it.positionId to refEvaluator.computeReferenceForQuery(it) }

        // 3. Precompute terms for Corpus A
        println("Extracting attack, defense, and combined closure terms across Corpus A...")
        val attackTermsByDoc = corpusDocs.associateWith { extractor.extractAttackTerms(it.fen).keys }
        val defenseTermsByDoc = corpusDocs.associateWith { extractor.extractDefenseTerms(it.fen).keys }
        val combinedTermsByDoc = corpusDocs.associateWith { extractor.extractCombinedTerms(it.fen).keys }

        // 4. Compute Vocabulary Statistics (§6.3)
        val vocabularyStatistics = mapOf(
            "ATTACK_CLOSURE" to computeVocabularySummary(attackTermsByDoc.values),
            "DEFENSE_CLOSURE" to computeVocabularySummary(defenseTermsByDoc.values),
            "COMBINED_CLOSURE" to computeVocabularySummary(combinedTermsByDoc.values),
        )

        // 5. Run Capture Continuation Diagnostics D1 & D2 (§7)
        println("Running continuation capture diagnostics over Corpus A...")
        val positionsWithPvs = corpusDocs.map { doc ->
            val rec = engineCache[doc.canonicalFen] ?: error("Missing cache for ${doc.canonicalFen}")
            val pvMoves = rec.principalVariations.firstOrNull()?.moves ?: emptyList()
            doc.fen to pvMoves
        }
        val diagnosticResults = diagnosticEvaluator.evaluateCorpus(positionsWithPvs)

        // 6. Build Candidate Selectors
        val selectorAttack = ConnectivityCandidateSelector(corpusDocs, { attackTermsByDoc[it] ?: emptySet() })
        val selectorDefense = ConnectivityCandidateSelector(corpusDocs, { defenseTermsByDoc[it] ?: emptySet() })
        val selectorCombined = ConnectivityCandidateSelector(corpusDocs, { combinedTermsByDoc[it] ?: emptySet() })

        val systems = listOf(
            Triple("SYSTEM_C1_ATTACK_CLOSURE", "ATTACK_CLOSURE", selectorAttack),
            Triple("SYSTEM_C2_DEFENSE_CLOSURE", "DEFENSE_CLOSURE", selectorDefense),
            Triple("SYSTEM_C3_COMBINED_CLOSURE", "COMBINED_CLOSURE", selectorCombined),
        )

        // Pre-run candidate pools per query, system, budget
        val candidatePools = mutableMapOf<String, MutableMap<Int, MutableMap<PositionDocument, CandidatePoolResult>>>()
        for ((systemId, _, selector) in systems) {
            val budgetMap = mutableMapOf<Int, MutableMap<PositionDocument, CandidatePoolResult>>()
            for (b in budgets) {
                val qMap = mutableMapOf<PositionDocument, CandidatePoolResult>()
                for (q in probeQueries) {
                    qMap[q] = selector.selectCandidates(q, b)
                }
                budgetMap[b] = qMap
            }
            candidatePools[systemId] = budgetMap
        }

        // 7. Evaluate System Metrics across budgets
        val systemResults = mutableMapOf<String, ConnectivitySystemRecord>()

        for ((systemId, repType, _) in systems) {
            val budgetResults = mutableMapOf<String, BudgetSystemMetrics>()
            for (b in budgets) {
                val poolMap = candidatePools.getValue(systemId).getValue(b)

                val actualCounts = mutableListOf<Double>()
                val crrActualList = mutableListOf<Double>()
                var poolExhaustedCount = 0

                val bm25RecallAt10List = mutableListOf<Double>()
                val bm25RecallAt20List = mutableListOf<Double>()
                val bm25PurityAt10List = mutableListOf<Double>()
                val bm25PurityAt20List = mutableListOf<Double>()

                val tfidfRecallAt10List = mutableListOf<Double>()
                val tfidfRecallAt20List = mutableListOf<Double>()
                val tfidfPurityAt10List = mutableListOf<Double>()
                val tfidfPurityAt20List = mutableListOf<Double>()

                // Retrieved pool overlaps
                val retrievedPoolMeanOverlaps = mutableListOf<Double>()
                val retrievedPoolMedianOverlaps = mutableListOf<Int>()
                val retrievedPoolMaxOverlaps = mutableListOf<Int>()

                // Dynamic reference overlaps
                val bm25RefTop10MeanOverlaps = mutableListOf<Double>()
                val bm25RefTop10MedianOverlaps = mutableListOf<Int>()
                val bm25RefTop10MaxOverlaps = mutableListOf<Int>()

                val bm25RefTop20MeanOverlaps = mutableListOf<Double>()
                val bm25RefTop20MedianOverlaps = mutableListOf<Int>()
                val bm25RefTop20MaxOverlaps = mutableListOf<Int>()

                val tfidfRefTop10MeanOverlaps = mutableListOf<Double>()
                val tfidfRefTop10MedianOverlaps = mutableListOf<Int>()
                val tfidfRefTop10MaxOverlaps = mutableListOf<Int>()

                val tfidfRefTop20MeanOverlaps = mutableListOf<Double>()
                val tfidfRefTop20MedianOverlaps = mutableListOf<Int>()
                val tfidfRefTop20MaxOverlaps = mutableListOf<Int>()

                for (q in probeQueries) {
                    val poolResult = poolMap.getValue(q)
                    val candidates = poolResult.candidates
                    val actualCount = candidates.size
                    actualCounts.add(actualCount.toDouble())
                    if (poolResult.isPoolExhausted) poolExhaustedCount++

                    val ref = queryReferences.getValue(q.positionId)
                    val universeSize = max(1, ref.universeSize)
                    val crr = 1.0 - (actualCount.toDouble() / universeSize)
                    crrActualList.add(crr)

                    val candidateIds = candidates.map { it.positionId }.toSet()

                    // BM25 recall & purity
                    val bm25Hits10 = candidateIds.intersect(ref.bm25Top10).size
                    val bm25Hits20 = candidateIds.intersect(ref.bm25Top20).size
                    bm25RecallAt10List.add(bm25Hits10 / 10.0)
                    bm25RecallAt20List.add(bm25Hits20 / 20.0)
                    bm25PurityAt10List.add(if (actualCount > 0) bm25Hits10.toDouble() / actualCount else 0.0)
                    bm25PurityAt20List.add(if (actualCount > 0) bm25Hits20.toDouble() / actualCount else 0.0)

                    // TF-IDF recall & purity
                    val tfidfHits10 = candidateIds.intersect(ref.tfidfTop10).size
                    val tfidfHits20 = candidateIds.intersect(ref.tfidfTop20).size
                    tfidfRecallAt10List.add(tfidfHits10 / 10.0)
                    tfidfRecallAt20List.add(tfidfHits20 / 20.0)
                    tfidfPurityAt10List.add(if (actualCount > 0) tfidfHits10.toDouble() / actualCount else 0.0)
                    tfidfPurityAt20List.add(if (actualCount > 0) tfidfHits20.toDouble() / actualCount else 0.0)

                    // Overlaps
                    val qClosure = when (repType) {
                        "ATTACK_CLOSURE" -> attackTermsByDoc[q] ?: emptySet()
                        "DEFENSE_CLOSURE" -> defenseTermsByDoc[q] ?: emptySet()
                        else -> combinedTermsByDoc[q] ?: emptySet()
                    }

                    // Retrieved candidates overlap
                    val candOverlaps = candidates.map { c ->
                        val cClosure = when (repType) {
                            "ATTACK_CLOSURE" -> attackTermsByDoc[c] ?: emptySet()
                            "DEFENSE_CLOSURE" -> defenseTermsByDoc[c] ?: emptySet()
                            else -> combinedTermsByDoc[c] ?: emptySet()
                        }
                        qClosure.intersect(cClosure).size
                    }
                    if (candOverlaps.isNotEmpty()) {
                        val sortedOverlaps = candOverlaps.sorted()
                        retrievedPoolMeanOverlaps.add(sortedOverlaps.average())
                        retrievedPoolMedianOverlaps.add(sortedOverlaps[sortedOverlaps.size / 2])
                        retrievedPoolMaxOverlaps.add(sortedOverlaps.last())
                    } else {
                        retrievedPoolMeanOverlaps.add(0.0)
                        retrievedPoolMedianOverlaps.add(0)
                        retrievedPoolMaxOverlaps.add(0)
                    }

                    // Dynamic reference overlap helper
                    fun computeTargetOverlaps(targetIds: Set<java.util.UUID>): Triple<Double, Int, Int> {
                        val targetOverlaps = targetIds.mapNotNull { id ->
                            corpusDocs.firstOrNull { it.positionId == id }?.let { targetDoc ->
                                val targetClosure = when (repType) {
                                    "ATTACK_CLOSURE" -> attackTermsByDoc[targetDoc] ?: emptySet()
                                    "DEFENSE_CLOSURE" -> defenseTermsByDoc[targetDoc] ?: emptySet()
                                    else -> combinedTermsByDoc[targetDoc] ?: emptySet()
                                }
                                qClosure.intersect(targetClosure).size
                            }
                        }
                        if (targetOverlaps.isEmpty()) return Triple(0.0, 0, 0)
                        val sorted = targetOverlaps.sorted()
                        return Triple(sorted.average(), sorted[sorted.size / 2], sorted.last())
                    }

                    val bm25O10 = computeTargetOverlaps(ref.bm25Top10)
                    bm25RefTop10MeanOverlaps.add(bm25O10.first)
                    bm25RefTop10MedianOverlaps.add(bm25O10.second)
                    bm25RefTop10MaxOverlaps.add(bm25O10.third)

                    val bm25O20 = computeTargetOverlaps(ref.bm25Top20)
                    bm25RefTop20MeanOverlaps.add(bm25O20.first)
                    bm25RefTop20MedianOverlaps.add(bm25O20.second)
                    bm25RefTop20MaxOverlaps.add(bm25O20.third)

                    val tfidfO10 = computeTargetOverlaps(ref.tfidfTop10)
                    tfidfRefTop10MeanOverlaps.add(tfidfO10.first)
                    tfidfRefTop10MedianOverlaps.add(tfidfO10.second)
                    tfidfRefTop10MaxOverlaps.add(tfidfO10.third)

                    val tfidfO20 = computeTargetOverlaps(ref.tfidfTop20)
                    tfidfRefTop20MeanOverlaps.add(tfidfO20.first)
                    tfidfRefTop20MedianOverlaps.add(tfidfO20.second)
                    tfidfRefTop20MaxOverlaps.add(tfidfO20.third)
                }

                // Jaccard overlap for Attack Closure against Defense Closure
                val jaccardOverlap = if (repType == "ATTACK_CLOSURE") {
                    val c1Pools = poolMap
                    val c2Pools = candidatePools.getValue("SYSTEM_C2_DEFENSE_CLOSURE").getValue(b)
                    probeQueries.map { q ->
                        val c1Set = c1Pools.getValue(q).candidates.map { it.positionId }.toSet()
                        val c2Set = c2Pools.getValue(q).candidates.map { it.positionId }.toSet()
                        val unionSize = c1Set.union(c2Set).size
                        if (unionSize > 0) {
                            c1Set.intersect(c2Set).size.toDouble() / unionSize
                        } else {
                            1.0
                        }
                    }.average()
                } else null

                // Incremental gains for Combined Closure over best single variant
                var bm25Gain10: Double? = null
                var bm25Gain20: Double? = null
                var tfidfGain10: Double? = null
                var tfidfGain20: Double? = null

                if (repType == "COMBINED_CLOSURE") {
                    val c1Map = candidatePools.getValue("SYSTEM_C1_ATTACK_CLOSURE").getValue(b)
                    val c2Map = candidatePools.getValue("SYSTEM_C2_DEFENSE_CLOSURE").getValue(b)

                    fun meanRecall(pool: Map<PositionDocument, CandidatePoolResult>, isBm25: Boolean, k: Int): Double {
                        return probeQueries.map { q ->
                            val cands = pool.getValue(q).candidates.map { it.positionId }.toSet()
                            val ref = queryReferences.getValue(q.positionId)
                            val target = if (isBm25) (if (k == 10) ref.bm25Top10 else ref.bm25Top20)
                            else (if (k == 10) ref.tfidfTop10 else ref.tfidfTop20)
                            cands.intersect(target).size.toDouble() / k
                        }.average()
                    }

                    val c3Bm25R10 = bm25RecallAt10List.average()
                    val c1Bm25R10 = meanRecall(c1Map, true, 10)
                    val c2Bm25R10 = meanRecall(c2Map, true, 10)
                    bm25Gain10 = c3Bm25R10 - maxOf(c1Bm25R10, c2Bm25R10)

                    val c3Bm25R20 = bm25RecallAt20List.average()
                    val c1Bm25R20 = meanRecall(c1Map, true, 20)
                    val c2Bm25R20 = meanRecall(c2Map, true, 20)
                    bm25Gain20 = c3Bm25R20 - maxOf(c1Bm25R20, c2Bm25R20)

                    val c3TfidfR10 = tfidfRecallAt10List.average()
                    val c1TfidfR10 = meanRecall(c1Map, false, 10)
                    val c2TfidfR10 = meanRecall(c2Map, false, 10)
                    tfidfGain10 = c3TfidfR10 - maxOf(c1TfidfR10, c2TfidfR10)

                    val c3TfidfR20 = tfidfRecallAt20List.average()
                    val c1TfidfR20 = meanRecall(c1Map, false, 20)
                    val c2TfidfR20 = meanRecall(c2Map, false, 20)
                    tfidfGain20 = c3TfidfR20 - maxOf(c1TfidfR20, c2TfidfR20)
                }

                val nQ = probeQueries.size
                val budgetMetrics = BudgetSystemMetrics(
                    requestedBudget = b,
                    actualMeanCandidateCount = actualCounts.average(),
                    positiveCandidatePoolExhaustionRate = (poolExhaustedCount.toDouble() / nQ) * 100.0,
                    actualMeanCandidateReductionRate = crrActualList.average(),
                    nominalCandidateReductionRate = 1.0 - (b.toDouble() / 5001.80),
                    bm25Metrics = ConnectivityScorerMetrics(
                        recallAt10 = ConnectivityDistributionCalculator.calculate(bm25RecallAt10List),
                        recallAt20 = ConnectivityDistributionCalculator.calculate(bm25RecallAt20List),
                        purityAt10 = ConnectivityDistributionCalculator.calculate(bm25PurityAt10List),
                        purityAt20 = ConnectivityDistributionCalculator.calculate(bm25PurityAt20List),
                        zeroRecallCountAt10 = bm25RecallAt10List.count { it == 0.0 },
                        zeroRecallPercentageAt10 = (bm25RecallAt10List.count { it == 0.0 }.toDouble() / nQ) * 100.0,
                        zeroRecallCountAt20 = bm25RecallAt20List.count { it == 0.0 },
                        zeroRecallPercentageAt20 = (bm25RecallAt20List.count { it == 0.0 }.toDouble() / nQ) * 100.0,
                    ),
                    tfidfMetrics = ConnectivityScorerMetrics(
                        recallAt10 = ConnectivityDistributionCalculator.calculate(tfidfRecallAt10List),
                        recallAt20 = ConnectivityDistributionCalculator.calculate(tfidfRecallAt20List),
                        purityAt10 = ConnectivityDistributionCalculator.calculate(tfidfPurityAt10List),
                        purityAt20 = ConnectivityDistributionCalculator.calculate(tfidfPurityAt20List),
                        zeroRecallCountAt10 = tfidfRecallAt10List.count { it == 0.0 },
                        zeroRecallPercentageAt10 = (tfidfRecallAt10List.count { it == 0.0 }.toDouble() / nQ) * 100.0,
                        zeroRecallCountAt20 = tfidfRecallAt20List.count { it == 0.0 },
                        zeroRecallPercentageAt20 = (tfidfRecallAt20List.count { it == 0.0 }.toDouble() / nQ) * 100.0,
                    ),
                    unweightedOverlapDiagnostic = OverlapDiagnosticStats(
                        meanRetrievedOverlap = retrievedPoolMeanOverlaps.average(),
                        medianRetrievedOverlap = retrievedPoolMedianOverlaps.sorted()[nQ / 2],
                        maxRetrievedOverlap = retrievedPoolMaxOverlaps.maxOrNull() ?: 0,
                        bm25ReferenceTop10Overlap = ReferenceOverlapDistribution(
                            mean = bm25RefTop10MeanOverlaps.average(),
                            median = bm25RefTop10MedianOverlaps.sorted()[nQ / 2],
                            max = bm25RefTop10MaxOverlaps.maxOrNull() ?: 0,
                        ),
                        bm25ReferenceTop20Overlap = ReferenceOverlapDistribution(
                            mean = bm25RefTop20MeanOverlaps.average(),
                            median = bm25RefTop20MedianOverlaps.sorted()[nQ / 2],
                            max = bm25RefTop20MaxOverlaps.maxOrNull() ?: 0,
                        ),
                        tfidfReferenceTop10Overlap = ReferenceOverlapDistribution(
                            mean = tfidfRefTop10MeanOverlaps.average(),
                            median = tfidfRefTop10MedianOverlaps.sorted()[nQ / 2],
                            max = tfidfRefTop10MaxOverlaps.maxOrNull() ?: 0,
                        ),
                        tfidfReferenceTop20Overlap = ReferenceOverlapDistribution(
                            mean = tfidfRefTop20MeanOverlaps.average(),
                            median = tfidfRefTop20MedianOverlaps.sorted()[nQ / 2],
                            max = tfidfRefTop20MaxOverlaps.maxOrNull() ?: 0,
                        ),
                    ),
                    overlapWithDefenseClosureJaccard = jaccardOverlap,
                    bm25IncrementalRecallGainAt10OverBestSingle = bm25Gain10,
                    bm25IncrementalRecallGainAt20OverBestSingle = bm25Gain20,
                    tfidfIncrementalRecallGainAt10OverBestSingle = tfidfGain10,
                    tfidfIncrementalRecallGainAt20OverBestSingle = tfidfGain20,
                )

                budgetResults["budget_$b"] = budgetMetrics
            }

            systemResults[systemId] = ConnectivitySystemRecord(
                systemId = systemId,
                representationType = repType,
                budgetResults = budgetResults,
            )
        }

        // 8. Ingest Baseline Comparisons (§5.4.1)
        println("Ingesting 18 baseline configurations from ${baselineResultsFile.name}...")
        val baselineComparisons = ingestBaselineComparisons(baselineResultsFile)

        // 9. Assemble final result
        val metadata = ExperimentMetadata(
            experimentId = "EXP-ISSUE-722-CONNECTIVITY-CLOSURE-ABLATION",
            timestamp = Instant.now().toString(),
            gitCommit = "c3f672e95ed5be3ed38a93a2ff64af424e1b920e",
            corpusSize = corpusDocs.size,
            queryCount = probeQueries.size,
            meanCandidateUniverseSize = probeQueries.map { queryReferences.getValue(it.positionId).universeSize }.average(),
            zeroEngineSearchesGuaranteed = true,
            zeroProductionChangesGuaranteed = true,
        )

        return ConnectivityAblationResult(
            metadata = metadata,
            vocabularyStatistics = vocabularyStatistics,
            diagnosticResults = diagnosticResults,
            systemResults = systemResults,
            baselineComparisons = baselineComparisons,
        )
    }

    private fun computeVocabularySummary(termsCollection: Collection<Set<String>>): VocabularySummary {
        val allTerms = termsCollection.flatten().toSet()
        val lengths = termsCollection.map { it.size }.sorted()
        val emptyCount = lengths.count { it == 0 }
        val n = lengths.size
        return VocabularySummary(
            vocabularySize = allTerms.size,
            avgDocumentLength = if (n > 0) lengths.average() else 0.0,
            medianDocumentLength = if (n > 0) lengths[n / 2] else 0,
            maxDocumentLength = lengths.lastOrNull() ?: 0,
            emptyDocumentCount = emptyCount,
            emptyDocumentPercentage = if (n > 0) (emptyCount.toDouble() / n) * 100.0 else 0.0,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun ingestBaselineComparisons(baselineFile: File): Map<String, BaselineComparisonRecord> {
        val root: Map<String, Any> = mapper.readValue(baselineFile)
        val sysResults = root["systemResults"] as Map<String, Any>

        fun extractRecord(
            systemKey: String,
            alphaKey: String,
            targetSystemId: String,
            archFamily: String,
            budget: Int?,
        ): BaselineComparisonRecord {
            val sysObj = sysResults[systemKey] as Map<String, Any>
            val alphaResults = sysObj["alphaResults"] as Map<String, Any>
            val alphaObj = alphaResults[alphaKey] as Map<String, Any>
            val meanCandCount = (alphaObj["meanCandidateCount"] as Number).toDouble()
            val crr = (alphaObj["candidateReductionRate"] as Number).toDouble()

            val bm25 = alphaObj["bm25Metrics"] as Map<String, Any>
            val tfidf = alphaObj["tfidfMetrics"] as Map<String, Any>

            val bm25R10 = (bm25["dynamicRecallAt10"] as Number).toDouble()
            val bm25R20 = (bm25["dynamicRecallAt20"] as Number).toDouble()
            val tfidfR10 = (tfidf["dynamicRecallAt10"] as Number).toDouble()
            val tfidfR20 = (tfidf["dynamicRecallAt20"] as Number).toDouble()

            return BaselineComparisonRecord(
                systemId = targetSystemId,
                architectureFamily = archFamily,
                budget = budget,
                meanCandidateCount = meanCandCount,
                candidateReductionRate = crr,
                bm25RecallAt10 = bm25R10,
                bm25RecallAt20 = bm25R20,
                tfidfRecallAt10 = tfidfR10,
                tfidfRecallAt20 = tfidfR20,
            )
        }

        val records = mutableMapOf<String, BaselineComparisonRecord>()

        // 1. System 2A (alpha=0.60, 5 budgets)
        listOf(50, 80, 200, 500, 1000).forEach { b ->
            records["SYSTEM_2A_ALPHA_0.60_N$b"] = extractRecord(
                systemKey = "SYSTEM_2A_DIRECT_INDEX_N$b",
                alphaKey = "alpha_0.6",
                targetSystemId = "SYSTEM_2A_ALPHA_0.60_N$b",
                archFamily = "STATIC_INVERTED_INDEX",
                budget = b
            )
        }

        // 2. System 2A (alpha=0.00, 5 budgets)
        listOf(50, 80, 200, 500, 1000).forEach { b ->
            records["SYSTEM_2A_ALPHA_0.00_N$b"] = extractRecord(
                systemKey = "SYSTEM_2A_DIRECT_INDEX_N$b",
                alphaKey = "alpha_0.0",
                targetSystemId = "SYSTEM_2A_ALPHA_0.00_N$b",
                archFamily = "STATIC_INVERTED_INDEX_PAWNLESS",
                budget = b
            )
        }

        // 3. System 2B (alpha=0.60, 5 budgets)
        listOf(50, 80, 200, 500, 1000).forEach { b ->
            records["SYSTEM_2B_ALPHA_0.60_N$b"] = extractRecord(
                systemKey = "SYSTEM_2B_STATIC_COSINE_N$b",
                alphaKey = "alpha_0.6",
                targetSystemId = "SYSTEM_2B_ALPHA_0.60_N$b",
                archFamily = "EXHAUSTIVE_STATIC_COSINE",
                budget = b
            )
        }

        // 4. Clustered Baselines (alpha=0.60, 2 systems)
        records["SYSTEM_3_ALPHA_0.60"] = extractRecord(
            systemKey = "SYSTEM_3_PRODUCTION_CLUSTERED_BASELINE",
            alphaKey = "alpha_0.6",
            targetSystemId = "SYSTEM_3_ALPHA_0.60",
            archFamily = "PRODUCTION_CLUSTERED_BASELINE",
            budget = null
        )
        records["SYSTEM_4D_ALPHA_0.60"] = extractRecord(
            systemKey = "SYSTEM_4D_SIDE_CONSTRAINED_CLUSTERED",
            alphaKey = "alpha_0.6",
            targetSystemId = "SYSTEM_4D_ALPHA_0.60",
            archFamily = "SIDE_CONSTRAINED_CLUSTERED_PIPELINE",
            budget = null
        )

        // 5. System 1 (Reference Standard Baseline)
        records["SYSTEM_1_DYNAMIC_REFERENCE"] = BaselineComparisonRecord(
            systemId = "SYSTEM_1_DYNAMIC_REFERENCE",
            architectureFamily = "DYNAMIC_REFERENCE_STANDARD",
            budget = null,
            meanCandidateCount = 5001.80,
            candidateReductionRate = 0.0,
            bm25RecallAt10 = 1.0,
            bm25RecallAt20 = 1.0,
            tfidfRecallAt10 = 1.0,
            tfidfRecallAt20 = 1.0,
        )

        return records
    }
}

object ConnectivityDistributionCalculator {
    fun calculate(values: List<Double>): ConnectivityMetricDistribution {
        if (values.isEmpty()) {
            return ConnectivityMetricDistribution(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val sorted = values.sorted()
        val n = sorted.size
        val mean = sorted.average()
        val variance = sorted.sumOf { (it - mean) * (it - mean) } / n
        val stdDev = kotlin.math.sqrt(variance)
        return ConnectivityMetricDistribution(
            mean = mean,
            median = sorted[n / 2],
            stdDev = stdDev,
            min = sorted.first(),
            p25 = sorted[(n * 0.25).toInt().coerceAtMost(n - 1)],
            p75 = sorted[(n * 0.75).toInt().coerceAtMost(n - 1)],
            p90 = sorted[(n * 0.90).toInt().coerceAtMost(n - 1)],
            max = sorted.last(),
        )
    }
}
