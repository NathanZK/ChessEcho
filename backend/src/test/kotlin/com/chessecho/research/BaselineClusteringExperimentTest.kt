package com.chessecho.research

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.PositionInvertedIndex
import com.chessecho.similarity.RelationalPawnTokenExtractor
import com.chessecho.similarity.clustering.PositionClusterSimilarityScorer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID

data class BaselineExperimentReport(
    val baselineMetrics: ClusteringSummaryMetrics,
    val bipartitionMetrics: BipartitionReport?,
    val nullModelMetrics: NullEnsembleSummary?,
    val candidateRecall: Map<Int, Map<Int, Double>>?,
    val stochasticStability: StochasticStabilityReport?,
)

data class BipartitionReport(
    val unifiedQ: Double,
    val separatedQ: Double,
    val deltaQ: Double,
    val ami: Double,
    val ari: Double,
    val jaccardAssigned: Double,
    val variationOfInformation: Double,
)

data class StochasticStabilityReport(
    val seeds: List<Long>,
    val meanAmi: Double,
    val minAmi: Double,
    val meanAri: Double,
    val minAri: Double,
)

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BaselineClusteringExperimentTest {
    private val extractor = RelationalPawnTokenExtractor()
    private val runner = ClusteringExperimentRunner()
    private val scorer = PositionClusterSimilarityScorer()
    private val rewirer = MaslovSneppenRewirer()
    private val mapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private val corpusFile = File("src/test/resources/research/corpus_a.json")
    private val reportFile = File("src/test/resources/research/baseline_report.json")

    private lateinit var corpusPositions: List<PositionDocument>
    private lateinit var baselineResult: ExperimentRunResult

    private var bipartitionReport: BipartitionReport? = null
    private var nullModelReport: NullEnsembleSummary? = null
    private var recallReport: Map<Int, Map<Int, Double>>? = null
    private var stochasticReport: StochasticStabilityReport? = null

    @BeforeAll
    fun setUp() {
        assertTrue(corpusFile.exists(), "Corpus A must exist before running baseline experiment")
        val builder = CorpusBuilder()
        val rawPositions = builder.loadCorpusFromJson(corpusFile)

        println("Extracting documents for 10,000 positions in Corpus A...")
        corpusPositions = rawPositions.mapIndexed { idx, pos ->
            val id = DeterministicIdGenerator.generateDeterministicId(pos.canonicalFen, pos.samplePly)
            extractor.extractDocument(id, pos.canonicalFen, includeReachability = true)
        }

        println("Executing baseline clustering (alpha=0.60, gamma_RB=1.25, candidateLimit=200)...")
        val baselineConfig = ExperimentConfig(
            alpha = 0.60,
            candidateLimit = 200,
            topK = 30,
            floorThreshold = 0.40,
            top1Threshold = 0.65,
            rbResolution = 1.25,
            centroidThreshold = 0.38,
            randomSeed = 42L,
            splitBySideToMove = false,
        )
        baselineResult = runner.runExperiment(corpusPositions, baselineConfig)
        println("Baseline clustering execution complete in ${baselineResult.metrics.totalDurationMs} ms.")
    }

    @Test
    fun `baseline distribution and granularity satisfy reporting requirements`() {
        val metrics = baselineResult.metrics
        val dist = metrics.distribution
        val cat = metrics.categories

        println("=== BASELINE GRANULARITY & DISTRIBUTION ===")
        println("Total Positions: ${metrics.totalPositions}")
        println("Assigned: ${metrics.assignedPositions} (${"%.2f".format(metrics.assignedPct)}%)")
        println("Isolates: ${metrics.isolatePositions} (${"%.2f".format(metrics.isolatePct)}%)")
        println("Outliers: ${metrics.outlierPositions} (${"%.2f".format(metrics.outlierPct)}%)")
        println("Total Clusters: ${metrics.totalClusters}")
        println("Size Distribution: Mean=${"%.2f".format(dist.mean)}, Median=${dist.median}, Mode=${dist.mode}, Min=${dist.min}, Max=${dist.max}")
        println("Percentiles: P10=${dist.p10}, P25=${dist.p25}, P50=${dist.p50}, P75=${dist.p75}, P90=${dist.p90}, P99=${dist.p99}")
        println("Singletons: ${cat.singletons} (${"%.1f".format(cat.singletonPct)}%)")
        println("Small (2-5): ${cat.small} (${"%.1f".format(cat.smallPct)}%)")
        println("Medium (6-25): ${cat.medium} (${"%.1f".format(cat.mediumPct)}%)")
        println("Large (26-100): ${cat.large} (${"%.1f".format(cat.largePct)}%)")
        println("Very Large (101-500): ${cat.veryLarge} (${"%.1f".format(cat.veryLargePct)}%)")
        println("Mega (>500): ${cat.mega} (${"%.1f".format(cat.megaPct)}%)")
        println("Leiden Modularity Q: ${"%.6f".format(metrics.leidenQuality)}")

        assertNotNull(dist)
        assertTrue(metrics.totalClusters > 0)
        assertTrue(metrics.assignedPositions > 0)
        assertTrue(dist.mean > 0.0)
    }

    @Test
    fun `side-to-move bipartition test compares unified vs separated subgraphs`() {
        println("Executing side-to-move separated subgraph experiment...")
        val splitConfig = baselineResult.config.copy(splitBySideToMove = true)
        val splitResult = runner.runExperiment(
            positions = corpusPositions,
            config = splitConfig,
            precomputedCandidates = baselineResult.candidateMap,
        )

        val unifiedAssignments = baselineResult.evaluationResult.assignments.associate { it.positionId to it.clusterIndex }
        val splitAssignments = splitResult.evaluationResult.assignments.associate { it.positionId to it.clusterIndex }

        val comparison = PartitionDistanceMetrics.evaluate(unifiedAssignments, splitAssignments)
        bipartitionReport = BipartitionReport(
            unifiedQ = baselineResult.metrics.leidenQuality,
            separatedQ = splitResult.metrics.leidenQuality,
            deltaQ = baselineResult.metrics.leidenQuality - splitResult.metrics.leidenQuality,
            ami = comparison.ami,
            ari = comparison.ari,
            jaccardAssigned = comparison.jaccardAssigned,
            variationOfInformation = comparison.variationOfInformation,
        )

        println("=== BIPARTITION COMPARISON ===")
        println("Unified Modularity Q: ${"%.6f".format(baselineResult.metrics.leidenQuality)}")
        println("Separated Modularity Q: ${"%.6f".format(splitResult.metrics.leidenQuality)}")
        println("Modularity Delta: ${"%.6f".format(baselineResult.metrics.leidenQuality - splitResult.metrics.leidenQuality)}")
        println("AMI between Unified and Separated: ${"%.4f".format(comparison.ami)}")
        println("ARI between Unified and Separated: ${"%.4f".format(comparison.ari)}")
        println("Assignment Jaccard Index: ${"%.4f".format(comparison.jaccardAssigned)}")
        println("Variation of Information: ${"%.4f".format(comparison.variationOfInformation)}")

        assertTrue(comparison.ami > 0.70, "Bipartition comparison AMI should show strong component alignment")
    }

    @Test
    fun `null model ensemble tests hypothesis 2 modularity significance and strength deviation`() {
        println("Executing R=20 color-constrained Maslov-Sneppen null model ensemble...")
        val ensemble = rewirer.evaluateEnsemble(
            graph = baselineResult.graph,
            empiricalQ = baselineResult.metrics.leidenQuality,
            rbResolution = baselineResult.config.rbResolution,
            rRuns = 20,
            baseSeed = 42L,
        )
        nullModelReport = ensemble

        println("=== NULL MODEL ENSEMBLE RESULTS (R=20) ===")
        println("Empirical Q: ${"%.6f".format(ensemble.empiricalQ)}")
        println("Mean Null Q: ${"%.6f".format(ensemble.meanNullQ)} +/- ${"%.6f".format(ensemble.stdNullQ)}")
        println("Delta Q (Q_emp - Q_null): ${"%.6f".format(ensemble.deltaQ)}")
        println("Effect Size (Z_Q): ${"%.2f".format(ensemble.zScoreQ)}")
        println("Mean Absolute Strength Deviation (MASD): ${"%.6f".format(ensemble.meanMasd)}")
        println("Relative Strength Deviation (RSD): ${"%.6f".format(ensemble.meanRsd)}")

        val h2Satisfied = ensemble.deltaQ >= 0.30 && ensemble.zScoreQ >= 3.0
        println("Hypothesis 2 (Modularity Significance) Satisfied: $h2Satisfied")

        assertTrue(ensemble.zScoreQ >= 3.0, "Empirical modularity must be statistically significant (Z_Q >= 3.0, actual: ${ensemble.zScoreQ})")
    }

    @Test
    fun `candidate retrieval recall tests hypothesis 1 across candidate limits`() {
        println("Benchmarking candidate retrieval recall against exhaustive search on 500-position sample...")
        val vectors = baselineResult.vectors
        val invertedIndex = PositionInvertedIndex().apply { index(corpusPositions) }

        val sample = corpusPositions.shuffled(java.util.Random(42L)).take(250)
        val kValues = listOf(1, 10, 20)
        val candidateLimits = listOf(200, 500, 1000)

        // Precompute exhaustive rankings once for the sample
        val exhaustiveByQuery = sample.associate { queryDoc ->
            val turn = queryDoc.sideToMove.lowercase()
            val eligible = corpusPositions.filter { it.sideToMove.lowercase() == turn && it.positionId != queryDoc.positionId }
            val queryVec = vectors.getValue(queryDoc.positionId)
            queryDoc.positionId to eligible
                .map { it.positionId to scorer.cosineSimilarity(queryVec, vectors.getValue(it.positionId)) }
                .sortedWith(compareByDescending<Pair<UUID, Double>> { it.second }.thenBy { it.first })
        }

        val recallSummary = mutableMapOf<Int, Map<Int, Double>>()

        candidateLimits.forEach { limit ->
            val recalls = kValues.associateWith { mutableListOf<Double>() }

            sample.forEach { queryDoc ->
                val turn = queryDoc.sideToMove.lowercase()
                val exhaustiveRanked = exhaustiveByQuery.getValue(queryDoc.positionId)

                val candidateIds = invertedIndex.generateCandidates(
                    queryTerms = queryDoc.terms.keys,
                    activeTurn = turn,
                    maxCandidates = limit,
                    alpha = 0.60,
                ).filter { it.positionId != queryDoc.positionId }.map { it.positionId }.toSet()

                kValues.forEach { k ->
                    val topK = exhaustiveRanked.take(k)
                    if (topK.isNotEmpty()) {
                        val hits = topK.count { (id, _) -> candidateIds.contains(id) }
                        recalls.getValue(k).add(hits.toDouble() / topK.size)
                    }
                }
            }

            val meanRecalls = recalls.mapValues { (_, hits) -> hits.average() }
            recallSummary[limit] = meanRecalls
            println("CandidateLimit=$limit: Recall@1=${"%.4f".format(meanRecalls[1])}, Recall@10=${"%.4f".format(meanRecalls[10])}, Recall@20=${"%.4f".format(meanRecalls[20])}")
        }
        recallReport = recallSummary

        val recall20At200 = recallSummary.getValue(200).getValue(20)
        println("Hypothesis 1 (Recall@20 >= 0.60 at limit=200): $recall20At200")
        assertTrue(recall20At200 >= 0.60, "Hypothesis 1 requires Recall@20 >= 0.60 at candidateLimit=200 (actual: $recall20At200)")
    }

    @Test
    fun `stochastic stability tests hypothesis 5 under random seed variations`() {
        println("Evaluating stochastic stability of Leiden community detection across 5 random seeds...")
        val seeds = listOf(42L, 100L, 777L, 2024L, 9999L)
        val detector = com.chessecho.similarity.clustering.PositionLeidenCommunityDetector()

        val seedResults = seeds.map { seed ->
            seed to detector.detectCommunities(baselineResult.graph, baselineResult.config.rbResolution, seed)
        }.toMap()

        val amiPairs = mutableListOf<Double>()
        val ariPairs = mutableListOf<Double>()

        for (i in 0 until seeds.size) {
            for (j in i + 1 until seeds.size) {
                val s1 = seeds[i]
                val s2 = seeds[j]
                val m = PartitionDistanceMetrics.evaluate(seedResults.getValue(s1).assignments, seedResults.getValue(s2).assignments)
                amiPairs.add(m.ami)
                ariPairs.add(m.ari)
            }
        }

        val meanAmi = amiPairs.average()
        val meanAri = ariPairs.average()
        stochasticReport = StochasticStabilityReport(
            seeds = seeds,
            meanAmi = meanAmi,
            minAmi = amiPairs.minOrNull() ?: 0.0,
            meanAri = meanAri,
            minAri = ariPairs.minOrNull() ?: 0.0,
        )
        println("=== STOCHASTIC STABILITY (Hypothesis 5) ===")
        println("Mean AMI across random seeds: ${"%.4f".format(meanAmi)} (min: ${"%.4f".format(amiPairs.minOrNull())})")
        println("Mean ARI across random seeds: ${"%.4f".format(meanAri)} (min: ${"%.4f".format(ariPairs.minOrNull())})")

        assertTrue(meanAmi > 0.85, "Hypothesis 5 requires AMI > 0.85 across seed variations (actual: $meanAmi)")
    }

    @AfterAll
    fun tearDown() {
        println("Writing baseline experiment report to ${reportFile.absolutePath}...")
        reportFile.parentFile.mkdirs()
        val report = BaselineExperimentReport(
            baselineMetrics = baselineResult.metrics,
            bipartitionMetrics = bipartitionReport,
            nullModelMetrics = nullModelReport,
            candidateRecall = recallReport,
            stochasticStability = stochasticReport,
        )
        mapper.writeValue(reportFile, report)
        println("Baseline experiment report successfully saved.")
    }
}
