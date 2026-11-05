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
class GraphArchitectureAblationEvaluatorTest {
    private val corpusFile = File("src/test/resources/research/corpus_a.json")
    private val cacheFile = File("src/test/resources/research/engine_evaluation_cache_d16_mpv1.json")

    private lateinit var corpusDocs: List<PositionDocument>
    private lateinit var cacheRecords: Map<String, CachedEngineRecord>
    private lateinit var probeQueries: List<PositionDocument>
    private lateinit var referencesByQuery: Map<java.util.UUID, QueryReferenceSet>
    private lateinit var evaluator: GraphArchitectureAblationEvaluator

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
        probeQueries = corpusDocs.shuffled(rng).take(20) // Representative subset for unit test
        referencesByQuery = probeQueries.associate { q ->
            q.positionId to refEvaluator.computeReferenceForQuery(q)
        }

        evaluator = GraphArchitectureAblationEvaluator(corpusDocs, probeQueries, referencesByQuery)
    }

    @Test
    fun `side-constrained graph bundle enforces structural turn isolation`() {
        val bundle = evaluator.buildSideConstrainedBundle(alpha = 0.60)
        val docsById = corpusDocs.associateBy { it.positionId }

        // All edges in wGraph must connect White positions
        bundle.wGraph.edges.forEach { edge ->
            val srcTurn = docsById.getValue(edge.sourceId).sideToMove.lowercase()
            val tgtTurn = docsById.getValue(edge.targetId).sideToMove.lowercase()
            assertEquals("w", srcTurn, "wGraph edge source must be White")
            assertEquals("w", tgtTurn, "wGraph edge target must be White")
        }

        // All edges in bGraph must connect Black positions
        bundle.bGraph.edges.forEach { edge ->
            val srcTurn = docsById.getValue(edge.sourceId).sideToMove.lowercase()
            val tgtTurn = docsById.getValue(edge.targetId).sideToMove.lowercase()
            assertEquals("b", srcTurn, "bGraph edge source must be Black")
            assertEquals("b", tgtTurn, "bGraph edge target must be Black")
        }
    }

    @Test
    fun `monotonic subset relationship holds for component to leiden community to centroid cluster`() {
        val bundle = evaluator.buildSideConstrainedBundle(alpha = 0.60)

        for (query in probeQueries.take(10)) {
            val comp = evaluator.retrieveSystem4CCandidates(query, bundle).toSet()
            val leiden = evaluator.retrieveSystem4DLeidenCommunity(query, bundle).toSet()
            val centroid = evaluator.retrieveSystem4DCentroidCluster(query, bundle).toSet()

            assertFalse(comp.contains(query.positionId), "Component must exclude self")
            assertFalse(leiden.contains(query.positionId), "Leiden community must exclude self")
            assertFalse(centroid.contains(query.positionId), "Centroid cluster must exclude self")

            // Leiden community must be a subset of the connected component
            assertTrue(
                comp.containsAll(leiden),
                "Leiden community members must be a subset of the connected component (H4a decomposition)"
            )

            // Centroid-pruned survivors must be a subset of the Leiden community
            assertTrue(
                leiden.containsAll(centroid),
                "Centroid cluster members must be a subset of the unpruned Leiden community (H4b decomposition)"
            )
        }
    }

    @Test
    fun `lineage accounting properly partitions recalled candidates into direct and graph-expanded`() {
        val bundle = evaluator.buildSideConstrainedBundle(alpha = 0.60)
        val candidateSetsByQuery = probeQueries.associate { q ->
            q.positionId to evaluator.retrieveSystem4BCandidates(q, budget = 80, bundle)
        }

        val metrics = evaluator.computeAlphaMetrics(
            alpha = 0.60,
            candidateSetsByQuery = candidateSetsByQuery,
            oppositeExcludedCount = 0,
        )

        val lineage = metrics.lineageBreakdown
        assertTrue(lineage != null, "Lineage breakdown must be computed for graph systems")
        lineage!!

        println("Lineage metrics: BM25 direct=${lineage.bm25DirectRecalledAt10}, graph=${lineage.bm25GraphRecalledAt10}, ratio=${lineage.bm25GraphRecoveryRatioAt10}")
        println("Lineage metrics: TF-IDF direct=${lineage.tfidfDirectRecalledAt10}, graph=${lineage.tfidfGraphRecalledAt10}, ratio=${lineage.tfidfGraphRecoveryRatioAt10}")

        assertTrue(lineage.bm25GraphRecoveryRatioAt10 in 0.0..1.0)
        assertTrue(lineage.tfidfGraphRecoveryRatioAt10 in 0.0..1.0)
    }
}
