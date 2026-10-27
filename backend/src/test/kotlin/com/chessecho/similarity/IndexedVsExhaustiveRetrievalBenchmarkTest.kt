package com.chessecho.similarity

import com.github.bhlangonijr.chesslib.Board
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.system.measureNanoTime

class IndexedVsExhaustiveRetrievalBenchmarkTest {
    private val extractor = RelationalPawnTokenExtractor()
    private val scorer = PositionSimilarityScorer()

    @Test
    fun `benchmark executes on 1000 personal positions verifying recall and latency targets`() {
        val corpusSize = 1000
        val rawPositions = generateDistinctPositions(corpusSize)
        assertTrue(rawPositions.size >= corpusSize, "Corpus must have at least 1,000 positions")

        val index = PositionInvertedIndex()
        val coldStartNs = System.nanoTime()
        val documents =
            rawPositions.map { fen ->
                extractor.extractDocument(UUID.randomUUID(), fen, includeReachability = true)
            }
        index.index(documents)
        val coldIndexingTimeMs = (System.nanoTime() - coldStartNs) / 1_000_000.0

        val queryFens = getSampleQueryFens()
        warmup(index, queryFens)

        val (latenciesMs, recallValues) = evaluateQueries(index, documents, queryFens)
        val sortedLatencies = latenciesMs.sorted()
        val p95 = sortedLatencies[(sortedLatencies.size * 0.95).toInt()]
        val avgRecall = recallValues.average()

        printBenchmarkReport(corpusSize, coldIndexingTimeMs, sortedLatencies, avgRecall)

        assertTrue(avgRecall >= 0.90, "Top-20 recall must be >= 90%, was ${avgRecall * 100}%")
        assertTrue(p95 < 500.0, "p95 query latency must be < 500ms, was $p95 ms")
    }

    private fun warmup(
        index: PositionInvertedIndex,
        queryFens: List<String>,
    ) {
        for (i in 0 until 50) {
            val qFen = queryFens[i % queryFens.size]
            val queryDoc = extractor.extractDocument(UUID.randomUUID(), qFen, includeReachability = false)
            val candidateDocs = index.queryCandidates(queryDoc.terms.keys, maxCandidates = 200)
            scorer.score(queryDoc, candidateDocs)
        }
    }

    private fun evaluateQueries(
        index: PositionInvertedIndex,
        documents: List<PositionDocument>,
        queryFens: List<String>,
    ): Pair<List<Double>, List<Double>> {
        val latenciesMs = mutableListOf<Double>()
        val recallValues = mutableListOf<Double>()

        for (queryFen in queryFens) {
            val queryDoc = extractor.extractDocument(UUID.randomUUID(), queryFen, includeReachability = false)
            val exhaustiveRanked = scorer.score(queryDoc, documents)
            val targetK = minOf(20, exhaustiveRanked.size)
            val exhaustiveTopK = exhaustiveRanked.take(targetK).map { it.document.canonicalFen }.toSet()

            for (run in 0 until 10) {
                var candidateDocs: List<PositionDocument>
                val elapsedNs =
                    measureNanoTime {
                        candidateDocs = index.queryCandidates(queryDoc.terms.keys, maxCandidates = 200)
                        scorer.score(queryDoc, candidateDocs)
                    }
                latenciesMs.add(elapsedNs / 1_000_000.0)

                val candidateFens = candidateDocs.map { it.canonicalFen }.toSet()
                val hits = exhaustiveTopK.count { it in candidateFens }
                val recall = if (exhaustiveTopK.isNotEmpty()) hits.toDouble() / exhaustiveTopK.size else 1.0
                recallValues.add(recall)
            }
        }
        return Pair(latenciesMs, recallValues)
    }

    private fun printBenchmarkReport(
        corpusSize: Int,
        coldIndexingTimeMs: Double,
        sortedLatencies: List<Double>,
        avgRecall: Double,
    ) {
        val p50 = sortedLatencies[(sortedLatencies.size * 0.50).toInt()]
        val p95 = sortedLatencies[(sortedLatencies.size * 0.95).toInt()]
        val p99 = sortedLatencies[(sortedLatencies.size * 0.99).toInt()]
        val minLatency = sortedLatencies.first()
        val meanLatency = sortedLatencies.average()

        println(
            """
            |=== Indexed vs Exhaustive Retrieval Benchmark ===
            |Corpus Size: $corpusSize positions
            |Cold Build & Index Time: ${"%.2f".format(coldIndexingTimeMs)} ms
            |Environment: OS ${System.getProperty(
                "os.name",
            )}, Java ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} cores
            |Latency Distribution (100 warm queries):
            |  Min:    ${"%.2f".format(minLatency)} ms
            |  Median: ${"%.2f".format(p50)} ms
            |  Mean:   ${"%.2f".format(meanLatency)} ms
            |  p95:    ${"%.2f".format(p95)} ms
            |  p99:    ${"%.2f".format(p99)} ms
            |Average Top-20 Recall: ${"%.2f".format(avgRecall * 100)}%
            |Note: Retrieval recall measures algorithmic retention of top-scoring candidates under inverted indexing.
            |Passing recall is reported separately from and does not claim evidence of human chess-similarity usefulness.
            |=================================================
            """.trimMargin(),
        )
    }

    private fun getSampleQueryFens(): List<String> =
        listOf(
            "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/3P1N2/PPP2PPP/RNBQK2R w KQkq - 1 5",
            "rnbqkb1r/pp2pppp/3p1n2/8/3NP3/8/PPP2PPP/RNBQKB1R w KQkq - 1 5",
            "r1bqkb1r/pp3ppp/2n1pn2/2pp4/3P4/2N1PN2/PPP1BPPP/R1BQK2R w KQkq - 2 6",
            "r1bq1rk1/ppp1bppp/2n1pn2/3p4/2PP4/2N1PN2/PP2BPPP/R1BQ1RK1 w - - 4 8",
            "r2q1rk1/pp1b1ppp/2n1pn2/2pp4/2PP4/2N1PN2/PP2BPPP/R1BQ1RK1 w - - 6 9",
            "r1b1k2r/pp2bppp/1qn1pn2/3p4/2PP4/2N1PN2/PP2BPPP/R1BQK2R w KQkq - 3 8",
            "r1bqk2r/1pp1bppp/p1n1pn2/3p4/2PP4/2N1PN2/PP2BPPP/R1BQK2R w KQkq - 0 7",
            "r1bq1rk1/pp2bppp/2n1pn2/2pp2B1/2PP4/2N1PN2/PP2BPPP/R2Q1RK1 w - - 5 9",
            "r2qk2r/pp1nbppp/2p1pn2/3p4/2PP4/2N1PN2/PP2BPPP/R1BQK2R w KQkq - 2 8",
            "r1bq1rk1/pp2bppp/2n1p3/3p4/2PPn3/2N1PN2/PP2BPPP/R1BQ1RK1 w - - 1 9",
        )

    private fun generateDistinctPositions(targetCount: Int): List<String> {
        val uniqueFens = mutableSetOf<String>()
        val queue = ArrayDeque<Board>()
        val startBoard = Board()
        queue.add(startBoard)
        uniqueFens.add(startBoard.fen)

        while (queue.isNotEmpty() && uniqueFens.size < targetCount) {
            val board = queue.removeFirst()
            expandBoard(board, queue, uniqueFens, targetCount)
        }
        return uniqueFens.toList()
    }

    private fun expandBoard(
        board: Board,
        queue: ArrayDeque<Board>,
        uniqueFens: MutableSet<String>,
        targetCount: Int,
    ) {
        for (move in board.legalMoves()) {
            val nextBoard = Board()
            nextBoard.loadFromFen(board.fen)
            nextBoard.doMove(move)
            if (uniqueFens.add(nextBoard.fen)) {
                if (queue.size < 4000) {
                    queue.add(nextBoard)
                }
                if (uniqueFens.size >= targetCount) {
                    return
                }
            }
        }
    }
}
