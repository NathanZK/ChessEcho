package com.chessprobe.similarity.clustering

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PositionLeidenCommunityDetectorTest {
    private val detector = PositionLeidenCommunityDetector()

    private fun mockVector(fen: String = "fen"): PositionClusterVector =
        PositionClusterVector(
            positionId = UUID.randomUUID(),
            canonicalFen = fen,
            rawTerms = emptyMap(),
            weightedTerms = emptyMap(),
            pawnSubvectorNorm = 1.0,
            pieceSubvectorNorm = 1.0,
            totalNorm = 1.0,
        )

    @Test
    fun `zero-edge graph bypasses community detection and returns all positions as isolates`() {
        val v1 = mockVector("fen1")
        val v2 = mockVector("fen2")
        val v3 = mockVector("fen3")

        val graph =
            PositionGraph(
                positions = listOf(v1, v2, v3),
                edges = emptyList(),
            )

        val result = detector.detectCommunities(graph, rbResolution = 1.25)

        assertEquals(3, result.assignments.size)
        assertNull(result.assignments[v1.positionId])
        assertNull(result.assignments[v2.positionId])
        assertNull(result.assignments[v3.positionId])
        assertEquals(0.0, result.quality, 1e-6)
    }

    @Test
    fun `detects separated cliques into distinct communities and leaves isolates as null`() {
        // Clique 1: v1, v2, v3
        val v1 = mockVector("c1_1")
        val v2 = mockVector("c1_2")
        val v3 = mockVector("c1_3")

        // Clique 2: v4, v5, v6
        val v4 = mockVector("c2_1")
        val v5 = mockVector("c2_2")
        val v6 = mockVector("c2_3")

        // Isolate: v7
        val v7 = mockVector("iso")

        val edges =
            listOf(
                // Clique 1 edges
                PositionGraphEdge(v1.positionId, v2.positionId, 0.8),
                PositionGraphEdge(v2.positionId, v3.positionId, 0.8),
                PositionGraphEdge(v1.positionId, v3.positionId, 0.8),
                // Clique 2 edges
                PositionGraphEdge(v4.positionId, v5.positionId, 0.8),
                PositionGraphEdge(v5.positionId, v6.positionId, 0.8),
                PositionGraphEdge(v4.positionId, v6.positionId, 0.8),
            )

        val graph =
            PositionGraph(
                positions = listOf(v1, v2, v3, v4, v5, v6, v7),
                edges = edges,
            )

        val result = detector.detectCommunities(graph, rbResolution = 1.25)

        val c1 = result.assignments[v1.positionId]
        val c2 = result.assignments[v2.positionId]
        val c3 = result.assignments[v3.positionId]

        val c4 = result.assignments[v4.positionId]
        val c5 = result.assignments[v5.positionId]
        val c6 = result.assignments[v6.positionId]

        val c7 = result.assignments[v7.positionId]

        // All nodes in Clique 1 share the same non-null cluster ID
        assertTrue(c1 != null)
        assertEquals(c1, c2)
        assertEquals(c1, c3)

        // All nodes in Clique 2 share the same non-null cluster ID
        assertTrue(c4 != null)
        assertEquals(c4, c5)
        assertEquals(c4, c6)

        // Clique 1 and Clique 2 are distinct
        assertNotEquals(c1, c4)

        // Isolate is null
        assertNull(c7)
    }

    @Test
    fun `clustering is deterministic given identical graph and seed`() {
        val v1 = mockVector("fen1")
        val v2 = mockVector("fen2")
        val v3 = mockVector("fen3")
        val v4 = mockVector("fen4")

        val edges =
            listOf(
                PositionGraphEdge(v1.positionId, v2.positionId, 0.6),
                PositionGraphEdge(v2.positionId, v3.positionId, 0.5),
                PositionGraphEdge(v3.positionId, v4.positionId, 0.7),
                PositionGraphEdge(v1.positionId, v4.positionId, 0.4),
            )

        val graph =
            PositionGraph(
                positions = listOf(v1, v2, v3, v4),
                edges = edges,
            )

        val result1 = detector.detectCommunities(graph, rbResolution = 1.25, randomSeed = 42L)
        val result2 = detector.detectCommunities(graph, rbResolution = 1.25, randomSeed = 42L)

        assertEquals(result1.assignments, result2.assignments)
        assertEquals(result1.quality, result2.quality, 1e-9)
    }

    @Test
    fun `verifies exact Reichardt-Bornholdt quality parity`() {
        // Build a known 4-node graph
        val v1 = mockVector("fen1")
        val v2 = mockVector("fen2")
        val v3 = mockVector("fen3")
        val v4 = mockVector("fen4")

        val edges =
            listOf(
                PositionGraphEdge(v1.positionId, v2.positionId, 1.0),
                PositionGraphEdge(v3.positionId, v4.positionId, 1.0),
            )

        val graph =
            PositionGraph(
                positions = listOf(v1, v2, v3, v4),
                edges = edges,
            )

        // Total edge weight m = 2.0 (undirected: 2 edges of weight 1.0), so 2m = 4.0
        // Degrees: k1 = 1.0, k2 = 1.0, k3 = 1.0, k4 = 1.0
        // Cluster 1 = {v1, v2}, Cluster 2 = {v3, v4}
        // Within-cluster edge weights sum = 2 * (1.0 + 1.0) = 4.0
        // Cluster 1 total degree W1 = k1 + k2 = 2.0 -> W1^2 = 4.0
        // Cluster 2 total degree W2 = k3 + k4 = 2.0 -> W2^2 = 4.0
        // With rbResolution = 1.0:
        // gamma_CWTS = rbResolution / (2m) = 1.0 / 4.0 = 0.25
        // CWTS calcQuality computes (sum_intra_A - gamma_CWTS * sum_C(W_C^2)) / (2m)
        // = (4.0 - 0.25 * (4.0 + 4.0)) / 4.0 = (4.0 - 2.0) / 4.0 = 0.50 = Q_RB!
        val result = detector.detectCommunities(graph, rbResolution = 1.0, randomSeed = 42L)

        assertEquals(result.assignments[v1.positionId], result.assignments[v2.positionId])
        assertEquals(result.assignments[v3.positionId], result.assignments[v4.positionId])
        assertNotEquals(result.assignments[v1.positionId], result.assignments[v3.positionId])

        // Verify quality matches exact Reichardt-Bornholdt quality Q_RB = 0.50
        assertEquals(0.5, result.quality, 1e-6)
    }
}
