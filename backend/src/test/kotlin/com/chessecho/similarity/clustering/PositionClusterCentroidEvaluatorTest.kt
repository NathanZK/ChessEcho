package com.chessecho.similarity.clustering

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PositionClusterCentroidEvaluatorTest {
    private val scorer = PositionClusterSimilarityScorer()
    private val evaluator = PositionClusterCentroidEvaluator()

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
    fun `evaluates tightly cohesive cluster with all members assigned`() {
        val v1 = createVector("fen_a", mapOf("P(e4)" to 1.0, "N(f3)" to 1.0))
        val v2 = createVector("fen_b", mapOf("P(e4)" to 1.0, "N(f3)" to 1.0))

        val result =
            evaluator.evaluateClusters(
                vectors = listOf(v1, v2),
                communityAssignments = mapOf(v1.positionId to 0, v2.positionId to 0),
                centroidThreshold = 0.38,
            )

        assertEquals(1, result.clusters.size)
        val cluster = result.clusters[0]
        assertEquals(0, cluster.clusterIndex)
        assertEquals(2, cluster.size)
        assertEquals(2, cluster.memberPositionIds.size)

        assertEquals(2, result.assignments.size)
        val a1 = result.assignments.first { it.positionId == v1.positionId }
        val a2 = result.assignments.first { it.positionId == v2.positionId }

        assertEquals(ClusterAssignmentStatus.ASSIGNED, a1.status)
        assertEquals(0, a1.clusterIndex)
        assertEquals(1.0, a1.centroidSimilarity!!, 1e-6)

        assertEquals(ClusterAssignmentStatus.ASSIGNED, a2.status)
        assertEquals(0, a2.clusterIndex)
        assertEquals(1.0, a2.centroidSimilarity!!, 1e-6)
    }

    @Test
    fun `evicts outlier member when centroid similarity is below threshold`() {
        // v1 and v2 share many terms, v3 is far away but was grouped into same initial community
        val v1 = createVector("fen_a", mapOf("P(e4)" to 5.0, "P(d4)" to 5.0, "N(f3)" to 5.0))
        val v2 = createVector("fen_b", mapOf("P(e4)" to 5.0, "P(d4)" to 5.0, "N(f3)" to 5.0))
        val v3 = createVector("fen_c", mapOf("P(a4)" to 5.0, "P(b4)" to 5.0, "P(e4)" to 0.1))

        val result =
            evaluator.evaluateClusters(
                vectors = listOf(v1, v2, v3),
                communityAssignments =
                    mapOf(
                        v1.positionId to 1,
                        v2.positionId to 1,
                        v3.positionId to 1,
                    ),
                centroidThreshold = 0.38,
            )

        assertEquals(1, result.clusters.size)
        val cluster = result.clusters[0]
        assertEquals(2, cluster.size)
        assertTrue(cluster.memberPositionIds.contains(v1.positionId))
        assertTrue(cluster.memberPositionIds.contains(v2.positionId))
        assertTrue(!cluster.memberPositionIds.contains(v3.positionId))

        val a3 = result.assignments.first { it.positionId == v3.positionId }
        assertEquals(ClusterAssignmentStatus.OUTLIER_EVICTED, a3.status)
        assertNull(a3.clusterIndex)
        assertTrue(a3.centroidSimilarity != null)
        assertTrue(a3.centroidSimilarity!! < 0.38)
    }

    @Test
    fun `drops cluster entirely if all members fail centroid cohesion threshold`() {
        val v1 = createVector("fen_a", mapOf("P(e4)" to 1.0))
        val v2 = createVector("fen_b", mapOf("P(d4)" to 1.0))

        // centroid similarity is ~0.70, with threshold 0.99 both are evicted
        val result =
            evaluator.evaluateClusters(
                vectors = listOf(v1, v2),
                communityAssignments = mapOf(v1.positionId to 5, v2.positionId to 5),
                centroidThreshold = 0.99,
            )

        assertEquals(0, result.clusters.size)
        assertEquals(2, result.assignments.size)
        assertTrue(result.assignments.all { it.status == ClusterAssignmentStatus.OUTLIER_EVICTED })
        assertTrue(result.assignments.all { it.clusterIndex == null })
    }

    @Test
    fun `preserves isolates with ISOLATE status, null cluster index and null similarity`() {
        val v1 = createVector("fen_a", mapOf("P(e4)" to 1.0))
        val isolate = createVector("fen_iso", mapOf("P(h4)" to 1.0))

        val result =
            evaluator.evaluateClusters(
                vectors = listOf(v1, isolate),
                communityAssignments =
                    mapOf(
                        v1.positionId to 0,
                        isolate.positionId to null,
                    ),
                centroidThreshold = 0.38,
            )

        val isolateAssignment = result.assignments.first { it.positionId == isolate.positionId }
        assertEquals(ClusterAssignmentStatus.ISOLATE, isolateAssignment.status)
        assertNull(isolateAssignment.clusterIndex)
        assertNull(isolateAssignment.centroidSimilarity)
    }

    @Test
    fun `assigns member when centroid similarity is exactly equal to threshold`() {
        val v1 = createVector("fen_a", mapOf("P(e4)" to 1.0, "N(f3)" to 1.0))
        val v2 = createVector("fen_b", mapOf("P(e4)" to 1.0, "N(f3)" to 1.0))

        val simResult =
            evaluator.evaluateClusters(
                vectors = listOf(v1, v2),
                communityAssignments = mapOf(v1.positionId to 0, v2.positionId to 0),
                centroidThreshold = 0.0,
            )
        val actualSim = simResult.assignments[0].centroidSimilarity!!

        val boundaryResult =
            evaluator.evaluateClusters(
                vectors = listOf(v1, v2),
                communityAssignments = mapOf(v1.positionId to 0, v2.positionId to 0),
                centroidThreshold = actualSim,
            )

        val a1 = boundaryResult.assignments.first { it.positionId == v1.positionId }
        assertEquals(ClusterAssignmentStatus.ASSIGNED, a1.status)
        assertEquals(0, a1.clusterIndex)
    }
}
