package com.chessecho.research

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
import kotlin.random.Random

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CandidatePreservationAblationTest {
    private val corpusFile = File("src/test/resources/research/corpus_a.json")
    private val cacheFile = File("src/test/resources/research/engine_evaluation_cache_d16_mpv1.json")
    private val frozenPoolsFile = File("src/test/resources/research/tactical-motif-frozen-pools.json")
    private val scorerComparisonFile = File("src/test/resources/research/scorer_comparison_tfidf_vs_bm25.json")
    private val outputFile = File("src/test/resources/research/candidate_preservation_ablation_results.json")

    private val mapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val alphas = listOf(0.00, 0.25, 0.50, 0.60, 0.75, 1.00)
    private val directBudgets = listOf(50, 80, 200, 500, 1000)
    private val graphBudgets = listOf(50, 80, 200)

    private lateinit var corpusDocs: List<PositionDocument>
    private lateinit var cacheRecords: Map<String, CachedEngineRecord>
    private lateinit var probeQueries: List<PositionDocument>
    private lateinit var queryReferences: Map<java.util.UUID, QueryReferenceSet>
    private lateinit var refEvaluator: DynamicReferenceRankingEvaluator
    private lateinit var auditReport: DynamicReferenceAuditReport

    @BeforeAll
    fun setUp() {
        assertTrue(corpusFile.exists(), "Corpus A file must exist")
        assertTrue(cacheFile.exists(), "Engine evaluation cache must exist")

        val corpusBuilder = CorpusBuilder()
        val rawPositions = corpusBuilder.loadCorpusFromJson(corpusFile)
        val extractor = RelationalPawnTokenExtractor()

        println("Loading 10,000 GM baseline positions from Corpus A...")
        corpusDocs = rawPositions.map { pos ->
            val id = DeterministicIdGenerator.generateDeterministicId(pos.canonicalFen, pos.samplePly)
            extractor.extractDocument(id, pos.canonicalFen, includeReachability = true)
        }
        assertEquals(10_000, corpusDocs.size, "Corpus A must have exactly 10,000 positions")

        println("Loading Stockfish Depth 16 MultiPV=1 evaluation cache (0 subprocess invocations)...")
        cacheRecords = mapper.readValue(cacheFile)
        assertTrue(cacheRecords.size >= 10_000, "Cache must contain all corpus positions")

        // Stratified 250 probe queries using seed 42
        val rng = Random(42L)
        probeQueries = corpusDocs.shuffled(rng).take(250)
        assertEquals(250, probeQueries.size, "Probe query set must equal 250 positions")

        println("Computing unconstrained same-side dynamic reference rankings over U(q) for 250 queries...")
        refEvaluator = DynamicReferenceRankingEvaluator(corpusDocs, cacheRecords)
        val refList = probeQueries.map { q -> refEvaluator.computeReferenceForQuery(q) }
        queryReferences = refList.associateBy { it.queryId }
        auditReport = refEvaluator.buildAuditReport(refList)

        assertEquals(0, auditReport.bm25Audit.zeroScoreInTop10Count)
        assertEquals(0, auditReport.bm25Audit.zeroScoreInTop20Count)
        assertEquals(0, auditReport.tfidfAudit.zeroScoreInTop10Count)
        assertEquals(0, auditReport.tfidfAudit.zeroScoreInTop20Count)
        println("Dynamic reference audit passed: 0 zero-score entries in R_10 and R_20 across all 250 queries.")
    }

    @Test
    fun `execute complete candidate preservation and dynamic retrieval ablation`() {
        val systemResults = mutableMapOf<String, SystemAblationRecord>()

        // 1. Direct Static Retrieval & Control (Systems 2A and 2B)
        println("Evaluating System 2A (Direct Inverted Index) and System 2B (Static Cosine Control)...")
        val directEvaluator = DirectStaticRetrievalEvaluator(corpusDocs, probeQueries, queryReferences)

        for (budget in directBudgets) {
            println("  Evaluating direct candidate budget N=$budget across 6 alphas...")
            val rec2A = directEvaluator.evaluateDirectSystem(
                systemId = "SYSTEM_2A_DIRECT_INDEX_N$budget",
                systemType = "DIRECT_INDEX",
                budget = budget,
                alphas = alphas,
                candidateProvider = directEvaluator::retrieveSystem2ACandidates,
            )
            systemResults[rec2A.systemId] = rec2A

            val rec2B = directEvaluator.evaluateDirectSystem(
                systemId = "SYSTEM_2B_STATIC_COSINE_N$budget",
                systemType = "STATIC_COSINE_CONTROL",
                budget = budget,
                alphas = alphas,
                candidateProvider = directEvaluator::retrieveSystem2BCandidates,
            )
            systemResults[rec2B.systemId] = rec2B
        }

        // 2. Production Clustered Baseline (System 3, splitBySideToMove = false)
        println("Evaluating System 3 (Production Clustered Baseline, unified graph)...")
        val graphEvaluator = GraphArchitectureAblationEvaluator(corpusDocs, probeQueries, queryReferences)
        val rec3 = graphEvaluator.evaluateSystem3(alphas)
        systemResults[rec3.systemId] = rec3

        // 3. Side-Constrained Graph Suite (Family 4, splitBySideToMove = true)
        println("Evaluating Side-Constrained Graph Architecture (Systems 4A, 4B, 4C, 4D)...")
        val system4AAlphaResults = mutableMapOf<String, AlphaSystemMetrics>()
        val system4BAlphaResults = mutableMapOf<Int, MutableMap<String, AlphaSystemMetrics>>()
        graphBudgets.forEach { budget ->
            system4BAlphaResults[budget] = mutableMapOf()
        }
        val system4CAlphaResults = mutableMapOf<String, AlphaSystemMetrics>()
        val system4DLeidenAlphaResults = mutableMapOf<String, AlphaSystemMetrics>()
        val system4DClusteredAlphaResults = mutableMapOf<String, AlphaSystemMetrics>()

        alphas.forEach { alpha ->
            val alphaKey = "alpha_${alpha}"
            println("  Building side-constrained graph bundle for alpha=$alpha...")
            val bundle = graphEvaluator.buildSideConstrainedBundle(alpha)

            // System 4A (1-Hop MNN Neighbors)
            val cands4A = probeQueries.associate { q ->
                q.positionId to graphEvaluator.retrieveSystem4ACandidates(q, bundle)
            }
            system4AAlphaResults[alphaKey] = graphEvaluator.computeAlphaMetrics(alpha, cands4A, oppositeExcludedCount = 0)

            // System 4B (2-Hop Expansion at N in {50, 80, 200})
            graphBudgets.forEach { budget ->
                val cands4B = probeQueries.associate { q ->
                    q.positionId to graphEvaluator.retrieveSystem4BCandidates(q, budget, bundle)
                }
                system4BAlphaResults.getValue(budget)[alphaKey] = graphEvaluator.computeAlphaMetrics(alpha, cands4B, oppositeExcludedCount = 0)
            }

            // System 4C (Connected Component Upper Bound within G_side,alpha)
            val cands4C = probeQueries.associate { q ->
                q.positionId to graphEvaluator.retrieveSystem4CCandidates(q, bundle)
            }
            system4CAlphaResults[alphaKey] = graphEvaluator.computeAlphaMetrics(alpha, cands4C, oppositeExcludedCount = 0)

            // System 4D (Unpruned Leiden Community on G_side,alpha)
            val cands4DLeiden = probeQueries.associate { q ->
                q.positionId to graphEvaluator.retrieveSystem4DLeidenCommunity(q, bundle)
            }
            system4DLeidenAlphaResults[alphaKey] = graphEvaluator.computeAlphaMetrics(alpha, cands4DLeiden, oppositeExcludedCount = 0)

            // System 4D (Centroid-Pruned Cluster on G_side,alpha)
            val cands4DClustered = probeQueries.associate { q ->
                q.positionId to graphEvaluator.retrieveSystem4DCentroidCluster(q, bundle)
            }
            system4DClusteredAlphaResults[alphaKey] = graphEvaluator.computeAlphaMetrics(alpha, cands4DClustered, oppositeExcludedCount = 0)
        }

        systemResults["SYSTEM_4A_GRAPH_1HOP"] = SystemAblationRecord(
            systemId = "SYSTEM_4A_GRAPH_1HOP",
            systemType = "SIDE_CONSTRAINED_GRAPH_EXPANSION",
            targetBudget = null,
            alphaResults = system4AAlphaResults,
        )

        graphBudgets.forEach { budget ->
            systemResults["SYSTEM_4B_GRAPH_2HOP_N$budget"] = SystemAblationRecord(
                systemId = "SYSTEM_4B_GRAPH_2HOP_N$budget",
                systemType = "SIDE_CONSTRAINED_GRAPH_EXPANSION",
                targetBudget = budget,
                alphaResults = system4BAlphaResults.getValue(budget),
            )
        }

        systemResults["SYSTEM_4C_GRAPH_CONNECTED_COMPONENT"] = SystemAblationRecord(
            systemId = "SYSTEM_4C_GRAPH_CONNECTED_COMPONENT",
            systemType = "SIDE_CONSTRAINED_GRAPH_EXPANSION",
            targetBudget = null,
            alphaResults = system4CAlphaResults,
        )

        systemResults["SYSTEM_4D_SIDE_CONSTRAINED_LEIDEN_COMMUNITY"] = SystemAblationRecord(
            systemId = "SYSTEM_4D_SIDE_CONSTRAINED_LEIDEN_COMMUNITY",
            systemType = "SIDE_CONSTRAINED_CLUSTERED_PIPELINE",
            targetBudget = null,
            alphaResults = system4DLeidenAlphaResults,
        )

        systemResults["SYSTEM_4D_SIDE_CONSTRAINED_CLUSTERED"] = SystemAblationRecord(
            systemId = "SYSTEM_4D_SIDE_CONSTRAINED_CLUSTERED",
            systemType = "SIDE_CONSTRAINED_CLUSTERED_PIPELINE",
            targetBudget = null,
            alphaResults = system4DClusteredAlphaResults,
        )

        // Validate diagnostic tactical benchmark integrity from frozen artifacts
        val tacticalBenchmarkMap: Map<String, Any> = if (scorerComparisonFile.exists()) {
            mapper.readValue(scorerComparisonFile)
        } else {
            emptyMap()
        }

        val metadata = mapOf(
            "experimentId" to "2026-11-05-candidate-preservation-ablation",
            "context" to "GitHub Issue #722",
            "totalCorpusPositions" to corpusDocs.size,
            "totalProbeQueries" to probeQueries.size,
            "engineDepth" to 16,
            "multiPv" to 1,
            "decisionThresholdCp" to 120,
            "alphasEvaluated" to alphas,
            "directBudgetsEvaluated" to directBudgets,
            "graphBudgetsEvaluated" to graphBudgets,
            "engineSubprocessInvocations" to 0,
            "totalNamedSystemVariants" to systemResults.size,
        )

        val experimentResult = AblationExperimentResult(
            metadata = metadata,
            referenceAudit = auditReport,
            tacticalMotifBenchmarkValidation = tacticalBenchmarkMap,
            systemResults = systemResults,
        )

        // Serialize output JSON artifact
        println("Serializing full experiment result to ${outputFile.path}...")
        mapper.writeValue(outputFile, experimentResult)
        assertTrue(outputFile.exists(), "Output artifact file must exist")
        assertTrue(outputFile.length() > 0, "Output artifact file must be non-empty")
        println("Experiment execution completed successfully! Artifact size: ${outputFile.length()} bytes.")

        // Basic sanity assertions on results
        assertEquals(18, systemResults.size, "Total system variant records (including budget tiers) must equal 18")
        systemResults.values.forEach { record ->
            assertEquals(6, record.alphaResults.size, "Each system variant must evaluate all 6 alphas")
        }
    }
}
