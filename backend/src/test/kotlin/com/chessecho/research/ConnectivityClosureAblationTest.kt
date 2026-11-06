package com.chessecho.research

import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.RelationalPawnTokenExtractor
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.random.Random

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectivityClosureAblationTest {

    private val mapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val corpusFile = File("src/test/resources/research/corpus_a.json")
    private val cacheFile = File("src/test/resources/research/engine_evaluation_cache_d16_mpv1.json")
    private val baselineResultsFile = File("src/test/resources/research/candidate_preservation_ablation_results.json")
    private val outputFile = File("src/test/resources/research/connectivity_closure_ablation_results.json")

    private lateinit var corpusDocs: List<PositionDocument>
    private lateinit var probeQueries: List<PositionDocument>

    @BeforeAll
    fun setUp() {
        assertTrue(corpusFile.exists(), "Corpus A file must exist: ${corpusFile.absolutePath}")
        assertTrue(cacheFile.exists(), "Engine evaluation cache must exist: ${cacheFile.absolutePath}")
        assertTrue(baselineResultsFile.exists(), "Baseline results must exist: ${baselineResultsFile.absolutePath}")

        val corpusBuilder = CorpusBuilder()
        val rawPositions = corpusBuilder.loadCorpusFromJson(corpusFile)
        val extractor = RelationalPawnTokenExtractor()

        println("Loading 10,000 GM positions from Corpus A...")
        corpusDocs = rawPositions.map { pos ->
            val id = DeterministicIdGenerator.generateDeterministicId(pos.canonicalFen, pos.samplePly)
            extractor.extractDocument(id, pos.canonicalFen, includeReachability = true)
        }
        assertEquals(10_000, corpusDocs.size, "Corpus A must have exactly 10,000 positions")

        // Exact seed 42 probe query sampling identical to Issue #722
        val rng = Random(42L)
        probeQueries = corpusDocs.shuffled(rng).take(250)
        assertEquals(250, probeQueries.size, "Probe query set must equal 250 positions")

        val whiteCount = probeQueries.count { it.fen.split("\\s+".toRegex())[1].lowercase() == "w" }
        val blackCount = probeQueries.count { it.fen.split("\\s+".toRegex())[1].lowercase() == "b" }
        assertEquals(139, whiteCount, "Must have exactly 139 White probe queries")
        assertEquals(111, blackCount, "Must have exactly 111 Black probe queries")
    }

    @Test
    fun `execute connectivity closure candidate ablation experiment and serialize results`() {
        println("Running Connectivity Closure Candidate Ablation Experiment across 15 configurations...")
        val runner = DefaultConnectivityClosureAblationRunner()

        val results = runner.runAblation(
            corpusDocs = corpusDocs,
            probeQueries = probeQueries,
            budgets = listOf(50, 80, 200, 500, 1000),
            engineCacheFile = cacheFile,
            baselineResultsFile = baselineResultsFile,
        )

        // 1. Verify metadata
        assertEquals("EXP-ISSUE-722-CONNECTIVITY-CLOSURE-ABLATION", results.metadata.experimentId)
        assertEquals(10_000, results.metadata.corpusSize)
        assertEquals(250, results.metadata.queryCount)
        assertEquals(5001.80, results.metadata.meanCandidateUniverseSize, 0.05)
        assertTrue(results.metadata.zeroEngineSearchesGuaranteed)
        assertTrue(results.metadata.zeroProductionChangesGuaranteed)

        // 2. Verify Vocabulary Statistics (§6.3)
        assertEquals(3, results.vocabularyStatistics.size)
        val attackVocab = results.vocabularyStatistics["ATTACK_CLOSURE"]
        assertNotNull(attackVocab)
        assertTrue(attackVocab!!.vocabularySize > 0)
        assertTrue(attackVocab.avgDocumentLength > 0)

        val defenseVocab = results.vocabularyStatistics["DEFENSE_CLOSURE"]
        assertNotNull(defenseVocab)
        assertTrue(defenseVocab!!.vocabularySize > 0)

        val combinedVocab = results.vocabularyStatistics["COMBINED_CLOSURE"]
        assertNotNull(combinedVocab)
        assertTrue(combinedVocab!!.vocabularySize >= attackVocab.vocabularySize)

        // 3. Verify Diagnostics D1 & D2 (§7)
        val diag = results.diagnosticResults
        assertEquals(10_000, diag.populationSize)
        val nxq = diag.nxqDiagnostic
        // Diagnostic D1 representation integrity:
        if (nxq.immediatePly0.totalEvents > 0) {
            assertEquals(1.0, nxq.immediatePly0.eventMatchRate, 1e-9, "Immediate N x Q eventMatchRate must be 1.0 (integrity check)")
            assertEquals(1.0, nxq.immediatePly0.positionMatchRate, 1e-9, "Immediate N x Q positionMatchRate must be 1.0 (integrity check)")
        }

        // 4. Verify System Results (§5.4)
        assertEquals(3, results.systemResults.size, "Must have exactly 3 connectivity systems: C1, C2, C3")
        val expectedSystems = listOf("SYSTEM_C1_ATTACK_CLOSURE", "SYSTEM_C2_DEFENSE_CLOSURE", "SYSTEM_C3_COMBINED_CLOSURE")
        for (sysId in expectedSystems) {
            val sysRecord = results.systemResults[sysId]
            assertNotNull(sysRecord, "System $sysId must be present")
            assertEquals(5, sysRecord!!.budgetResults.size, "System $sysId must evaluate 5 budgets")
            for (b in listOf(50, 80, 200, 500, 1000)) {
                val bMetrics = sysRecord.budgetResults["budget_$b"]
                assertNotNull(bMetrics, "Budget budget_$b must be present for $sysId")
                assertEquals(b, bMetrics!!.requestedBudget)
                assertTrue(bMetrics.actualMeanCandidateCount <= b.toDouble())
                assertTrue(bMetrics.actualMeanCandidateReductionRate > 0.0)
                assertTrue(bMetrics.nominalCandidateReductionRate > 0.0)
            }
        }

        // Verify C1 Jaccard overlap is populated and C2/C3 are null
        val c1MetricsN50 = results.systemResults["SYSTEM_C1_ATTACK_CLOSURE"]!!.budgetResults["budget_50"]!!
        assertNotNull(c1MetricsN50.overlapWithDefenseClosureJaccard)
        val c2MetricsN50 = results.systemResults["SYSTEM_C2_DEFENSE_CLOSURE"]!!.budgetResults["budget_50"]!!
        assertEquals(null, c2MetricsN50.overlapWithDefenseClosureJaccard)

        // Verify C3 incremental gains are populated and C1/C2 are null
        val c3MetricsN50 = results.systemResults["SYSTEM_C3_COMBINED_CLOSURE"]!!.budgetResults["budget_50"]!!
        assertNotNull(c3MetricsN50.bm25IncrementalRecallGainAt10OverBestSingle)
        assertNotNull(c3MetricsN50.bm25IncrementalRecallGainAt20OverBestSingle)
        assertEquals(null, c1MetricsN50.bm25IncrementalRecallGainAt10OverBestSingle)

        // 5. Verify Baseline Comparisons (§5.4.1)
        assertEquals(18, results.baselineComparisons.size, "Must have exactly 18 ingested baseline configurations")
        assertNotNull(results.baselineComparisons["SYSTEM_1_DYNAMIC_REFERENCE"])
        assertNotNull(results.baselineComparisons["SYSTEM_2A_ALPHA_0.60_N50"])
        assertNotNull(results.baselineComparisons["SYSTEM_2A_ALPHA_0.00_N50"])
        assertNotNull(results.baselineComparisons["SYSTEM_2B_ALPHA_0.60_N50"])
        assertNotNull(results.baselineComparisons["SYSTEM_3_ALPHA_0.60"])
        assertNotNull(results.baselineComparisons["SYSTEM_4D_ALPHA_0.60"])

        // 6. Serialize to results artifact
        println("Serializing results artifact to ${outputFile.absolutePath}...")
        mapper.writeValue(outputFile, results)
        assertTrue(outputFile.exists(), "Results artifact file must exist")
        assertTrue(outputFile.length() > 10_000, "Results artifact must be non-empty (> 10 KB)")
        println("Serialization successful (${outputFile.length() / 1024} KB).")
    }
}
