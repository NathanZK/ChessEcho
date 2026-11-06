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
import java.util.UUID
import kotlin.random.Random

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectivityTokenIntersectionAnalysisTest {

    private val mapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val corpusFile = File("src/test/resources/research/corpus_a.json")
    private val cacheFile = File("src/test/resources/research/engine_evaluation_cache_d16_mpv1.json")
    private val outputFile = File("src/test/resources/research/connectivity_token_intersection_diagnostics.json")

    private lateinit var corpusDocs: List<PositionDocument>
    private lateinit var probeQueries: List<PositionDocument>
    private lateinit var engineCache: Map<String, CachedEngineRecord>
    private lateinit var queryReferences: Map<UUID, QueryReferenceSet>

    @BeforeAll
    fun setUp() {
        assertTrue(corpusFile.exists(), "Corpus A file must exist: ${corpusFile.absolutePath}")
        assertTrue(cacheFile.exists(), "Engine evaluation cache must exist: ${cacheFile.absolutePath}")

        val corpusBuilder = CorpusBuilder()
        val rawPositions = corpusBuilder.loadCorpusFromJson(corpusFile)
        val extractor = RelationalPawnTokenExtractor()

        println("Loading 10,000 GM positions from Corpus A...")
        corpusDocs = rawPositions.map { pos ->
            val id = DeterministicIdGenerator.generateDeterministicId(pos.canonicalFen, pos.samplePly)
            extractor.extractDocument(id, pos.canonicalFen, includeReachability = true)
        }
        assertEquals(10_000, corpusDocs.size, "Corpus A must have exactly 10,000 positions")

        // Exact seed 42 probe query sampling identical to Issue #722 baseline
        val rng = Random(42L)
        probeQueries = corpusDocs.shuffled(rng).take(250)
        assertEquals(250, probeQueries.size, "Probe query set must equal 250 positions")

        val whiteCount = probeQueries.count { it.fen.split("\\s+".toRegex())[1].lowercase() == "w" }
        val blackCount = probeQueries.count { it.fen.split("\\s+".toRegex())[1].lowercase() == "b" }
        assertEquals(139, whiteCount, "Must have exactly 139 White probe queries")
        assertEquals(111, blackCount, "Must have exactly 111 Black probe queries")

        println("Loading engine evaluation cache from ${cacheFile.name}...")
        val rawCache: Map<String, CachedEngineRecord> = mapper.readValue(cacheFile)
        engineCache = rawCache.values.associateBy { it.canonicalFen }

        println("Computing dynamic reference rankings...")
        val refEvaluator = DynamicReferenceRankingEvaluator(corpusDocs, engineCache)
        queryReferences = probeQueries.associate { it.positionId to refEvaluator.computeReferenceForQuery(it) }
    }

    @Test
    fun `execute diagnostic token intersection analysis and serialize JSON artifact`() {
        println("Executing ConnectivityTokenIntersectionAnalyzer...")
        val analyzer = ConnectivityTokenIntersectionAnalyzer(
            corpusDocs = corpusDocs,
            probeQueries = probeQueries,
            engineCache = engineCache,
            queryReferences = queryReferences,
        )

        val report = analyzer.runAnalysis()

        // Serialize to disk first so artifact is preserved
        println("Serializing diagnostic report to ${outputFile.absolutePath}...")
        outputFile.parentFile.mkdirs()
        mapper.writeValue(outputFile, report)
        assertTrue(outputFile.exists(), "Diagnostic artifact must exist on disk")
        assertTrue(outputFile.length() > 5000, "Diagnostic artifact must not be empty (was ${outputFile.length()} bytes)")
        println("Successfully generated diagnostic artifact (${outputFile.length()} bytes)")

        println("Defense token family contributions: ${report.defenseClosureBudgetAnalysis.tokenFamilyScoreContributions}")

        // 1. Verify Query Verification
        assertEquals(250, report.queryVerification.totalQueries)
        assertEquals(139, report.queryVerification.whiteCount)
        assertEquals(111, report.queryVerification.blackCount)
        assertEquals(5001.80, report.queryVerification.meanUniverseSize, 0.05)
        assertTrue(report.queryVerification.kotlinRandomIdentitiesVerified)
        assertTrue(report.queryVerification.historicalJavaRandomDistinctionDocumented)

        // 2. Verify Token System Comparison
        assertEquals(0.0, report.tokenSystemComparison.literalStringOverlapPercentage, 1e-9)
        assertTrue(report.tokenSystemComparison.attackClosureTokenCountPerPosition.mean < report.tokenSystemComparison.defenseClosureTokenCountPerPosition.mean)

        // 3. Verify Attack Closure Failure
        assertTrue(report.attackClosureFailureAnalysis.poolExhaustionPercentageByBudget["budget_500"]!! > 40.0)
        assertTrue(report.attackClosureFailureAnalysis.refTop10ZeroAttackOverlapPercentage > 60.0)

        // 4. Verify Defense Closure Budget Dynamics
        assertTrue(report.defenseClosureBudgetAnalysis.tokenFamilyScoreContributions.containsKey("pawn_pawn_chains"))
        assertTrue(report.defenseClosureBudgetAnalysis.tokenFamilyScoreContributions["pawn_pawn_chains"]!! > 0.0)

        // 5. Verify Missed Candidate Breakdown
        assertTrue(report.missedCandidateClassification.attackClosureByBudget.containsKey("budget_50"))
        assertTrue(report.missedCandidateClassification.defenseClosureByBudget.containsKey("budget_50"))

        // 6. Verify Persistent Zero Recall
        assertTrue(report.persistentZeroRecallAnalysis.persistentZeroRecallQueriesAtN1000Count > 0)

        // 7. Verify Representative Examples
        assertTrue(report.representativeExamples.isNotEmpty())
    }
}
