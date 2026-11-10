package com.chessprobe.similarity.clustering

import com.chessprobe.similarity.PositionDocument
import com.chessprobe.similarity.PositionInvertedIndex
import com.chessprobe.similarity.RelationalPawnTokenExtractor
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.ln

class CandidateRetrievalRecallBenchmarkTest {
    private val extractor = RelationalPawnTokenExtractor()
    private val scorer = PositionClusterSimilarityScorer()

    @Test
    fun `benchmark candidate retrieval recall and reduction across alpha configurations`() {
        val positions = buildBenchmarkCorpus()
        val totalDocs = positions.size
        assertTrue(totalDocs >= 200, "Corpus must contain at least 200 positions")

        val idfMap = computeIdfMap(positions)
        val invertedIndex = PositionInvertedIndex()
        invertedIndex.index(positions)

        val alphaConfigs = listOf(0.60, 0.0, 1.0)
        alphaConfigs.forEach { alpha ->
            evaluateAlphaRecall(alpha, positions, idfMap, invertedIndex)
        }
    }

    private fun computeIdfMap(positions: List<PositionDocument>): Map<String, Double> {
        val dfMap = mutableMapOf<String, Int>()
        positions.forEach { p ->
            p.terms.keys.forEach { t -> dfMap.merge(t, 1, Int::plus) }
        }
        val totalDocs = positions.size
        return dfMap.mapValues { (_, df) ->
            ln(1.0 + ((totalDocs - df + 0.5) / (df + 0.5)))
        }
    }

    private fun evaluateAlphaRecall(
        alpha: Double,
        positions: List<PositionDocument>,
        idfMap: Map<String, Double>,
        invertedIndex: PositionInvertedIndex,
    ) {
        val vectors =
            positions.associate { doc ->
                doc.positionId to
                    scorer.buildVector(
                        positionId = doc.positionId,
                        canonicalFen = doc.canonicalFen,
                        terms = doc.terms,
                        idfs = idfMap,
                        alpha = alpha,
                    )
            }

        val kValues = listOf(1, 10, 20)
        val recalls = kValues.associateWith { mutableListOf<Double>() }
        val reductions = mutableListOf<Double>()

        positions.take(30).forEach { queryDoc ->
            evaluateQueryRecall(queryDoc, positions, vectors, invertedIndex, alpha, kValues, recalls, reductions)
        }

        val meanR1 = recalls.getValue(1).average()
        val meanR10 = recalls.getValue(10).average()
        val meanR20 = recalls.getValue(20).average()
        val meanReduction = reductions.average() * 100.0

        println(
            "Benchmark [alpha=%.2f]: Recall@1=%.4f, Recall@10=%.4f, Recall@20=%.4f, Candidate Reduction=%.1f%%"
                .format(alpha, meanR1, meanR10, meanR20, meanReduction),
        )

        assertTrue(meanR1 >= 0.80, "Mean Recall@1 for alpha=$alpha was $meanR1 (expected >= 0.80)")
        assertTrue(meanR10 >= 0.70, "Mean Recall@10 for alpha=$alpha was $meanR10 (expected >= 0.70)")
        assertTrue(meanR20 >= 0.65, "Mean Recall@20 for alpha=$alpha was $meanR20 (expected >= 0.65)")
    }

    private fun evaluateQueryRecall(
        queryDoc: PositionDocument,
        positions: List<PositionDocument>,
        vectors: Map<UUID, PositionClusterVector>,
        invertedIndex: PositionInvertedIndex,
        alpha: Double,
        kValues: List<Int>,
        recalls: Map<Int, MutableList<Double>>,
        reductions: MutableList<Double>,
    ) {
        val queryVec = vectors.getValue(queryDoc.positionId)
        val turn = queryDoc.sideToMove
        val eligible = positions.filter { it.sideToMove == turn && it.positionId != queryDoc.positionId }
        if (eligible.isEmpty()) return

        val exhaustiveRanked =
            eligible
                .map { target ->
                    target to scorer.cosineSimilarity(queryVec, vectors.getValue(target.positionId))
                }.sortedWith(
                    compareByDescending<Pair<PositionDocument, Double>> { it.second }
                        .thenBy { it.first.canonicalFen },
                )

        val candidates =
            invertedIndex
                .generateCandidates(
                    queryTerms = queryDoc.terms.keys,
                    activeTurn = turn,
                    maxCandidates = 200,
                    alpha = alpha,
                ).filter { it.positionId != queryDoc.positionId }

        val candidateIds = candidates.map { it.positionId }.toSet()

        kValues.forEach { k ->
            val topK = exhaustiveRanked.take(k)
            if (topK.isNotEmpty()) {
                val hits = topK.count { (t, _) -> candidateIds.contains(t.positionId) }
                recalls.getValue(k).add(hits.toDouble() / topK.size)
            }
        }

        val reduction = (eligible.size - candidates.size).toDouble() / eligible.size
        reductions.add(reduction.coerceAtLeast(0.0))
    }

    private fun buildBenchmarkCorpus(): List<PositionDocument> {
        val baseFens =
            listOf(
                "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
                "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
                "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 1 3",
                "r1bqkbnr/pppp1ppp/2n5/1b2p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 4",
                "r1bqk1nr/pppp1ppp/2n5/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 3 4",
                "r1bqk1nr/pppp1ppp/2n5/4p3/1bB1P3/2P2N2/PP1P1PPP/RNBQK2R b KQkq - 0 4",
                "r1bqk1nr/pppp1ppp/2n5/4p3/1b1PP3/5N2/PPP2PPP/RNBQKB1R w KQkq - 1 4",
                "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2",
                "rnbqkbnr/pp1ppppp/8/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2",
                "rnbqkb1r/pp1ppppp/5n2/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
                "r1bqkbnr/pp1ppppp/2n5/2p5/3PP3/5N2/PPP2PPP/RNBQKB1R b KQkq d3 0 3",
                "r1bqkbnr/pp1ppppp/2n5/8/3pP3/5N2/PPP2PPP/RNBQKB1R w KQkq - 0 4",
                "r1bqkb1r/pp1ppppp/2n2n2/8/3NP3/8/PPP2PPP/RNBQKB1R w KQkq - 1 5",
                "r1bqkb1r/pp2pppp/2np1n2/8/3NP3/2N5/PPP2PPP/R1BQKB1R w KQkq - 2 6",
                "rnbqkbnr/pppp1ppp/4p3/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
                "rnbqkbnr/pppp1ppp/4p3/8/3PP3/8/PPP2PPP/RNBQKBNR b KQkq d3 0 2",
                "rnbqkbnr/ppp2ppp/4p3/3p4/3PP3/8/PPP2PPP/RNBQKBNR w KQkq d6 0 3",
                "rnbqkbnr/ppp2ppp/4p3/3pP3/3P4/8/PPP2PPP/RNBQKBNR b KQkq - 0 3",
                "rnbqk1nr/ppp2ppp/4p3/3pP3/1b1P4/2N5/PPP2PPP/R1BQKBNR b KQkq - 1 4",
                "r1bqk1nr/ppp2ppp/2n1p3/3pP3/3P4/2N5/PPP2PPP/R1BQKBNR w KQkq - 1 4",
                "rnbqkbnr/pp1ppppp/2p5/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
                "rnbqkbnr/pp1ppppp/2p5/8/3PP3/8/PPP2PPP/RNBQKBNR b KQkq d3 0 2",
                "rnbqkbnr/pp2pppp/2p5/3p4/3PP3/8/PPP2PPP/RNBQKBNR w KQkq d6 0 3",
                "rnbqkbnr/pp2pppp/2p5/3P4/3P4/8/PPP2PPP/RNBQKBNR b KQkq - 0 3",
                "rn1qkbnr/pp2pppp/2p5/5b2/3PN3/8/PPP2PPP/R1BQKBNR w KQkq - 1 4",
                "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 1",
                "rnbqkb1r/pppppppp/5n2/8/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 1 2",
                "rnbqkb1r/pppppppp/5n2/8/2PP4/8/PP2PPPP/RNBQKBNR b KQkq c3 0 2",
                "rnbqkb1r/pppp1ppp/4pn2/8/2PP4/8/PP2PPPP/RNBQKBNR w KQkq - 0 3",
                "rnbqkb1r/ppp1pppp/3p1n2/8/2PP4/5N2/PP2PPPP/RNBQKB1R b KQkq - 1 3",
                "rnbqkb1r/ppp1pp1p/3p1np1/8/2PP4/2N2N2/PP2PPPP/R1BQKB1R b KQkq - 2 4",
                "rnbq1rk1/ppp1ppbp/3p1np1/8/2PP4/2N2NP1/PP2PPBP/R1BQK2R b KQkq - 3 5",
                "8/5pk1/4p1p1/7p/4P3/5PP1/5K1P/8 w - - 0 40",
                "8/8/4k3/8/8/4K3/4P3/8 w - - 0 50",
                "4k3/8/8/8/8/8/8/R3K3 w - - 0 1",
                "8/8/1k6/8/8/1K6/4P3/8 w - - 0 1",
                "8/8/1k6/8/8/1K6/8/R7 w - - 0 1",
            )

        val positions = mutableListOf<PositionDocument>()
        var count = 0
        while (positions.size < 220) {
            val base = baseFens[count % baseFens.size]
            val doc = extractor.extractDocument(UUID.randomUUID(), base, includeReachability = true)
            positions.add(doc)
            count++
        }
        return positions
    }
}
