package com.chessecho.research

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.RelationalPawnTokenExtractor
import com.chessecho.similarity.clustering.PositionClusterCentroidEvaluator
import com.chessecho.similarity.clustering.PositionClusterSimilarityScorer
import com.chessecho.similarity.clustering.PositionClusterVector
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID

data class MultiDepthResearchReport(
    val totalAnalysesExecuted: Int,
    val maxBudgetAllowed: Int,
    val timeoutsEncountered: Int,
    val timeoutPct: Double,
    val passedScreeningTimeoutAudit: Boolean,
    val analysesByStratum: Map<String, Int>,
    val analysesByDepth: Map<Int, Int>,
    val hypothesis6ScreeningReliability: Double,
    val hypothesis6Satisfied: Boolean,
    val depthAgreementStats: DepthAgreementSummary,
    val samplingBenchmarks: Map<SamplingStrategy, SamplingStrategyBenchmark>,
    val stratum1Clusters: List<Int>,
)

data class DepthAgreementSummary(
    val meanAgreementD8VsD16: Double,
    val meanAgreementD12VsD16: Double,
    val meanAgreementD16VsD20: Double,
    val meanEvalDiffD8VsD16: Double,
    val meanEvalDiffD12VsD16: Double,
    val meanEvalDiffD16VsD20: Double,
    val volatilePositionsPctD8VsD16: Double, // |eval diff| > 100 cp
    val volatilePositionsPctD16VsD20: Double,
)

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiDepthEngineAnalysisTest {
    private val extractor = RelationalPawnTokenExtractor()
    private val runner = ClusteringExperimentRunner()
    private val scorer = PositionClusterSimilarityScorer()
    private val samplingEvaluator = SamplingStrategyEvaluator()
    private val mapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    private val corpusFile = File("src/test/resources/research/corpus_a.json")
    private val outputFile = File("src/test/resources/research/multi_depth_engine_report.json")

    private lateinit var corpusPositions: List<PositionDocument>
    private lateinit var vectors: Map<UUID, PositionClusterVector>
    private lateinit var clusterAssignments: Map<UUID, Int>
    private lateinit var clustersByIndex: Map<Int, List<UUID>>
    private lateinit var centroidSimilarities: Map<UUID, Double>

    private val evaluationsByDepth = mutableMapOf<Int, MutableMap<UUID, EngineEvaluation>>()
    private val stratumCounts = mutableMapOf<String, Int>()

    @BeforeAll
    fun setUp() {
        assertTrue(corpusFile.exists(), "Corpus A must exist")
        val builder = CorpusBuilder()
        val rawPositions = builder.loadCorpusFromJson(corpusFile)

        println("Extracting documents and running baseline clustering to establish cluster census...")
        corpusPositions = rawPositions.mapIndexed { _, pos ->
            val id = DeterministicIdGenerator.generateDeterministicId(pos.canonicalFen, pos.samplePly)
            extractor.extractDocument(id, pos.canonicalFen, includeReachability = true)
        }

        val baselineConfig = ExperimentConfig(
            alpha = 0.60,
            candidateLimit = 200,
            topK = 30,
            floorThreshold = 0.40,
            top1Threshold = 0.65,
            rbResolution = 1.25,
            centroidThreshold = 0.38,
            randomSeed = 42L,
        )
        val baselineResult = runner.runExperiment(corpusPositions, baselineConfig)
        vectors = baselineResult.vectors

        val assigned = baselineResult.evaluationResult.assignments.filter { it.clusterIndex != null }
        clusterAssignments = assigned.associate { it.positionId to it.clusterIndex!! }
        clustersByIndex = clusterAssignments.entries.groupBy({ it.value }, { it.key })
        centroidSimilarities = assigned.associate { it.positionId to (it.centroidSimilarity ?: 0.0) }

        println("Setup complete. Found ${clustersByIndex.size} clusters. Ready for multi-depth analysis.")
    }

    @Test
    fun `execute multi-depth analysis within strict 1500 budget and benchmark sampling strategies`() {
        val engineRunner = StockfishResearchRunner(maxTotalBudget = 1500)
        val posById = corpusPositions.associateBy { it.positionId }

        engineRunner.use { engine ->
            // Initialize depth storage
            listOf(8, 12, 16, 20).forEach { evaluationsByDepth[it] = mutableMapOf() }

            // -------------------------------------------------------------
            // STRATUM 1: 5-Cluster Complete Census Validation
            // -------------------------------------------------------------
            // Select 5 diverse clusters matching spec (1 large ~45, 2 medium ~25, 2 small ~15)
            val sortedClusters = clustersByIndex.entries.sortedByDescending { it.value.size }
            val largeCluster = sortedClusters.minByOrNull { kotlin.math.abs(it.value.size - 45) }!!.key
            val remainingAfterLarge = sortedClusters.filterNot { it.key == largeCluster }
            val mediumClusters = remainingAfterLarge.sortedBy { kotlin.math.abs(it.value.size - 25) }.take(2).map { it.key }
            val remainingAfterMedium = remainingAfterLarge.filterNot { mediumClusters.contains(it.key) }
            val smallClusters = remainingAfterMedium.sortedBy { kotlin.math.abs(it.value.size - 15) }.take(2).map { it.key }

            val stratum1Clusters = listOf(largeCluster) + mediumClusters + smallClusters
            val stratum1Positions = stratum1Clusters.flatMap { clustersByIndex.getValue(it) }.distinct()
            println("Stratum 1: Selected 5 clusters (sizes: ${stratum1Clusters.map { clustersByIndex.getValue(it).size }}) with total ${stratum1Positions.size} positions for complete census.")

            var stratum1Analyses = 0

            // Depths 8, 12, 16 for all Stratum 1 positions
            for (posId in stratum1Positions) {
                val fen = posById.getValue(posId).canonicalFen
                val ev8 = engine.evaluatePosition(fen, 8)
                val ev12 = engine.evaluatePosition(fen, 12)
                val ev16 = engine.evaluatePosition(fen, 16)

                evaluationsByDepth.getValue(8)[posId] = ev8
                evaluationsByDepth.getValue(12)[posId] = ev12
                evaluationsByDepth.getValue(16)[posId] = ev16
                stratum1Analyses += 3
            }

            // Depth 20 for top 10 positions in each of the 5 clusters
            val d20Positions = mutableListOf<UUID>()
            stratum1Clusters.forEach { cIdx ->
                val cPositions = clustersByIndex.getValue(cIdx)
                val top10 = cPositions.sortedByDescending { centroidSimilarities[it] ?: 0.0 }.take(10)
                d20Positions.addAll(top10)
            }

            for (posId in d20Positions) {
                val fen = posById.getValue(posId).canonicalFen
                val ev20 = engine.evaluatePosition(fen, 20)
                evaluationsByDepth.getValue(20)[posId] = ev20
                stratum1Analyses++
            }
            stratumCounts["Stratum1_Census"] = stratum1Analyses
            println("Stratum 1 execution complete: $stratum1Analyses analyses.")

            // -------------------------------------------------------------
            // STRATUM 2: Cluster Stability Sample
            // -------------------------------------------------------------
            val otherAssigned = clusterAssignments.keys.filterNot { stratum1Positions.contains(it) }
            val rng = java.util.Random(42L)
            val shuffledOther = otherAssigned.shuffled(rng)

            val s2Positions300 = shuffledOther.take(300)
            val s2Positions150 = s2Positions300.take(150)
            val s2Positions100 = s2Positions150.take(100)

            var stratum2Analyses = 0
            for (posId in s2Positions300) {
                val fen = posById.getValue(posId).canonicalFen
                evaluationsByDepth.getValue(8)[posId] = engine.evaluatePosition(fen, 8)
                stratum2Analyses++
            }
            for (posId in s2Positions150) {
                val fen = posById.getValue(posId).canonicalFen
                evaluationsByDepth.getValue(12)[posId] = engine.evaluatePosition(fen, 12)
                stratum2Analyses++
            }
            for (posId in s2Positions100) {
                val fen = posById.getValue(posId).canonicalFen
                evaluationsByDepth.getValue(16)[posId] = engine.evaluatePosition(fen, 16)
                stratum2Analyses++
            }
            stratumCounts["Stratum2_Stability"] = stratum2Analyses
            println("Stratum 2 execution complete: $stratum2Analyses analyses.")

            // -------------------------------------------------------------
            // STRATUM 3: Dynamic Coherence Sample
            // -------------------------------------------------------------
            val remainingForS3 = otherAssigned.filterNot { s2Positions300.contains(it) }.shuffled(rng)
            val s3Positions100 = remainingForS3.take(100)
            val s3Positions25 = s3Positions100.take(25)

            var stratum3Analyses = 0
            for (posId in s3Positions100) {
                val fen = posById.getValue(posId).canonicalFen
                evaluationsByDepth.getValue(8)[posId] = engine.evaluatePosition(fen, 8)
                evaluationsByDepth.getValue(12)[posId] = engine.evaluatePosition(fen, 12)
                stratum3Analyses += 2
            }
            for (posId in s3Positions25) {
                val fen = posById.getValue(posId).canonicalFen
                evaluationsByDepth.getValue(16)[posId] = engine.evaluatePosition(fen, 16)
                stratum3Analyses++
            }
            stratumCounts["Stratum3_Coherence"] = stratum3Analyses
            println("Stratum 3 execution complete: $stratum3Analyses analyses.")

            // -------------------------------------------------------------
            // STRATUM 4: Boundary Sensitivity Sample
            // -------------------------------------------------------------
            // Select positions whose similarity to their cluster centroid is close to threshold 0.38 (e.g. 0.38..0.45)
            val boundaryCandidates = centroidSimilarities.filter { (id, sim) ->
                sim in 0.38..0.45 && !stratum1Positions.contains(id) && !s2Positions300.contains(id)
            }.keys.toList()

            val s4Positions75 = (boundaryCandidates + remainingForS3.drop(100)).distinct().take(75)
            var stratum4Analyses = 0
            for (posId in s4Positions75) {
                val fen = posById.getValue(posId).canonicalFen
                evaluationsByDepth.getValue(8)[posId] = engine.evaluatePosition(fen, 8)
                evaluationsByDepth.getValue(12)[posId] = engine.evaluatePosition(fen, 12)
                stratum4Analyses += 2
            }
            stratumCounts["Stratum4_Boundary"] = stratum4Analyses
            println("Stratum 4 execution complete: $stratum4Analyses analyses.")

            // Verify strict budget cap
            val totalExecuted = engine.totalAnalysesExecuted.get()
            println("=== TOTAL STOCKFISH ANALYSES EXECUTED: $totalExecuted / 1500 ===")
            assertTrue(totalExecuted <= 1500, "Strict budget exceeded: $totalExecuted > 1500")

            // -------------------------------------------------------------
            // Sampling Strategy Benchmarking (Stratum 1 Census Evaluation)
            // -------------------------------------------------------------
            val d16Evals = evaluationsByDepth.getValue(16)
            val samplingResultsByCluster = stratum1Clusters.map { cIdx ->
                val cPositions = clustersByIndex.getValue(cIdx)
                val censusEvals = cPositions.associateWith { d16Evals.getValue(it).scoreCpWhite.toDouble() }

                samplingEvaluator.evaluateStrategies(
                    clusterIndex = cIdx,
                    positions = cPositions,
                    centroidSimilarities = centroidSimilarities,
                    censusEvals = censusEvals,
                )
            }

            val samplingBenchmarks = samplingEvaluator.summarizeBenchmarks(samplingResultsByCluster)
            println("=== SAMPLING STRATEGY BENCHMARKS (vs Stratum 1 Census) ===")
            samplingBenchmarks.forEach { (strat, b) ->
                println("${strat.name}: MeanN=${"%.1f".format(b.meanSampleSize)}, RMSE=${"%.2f".format(b.rootMeanSquaredErrorCp)} cp, MAE=${"%.2f".format(b.meanAbsoluteErrorCp)} cp, Bias=${"%.2f".format(b.meanBiasCp)} cp, StdDevErr=${"%.2f".format(b.stdDevEstimationErrorCp)} cp")
            }

            // -------------------------------------------------------------
            // Multi-Depth Stability & Hypothesis 6 Evaluation
            // -------------------------------------------------------------
            val commonD8D16 = evaluationsByDepth.getValue(8).keys.intersect(evaluationsByDepth.getValue(16).keys)
            val d8d16Agreement = commonD8D16.count {
                evaluationsByDepth.getValue(8)[it]?.bestMove == evaluationsByDepth.getValue(16)[it]?.bestMove
            }.toDouble() / commonD8D16.size

            val commonD12D16 = evaluationsByDepth.getValue(12).keys.intersect(evaluationsByDepth.getValue(16).keys)
            val d12d16Agreement = commonD12D16.count {
                evaluationsByDepth.getValue(12)[it]?.bestMove == evaluationsByDepth.getValue(16)[it]?.bestMove
            }.toDouble() / commonD12D16.size

            val commonD16D20 = evaluationsByDepth.getValue(16).keys.intersect(evaluationsByDepth.getValue(20).keys)
            val d16d20Agreement = commonD16D20.count {
                evaluationsByDepth.getValue(16)[it]?.bestMove == evaluationsByDepth.getValue(20)[it]?.bestMove
            }.toDouble() / commonD16D20.size

            val evalDiffsD8D16 = commonD8D16.map {
                kotlin.math.abs(evaluationsByDepth.getValue(8).getValue(it).scoreCpWhite - evaluationsByDepth.getValue(16).getValue(it).scoreCpWhite)
            }
            val evalDiffsD12D16 = commonD12D16.map {
                kotlin.math.abs(evaluationsByDepth.getValue(12).getValue(it).scoreCpWhite - evaluationsByDepth.getValue(16).getValue(it).scoreCpWhite)
            }
            val evalDiffsD16D20 = commonD16D20.map {
                kotlin.math.abs(evaluationsByDepth.getValue(16).getValue(it).scoreCpWhite - evaluationsByDepth.getValue(20).getValue(it).scoreCpWhite)
            }

            val volatilePctD8D16 = (evalDiffsD8D16.count { it > 100 }.toDouble() / evalDiffsD8D16.size) * 100.0
            val volatilePctD16D20 = (evalDiffsD16D20.count { it > 100 }.toDouble() / evalDiffsD16D20.size) * 100.0

            println("=== MULTI-DEPTH STABILITY (Hypothesis 6) ===")
            println("Best Move Agreement D8 vs D16: ${"%.2f".format(d8d16Agreement * 100)}% (sample: ${commonD8D16.size})")
            println("Best Move Agreement D12 vs D16: ${"%.2f".format(d12d16Agreement * 100)}% (sample: ${commonD12D16.size})")
            println("Best Move Agreement D16 vs D20: ${"%.2f".format(d16d20Agreement * 100)}% (sample: ${commonD16D20.size})")
            println("Mean Eval Diff D8 vs D16: ${"%.2f".format(evalDiffsD8D16.average())} cp (Volatile >100cp: ${"%.1f".format(volatilePctD8D16)}%)")
            println("Mean Eval Diff D16 vs D20: ${"%.2f".format(evalDiffsD16D20.average())} cp (Volatile >100cp: ${"%.1f".format(volatilePctD16D20)}%)")

            val h6Satisfied = d8d16Agreement >= 0.70
            println("Hypothesis 6 (Screening Reliability >= 70%): $h6Satisfied")

            val timeouts = engine.timeoutsEncountered.get()
            val timeoutPct = (timeouts.toDouble() / totalExecuted) * 100.0
            val passedTimeoutAudit = timeoutPct <= 2.0
            println("Screening Timeout Audit: $timeouts timeouts (${"%.2f".format(timeoutPct)}%, ceiling: <= 2.0%): $passedTimeoutAudit")

            // -------------------------------------------------------------
            // Save Multi-Depth Engine Report
            // -------------------------------------------------------------
            val depthCounts = mapOf(
                8 to evaluationsByDepth.getValue(8).size,
                12 to evaluationsByDepth.getValue(12).size,
                16 to evaluationsByDepth.getValue(16).size,
                20 to evaluationsByDepth.getValue(20).size,
            )

            val report = MultiDepthResearchReport(
                totalAnalysesExecuted = totalExecuted,
                maxBudgetAllowed = 1500,
                timeoutsEncountered = timeouts,
                timeoutPct = timeoutPct,
                passedScreeningTimeoutAudit = passedTimeoutAudit,
                analysesByStratum = stratumCounts,
                analysesByDepth = depthCounts,
                hypothesis6ScreeningReliability = d8d16Agreement,
                hypothesis6Satisfied = h6Satisfied,
                depthAgreementStats = DepthAgreementSummary(
                    meanAgreementD8VsD16 = d8d16Agreement,
                    meanAgreementD12VsD16 = d12d16Agreement,
                    meanAgreementD16VsD20 = d16d20Agreement,
                    meanEvalDiffD8VsD16 = evalDiffsD8D16.average(),
                    meanEvalDiffD12VsD16 = evalDiffsD12D16.average(),
                    meanEvalDiffD16VsD20 = evalDiffsD16D20.average(),
                    volatilePositionsPctD8VsD16 = volatilePctD8D16,
                    volatilePositionsPctD16VsD20 = volatilePctD16D20,
                ),
                samplingBenchmarks = samplingBenchmarks,
                stratum1Clusters = stratum1Clusters,
            )

            outputFile.parentFile.mkdirs()
            mapper.writeValue(outputFile, report)
            println("Multi-depth engine report successfully written to ${outputFile.absolutePath}.")

            assertTrue(totalExecuted <= 1500)
            assertTrue(passedTimeoutAudit, "Timeouts must be <= 2.0% (survivorship bias gate)")
        }
    }
}
