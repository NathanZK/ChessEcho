package com.chessprobe.similarity.clustering

import com.chessprobe.similarity.DynamicInvertedIndex
import com.chessprobe.similarity.DynamicTacticalScorer
import com.chessprobe.similarity.PositionAnalysisOutcome
import com.chessprobe.similarity.PositionDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.ln
import kotlin.math.sqrt

class DynamicClusteringWorkedExampleTest {
    private val scorer = DynamicTacticalScorer()
    private val similarityScorer = PositionClusterSimilarityScorer()

    @Test
    fun `reproduce worked example end to end`() {
        val fenA = "r1bq1rk1/ppp2ppp/2n1pn2/3p4/3P4/2NBPN2/PPP2PPP/R1BQK2R w KQ - 0 7"
        val resolvedA = listOf("d3h7", "g8h7", "f3g5", "h7g8")
        val fenB = "r2q1rk1/pp1n1ppp/2p1pn2/8/1b1P4/2NBPN2/PPP2PPP/R1BQK2R w KQ - 2 9"
        val resolvedB = listOf("d3h7", "g8h7", "f3g5", "h7g6")

        val tokensA = scorer.extractDynamicTokens(fenA, resolvedA)
        val tokensB = scorer.extractDynamicTokens(fenB, resolvedB)
        assertSharedMotifs(tokensA, tokensB)

        val idA = UUID.randomUUID()
        val idB = UUID.randomUUID()
        val docA =
            PositionDocument(
                idA,
                fenA,
                fenA.substringBefore(" 0 7"),
                tokensA,
                analysisOutcome = PositionAnalysisOutcome.EVALUATED_WITH_TOKENS,
            )
        val docB =
            PositionDocument(
                idB,
                fenB,
                fenB.substringBefore(" 2 9"),
                tokensB,
                analysisOutcome = PositionAnalysisOutcome.EVALUATED_WITH_TOKENS,
            )
        val (vecA, vecB) = buildDynamicVectors(listOf(docA, docB))

        val index = DynamicInvertedIndex(listOf(docA, docB))
        val candidatesA = index.retrieveCandidates(docA.terms.keys, docA.sideToMove, maxCandidates = 10)
        assertTrue(candidatesA.any { it.positionId == idB }, "Position B should be shortlisted for Position A")

        val sim = similarityScorer.cosineSimilarity(vecA, vecB)
        assertTrue(sim >= 0.40, "Cosine similarity should exceed 0.40, was $sim")

        val graph =
            RelaxedMnnGraphBuilder(similarityScorer).buildGraph(
                listOf(vecA, vecB),
                mapOf(idA to listOf(idB), idB to listOf(idA)),
                topK = 10,
                floorThreshold = 0.40,
                top1Threshold = 0.65,
            )
        assertEquals(1, graph.edges.size, "Expected 1 mutual edge between Position A and B")

        val detection = PositionLeidenCommunityDetector().detectCommunities(graph, rbResolution = 1.25)
        val clusterCount =
            detection.assignments.values
                .filterNotNull()
                .distinct()
                .size
        assertEquals(1, clusterCount, "Both positions should be clustered into 1 community")
        assertEquals(detection.assignments[idA], detection.assignments[idB], "Both positions must share cluster")
    }

    private fun assertSharedMotifs(
        tokensA: Map<String, Double>,
        tokensB: Map<String, Double>,
    ) {
        val requiredKeys = listOf("?px", "?S", "!SB", "!xP", "!B>K", "!N>K", "!-BK")
        for (key in requiredKeys) {
            assertTrue(tokensA.containsKey(key), "Expected Position A to contain $key")
            assertTrue(tokensB.containsKey(key), "Expected Position B to contain $key")
        }
    }

    private fun buildDynamicVectors(docs: List<PositionDocument>): List<PositionClusterVector> {
        val totalDocs = docs.size
        val dfMap = mutableMapOf<String, Int>()
        docs.forEach { d -> d.terms.keys.forEach { t -> dfMap.merge(t, 1, Int::plus) } }
        val idfMap = dfMap.mapValues { (_, df) -> ln(1.0 + ((totalDocs - df + 0.5) / (df + 0.5))) }

        return docs.map { doc ->
            val weighted = mutableMapOf<String, Double>()
            doc.terms.forEach { (t, tf) ->
                val idf = idfMap[t] ?: 0.0
                val w = tf * idf
                if (w > 0.0) weighted[t] = w
            }
            val norm = sqrt(weighted.values.sumOf { it * it })
            val normalized = weighted.mapValues { (_, w) -> w / norm }
            PositionClusterVector(doc.positionId, doc.canonicalFen, doc.terms, normalized, 0.0, 1.0, 1.0)
        }
    }
}
