package com.chessprobe.similarity.clustering

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class RelaxedMnnGraphBuilderTest {
    private val scorer = PositionClusterSimilarityScorer()
    private val builder = RelaxedMnnGraphBuilder(scorer)

    private fun createVector(
        fen: String,
        terms: Map<String, Double>,
        alpha: Double = 0.60,
    ): PositionClusterVector {
        val idfs = terms.keys.associateWith { 1.0 }
        return scorer.buildVector(
            positionId = UUID.randomUUID(),
            canonicalFen = fen,
            terms = terms,
            idfs = idfs,
            alpha = alpha,
        )
    }

    @Test
    fun `creates mutual top-k edge when similarity is at or above floor threshold`() {
        // Two positions sharing features giving similarity >= 0.40
        val v1 = createVector("fen_a", mapOf("P(e4)" to 1.0, "N(f3)" to 1.0))
        val v2 = createVector("fen_b", mapOf("P(e4)" to 1.0, "N(f3)" to 1.0))

        val graph =
            builder.buildGraph(
                vectors = listOf(v1, v2),
                topK = 5,
                floorThreshold = 0.40,
                top1Threshold = 0.65,
            )

        assertEquals(2, graph.positions.size)
        assertEquals(1, graph.edges.size)
        val edge = graph.edges[0]
        assertTrue(
            (edge.sourceId == v1.positionId && edge.targetId == v2.positionId) ||
                (edge.sourceId == v2.positionId && edge.targetId == v1.positionId),
        )
        assertEquals(1.0, edge.weight, 1e-6)
    }

    @Test
    fun `rejects mutual candidate pair when similarity is below floor threshold`() {
        // v1 has mostly pawn e4, v2 has mostly pawn d4, slight overlap
        val v1 = createVector("fen_a", mapOf("P(e4)" to 10.0, "P(c4)" to 1.0))
        val v2 = createVector("fen_b", mapOf("P(d4)" to 10.0, "P(c4)" to 1.0))

        val sim = scorer.cosineSimilarity(v1, v2)
        assertTrue(sim < 0.40, "similarity should be below floor threshold, was $sim")

        val graph =
            builder.buildGraph(
                vectors = listOf(v1, v2),
                topK = 5,
                floorThreshold = 0.40,
                top1Threshold = 0.65,
            )

        assertEquals(2, graph.positions.size)
        assertEquals(0, graph.edges.size)
    }

    @Test
    fun `rejects asymmetric top-k candidate pair if similarity below top1 threshold`() {
        // A, B, C, D
        // Let A have highest sim to B (0.50).
        // But B has highest sim to C (0.90) and D (0.80), and topK = 1, so A is NOT in B's topK.
        // Sim(A, B) = 0.50, which is >= floor (0.40) but < top1 (0.65).
        // Since B's top-1 is C, and A's top-1 is B, but A is not in B's top-k and sim < 0.65,
        // no edge between A and B should be formed.
        val a = createVector("fen_a", mapOf("P(e4)" to 1.0, "P(c4)" to 1.0))
        val b = createVector("fen_b", mapOf("P(e4)" to 1.0, "N(f3)" to 1.0))
        val c = createVector("fen_c", mapOf("P(e4)" to 1.0, "N(f3)" to 1.0)) // Identical to B

        val simAB = scorer.cosineSimilarity(a, b)
        val simBC = scorer.cosineSimilarity(b, c)

        assertTrue(simAB in 0.40..0.60, "simAB should be in [0.40, 0.60], was $simAB")
        assertEquals(1.0, simBC, 1e-6)

        val graph =
            builder.buildGraph(
                vectors = listOf(a, b, c),
                topK = 1, // Only top 1 candidate retained in N_k
                floorThreshold = 0.40,
                top1Threshold = 0.65,
            )

        // B's top 1 is C (sim 1.0). A is not in B's top 1.
        // A's top 1 is B or C (both sim ~0.50 < 0.65).
        // So A and B are NOT mutual top-1, and simAB < 0.65.
        // Therefore no edge between A and B! Edge exists between B and C.
        val edgesWithA = graph.edges.filter { it.sourceId == a.positionId || it.targetId == a.positionId }
        assertEquals(0, edgesWithA.size)

        val edgesWithBC =
            graph.edges.filter {
                (it.sourceId == b.positionId && it.targetId == c.positionId) ||
                    (it.sourceId == c.positionId && it.targetId == b.positionId)
            }
        assertEquals(1, edgesWithBC.size)
    }

    @Test
    fun `creates edge under absolute top-1 exception when similarity is at or above 0_65`() {
        // A's top-1 candidate is B with similarity 0.70.
        // B's top-1 candidate is C with similarity 0.95.
        // topK = 1. A is NOT in B's top-1.
        // However, B is A's top-1 and sim(A, B) = 0.70 >= 0.65.
        // Absolute top-1 exception applies -> edge (A, B) is added!
        val a = createVector("fen_a", mapOf("P(e4)" to 2.0, "N(f3)" to 1.0))
        val b = createVector("fen_b", mapOf("P(e4)" to 2.0, "N(f3)" to 1.0, "B(b5)" to 0.5))
        val c = createVector("fen_c", mapOf("P(e4)" to 2.0, "N(f3)" to 1.0, "B(b5)" to 0.5)) // identical to b

        val simAB = scorer.cosineSimilarity(a, b)
        assertTrue(simAB >= 0.65, "simAB should be >= 0.65, was $simAB")

        val graph =
            builder.buildGraph(
                vectors = listOf(a, b, c),
                topK = 1,
                floorThreshold = 0.40,
                top1Threshold = 0.65,
            )

        val edgesWithA = graph.edges.filter { it.sourceId == a.positionId || it.targetId == a.positionId }
        assertEquals(1, edgesWithA.size)
        val edge = edgesWithA[0]
        assertTrue(edge.sourceId == b.positionId || edge.targetId == b.positionId)
        assertEquals(simAB, edge.weight, 1e-6)
    }

    @Test
    fun `breaks candidate ties using canonicalFen ascending`() {
        // Target position T
        val t = createVector("fen_target", mapOf("P(e4)" to 1.0))
        // Candidates C1 and C2 have identical similarity to T (1.0), but different FENs
        val c1 = createVector("fen_z_later", mapOf("P(e4)" to 1.0))
        val c2 = createVector("fen_a_earlier", mapOf("P(e4)" to 1.0))

        val graph =
            builder.buildGraph(
                vectors = listOf(t, c1, c2),
                topK = 1, // Only 1 can be in topK
                floorThreshold = 0.40,
                top1Threshold = 0.65,
            )

        // T should tie-break to c2 ("fen_a_earlier" < "fen_z_later")
        // c2 should tie-break to c1 or t. Between c1 and t, "fen_target" < "fen_z_later", so c2 picks t!
        // Mutual top-1 between t and c2!
        val edgeTC2 =
            graph.edges.find {
                (it.sourceId == t.positionId && it.targetId == c2.positionId) ||
                    (it.sourceId == c2.positionId && it.targetId == t.positionId)
            }
        assertTrue(edgeTC2 != null, "Edge between target and fen_a_earlier should exist due to tie-breaking")
    }

    @Test
    fun `graph deduplicates undirected edges and never creates self edges`() {
        val v1 = createVector("fen_a", mapOf("P(e4)" to 1.0))
        val v2 = createVector("fen_b", mapOf("P(e4)" to 1.0))

        val graph =
            builder.buildGraph(
                vectors = listOf(v1, v2),
                topK = 10,
                floorThreshold = 0.40,
                top1Threshold = 0.65,
            )

        assertEquals(1, graph.edges.size)
        assertFalse(graph.edges.any { it.sourceId == it.targetId }, "Must not contain self edges")
    }

    @Test
    fun `isolates are preserved in position list with degree 0`() {
        val v1 = createVector("fen_a", mapOf("P(e4)" to 1.0))
        val v2 = createVector("fen_b", mapOf("P(e4)" to 1.0))
        val isolate = createVector("fen_c", mapOf("Q(h5)" to 1.0)) // completely disjoint

        val graph =
            builder.buildGraph(
                vectors = listOf(v1, v2, isolate),
                topK = 10,
                floorThreshold = 0.40,
                top1Threshold = 0.65,
            )

        assertEquals(3, graph.positions.size)
        assertEquals(1, graph.edges.size)
        val isolateEdges =
            graph.edges.filter {
                it.sourceId == isolate.positionId || it.targetId == isolate.positionId
            }
        assertEquals(0, isolateEdges.size)
    }

    @Test
    fun `creates mutual top-k edge when similarity is exactly equal to floor threshold`() {
        val mockScorer = org.mockito.Mockito.mock(PositionClusterSimilarityScorer::class.java)
        val customBuilder = RelaxedMnnGraphBuilder(mockScorer)
        val v1 = createVector("fen_a", mapOf("P(e4)" to 1.0))
        val v2 = createVector("fen_b", mapOf("P(e4)" to 1.0))

        org.mockito.Mockito
            .`when`(mockScorer.cosineSimilarity(v1, v2))
            .thenReturn(0.40)
        org.mockito.Mockito
            .`when`(mockScorer.cosineSimilarity(v2, v1))
            .thenReturn(0.40)

        val graph =
            customBuilder.buildGraph(
                vectors = listOf(v1, v2),
                topK = 5,
                floorThreshold = 0.40,
                top1Threshold = 0.65,
            )

        assertEquals(1, graph.edges.size)
        assertEquals(0.40, graph.edges[0].weight, 1e-6)
    }

    @Test
    fun `creates asymmetric edge when similarity is exactly equal to top-1 threshold`() {
        val mockScorer = org.mockito.Mockito.mock(PositionClusterSimilarityScorer::class.java)
        val customBuilder = RelaxedMnnGraphBuilder(mockScorer)
        val v1 = createVector("fen_a", mapOf("P(e4)" to 1.0))
        val v2 = createVector("fen_b", mapOf("P(e4)" to 1.0))
        val v3 = createVector("fen_c", mapOf("P(e4)" to 1.0))

        org.mockito.Mockito
            .`when`(mockScorer.cosineSimilarity(v1, v2))
            .thenReturn(0.65)
        org.mockito.Mockito
            .`when`(mockScorer.cosineSimilarity(v2, v1))
            .thenReturn(0.35)
        org.mockito.Mockito
            .`when`(mockScorer.cosineSimilarity(v1, v3))
            .thenReturn(0.10)
        org.mockito.Mockito
            .`when`(mockScorer.cosineSimilarity(v3, v1))
            .thenReturn(0.10)
        org.mockito.Mockito
            .`when`(mockScorer.cosineSimilarity(v2, v3))
            .thenReturn(0.90)
        org.mockito.Mockito
            .`when`(mockScorer.cosineSimilarity(v3, v2))
            .thenReturn(0.90)

        val graph =
            customBuilder.buildGraph(
                vectors = listOf(v1, v2, v3),
                topK = 1,
                floorThreshold = 0.40,
                top1Threshold = 0.65,
            )

        val v1v2Edge =
            graph.edges.find {
                (it.sourceId == v1.positionId && it.targetId == v2.positionId) ||
                    (it.sourceId == v2.positionId && it.targetId == v1.positionId)
            }
        assertTrue(v1v2Edge != null, "Asymmetric edge at exactly top-1 threshold must be included")
    }
}
