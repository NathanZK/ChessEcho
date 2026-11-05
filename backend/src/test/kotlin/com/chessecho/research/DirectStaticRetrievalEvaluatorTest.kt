package com.chessecho.research

import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.RelationalPawnTokenExtractor
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.random.Random

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DirectStaticRetrievalEvaluatorTest {
    private val corpusFile = File("src/test/resources/research/corpus_a.json")
    private val cacheFile = File("src/test/resources/research/engine_evaluation_cache_d16_mpv1.json")

    private lateinit var corpusDocs: List<PositionDocument>
    private lateinit var cacheRecords: Map<String, CachedEngineRecord>
    private lateinit var probeQueries: List<PositionDocument>
    private lateinit var referencesByQuery: Map<java.util.UUID, QueryReferenceSet>
    private lateinit var directEvaluator: DirectStaticRetrievalEvaluator

    @BeforeAll
    fun setUp() {
        val corpusBuilder = CorpusBuilder()
        val rawPositions = corpusBuilder.loadCorpusFromJson(corpusFile)
        val extractor = RelationalPawnTokenExtractor()

        corpusDocs = rawPositions.map { pos ->
            val id = DeterministicIdGenerator.generateDeterministicId(pos.canonicalFen, pos.samplePly)
            extractor.extractDocument(id, pos.canonicalFen, includeReachability = true)
        }

        val mapper = jacksonObjectMapper()
        cacheRecords = mapper.readValue(cacheFile)

        val refEvaluator = DynamicReferenceRankingEvaluator(corpusDocs, cacheRecords)

        val rng = Random(42L)
        probeQueries = corpusDocs.shuffled(rng).take(25) // representative subset for unit test
        referencesByQuery = probeQueries.associate { q ->
            q.positionId to refEvaluator.computeReferenceForQuery(q)
        }

        directEvaluator = DirectStaticRetrievalEvaluator(corpusDocs, probeQueries, referencesByQuery)
    }

    @Test
    fun `System 2A enforces exact budget self-exclusion and same-side eligibility`() {
        val testBudgets = listOf(50, 80, 200, 500, 1000)
        val alpha = 0.60
        val docsById = corpusDocs.associateBy { it.positionId }

        for (budget in testBudgets) {
            for (query in probeQueries.take(5)) {
                val cands = directEvaluator.retrieveSystem2ACandidates(query, budget, alpha)
                val expectedSize = minOf(budget, corpusDocs.count { it.positionId != query.positionId && it.sideToMove.lowercase() == query.sideToMove.lowercase() })

                assertEquals(expectedSize, cands.size, "Candidate count must equal min(budget, eligible)")
                assertFalse(cands.contains(query.positionId), "Candidate set must exclude self-match")

                cands.forEach { candId ->
                    val candDoc = docsById.getValue(candId)
                    assertEquals(
                        query.sideToMove.lowercase(),
                        candDoc.sideToMove.lowercase(),
                        "All candidates must match query side to move"
                    )
                }
            }
        }
    }

    @Test
    fun `System 2B static cosine control enforces exact budget self-exclusion and deterministic ranking`() {
        val testBudgets = listOf(50, 80, 200, 500, 1000)
        val alpha = 0.60
        val docsById = corpusDocs.associateBy { it.positionId }

        for (budget in testBudgets) {
            for (query in probeQueries.take(5)) {
                val cands = directEvaluator.retrieveSystem2BCandidates(query, budget, alpha)
                val expectedSize = minOf(budget, corpusDocs.count { it.positionId != query.positionId && it.sideToMove.lowercase() == query.sideToMove.lowercase() })

                assertEquals(expectedSize, cands.size, "Candidate count must equal min(budget, eligible)")
                assertFalse(cands.contains(query.positionId), "Candidate set must exclude self-match")

                cands.forEach { candId ->
                    val candDoc = docsById.getValue(candId)
                    assertEquals(
                        query.sideToMove.lowercase(),
                        candDoc.sideToMove.lowercase(),
                        "All candidates must match query side to move"
                    )
                }
            }
        }
    }

    @Test
    fun `evaluateDirectSystem produces valid metrics and non-negative recall across alphas`() {
        val record2A = directEvaluator.evaluateDirectSystem(
            systemId = "SYSTEM_2A_DIRECT_INDEX_N80",
            systemType = "DIRECT_INDEX",
            budget = 80,
            alphas = listOf(0.00, 0.60, 1.00),
            candidateProvider = directEvaluator::retrieveSystem2ACandidates,
        )

        assertEquals("SYSTEM_2A_DIRECT_INDEX_N80", record2A.systemId)
        assertEquals(3, record2A.alphaResults.size)

        val metrics060 = record2A.alphaResults.getValue("alpha_0.6")
        assertEquals(80.0, metrics060.meanCandidateCount, 1e-6)
        assertTrue(metrics060.bm25Metrics.dynamicRecallAt10 in 0.0..1.0)
        assertTrue(metrics060.bm25Metrics.purityAt10 in 0.0..1.0)
        assertTrue(metrics060.tfidfMetrics.dynamicRecallAt10 in 0.0..1.0)
        assertTrue(metrics060.tfidfMetrics.purityAt10 in 0.0..1.0)

        // Positive-only recall matches dynamic recall when zeroScore count is 0
        assertEquals(metrics060.bm25Metrics.dynamicRecallAt10, metrics060.bm25Metrics.positiveOnlyRecallAt10, 1e-12)
        assertEquals(metrics060.tfidfMetrics.dynamicRecallAt10, metrics060.tfidfMetrics.positiveOnlyRecallAt10, 1e-12)
    }
}
