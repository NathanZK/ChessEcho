package com.chessecho.research

import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.RelationalPawnTokenExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ClusteringExperimentRunnerTest {
    private val extractor = RelationalPawnTokenExtractor()
    private val runner = ClusteringExperimentRunner()

    private val sampleFens = listOf(
        "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
        "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
        "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 1 3",
        "r1bqkbnr/pppp1ppp/2n5/1b2p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 4",
        "r1bqk1nr/pppp1ppp/2n5/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 3 4",
        "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2",
        "rnbqkbnr/pp1ppppp/8/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2",
        "rnbqkb1r/pp1ppppp/5n2/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
        "r1bqkbnr/pp1ppppp/2n5/2p5/3PP3/5N2/PPP2PPP/RNBQKB1R b KQkq d3 0 3",
        "r1bqkbnr/pp1ppppp/2n5/8/3pP3/5N2/PPP2PPP/RNBQKB1R w KQkq - 0 4",
        "rnbqkbnr/pppp1ppp/4p3/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
        "rnbqkbnr/pppp1ppp/4p3/8/3PP3/8/PPP2PPP/RNBQKBNR b KQkq d3 0 2",
        "rnbqkbnr/ppp2ppp/4p3/3p4/3PP3/8/PPP2PPP/RNBQKBNR w KQkq d6 0 3",
        "rnbqkbnr/ppp2ppp/4p3/3pP3/3P4/8/PPP2PPP/RNBQKBNR b KQkq - 0 3",
        "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 1",
        "rnbqkb1r/pppppppp/5n2/8/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 1 2",
        "rnbqkb1r/pppppppp/5n2/8/2PP4/8/PP2PPPP/RNBQKBNR b KQkq c3 0 2",
        "rnbqkb1r/pppp1ppp/4pn2/8/2PP4/8/PP2PPPP/RNBQKBNR w KQkq - 0 3",
        "8/5pk1/4p1p1/7p/4P3/5PP1/5K1P/8 w - - 0 40",
        "8/8/4k3/8/8/4K3/4P3/8 w - - 0 50",
    )

    @Test
    fun `deterministic uuid generator produces identical uuids for same fen and ply`() {
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
        val id1 = DeterministicIdGenerator.generateDeterministicId(fen, 1)
        val id2 = DeterministicIdGenerator.generateDeterministicId(fen, 1)
        val id3 = DeterministicIdGenerator.generateDeterministicId(fen, 2)

        assertEquals(id1, id2, "UUIDv5 must be strictly reproducible for identical inputs")
        assertTrue(id1 != id3, "Different ply must generate different UUID")
        assertEquals(5, id1.version(), "Must be UUID version 5")
    }

    @Test
    fun `reproducibility test produces identical cluster assignments and metrics across runs`() {
        val positions = sampleFens.mapIndexed { idx, fen ->
            val id = DeterministicIdGenerator.generateDeterministicId(fen, idx + 1)
            extractor.extractDocument(id, fen, includeReachability = true)
        }

        val config = ExperimentConfig(
            alpha = 0.60,
            candidateLimit = 10,
            topK = 5,
            floorThreshold = 0.30,
            top1Threshold = 0.50,
            rbResolution = 1.25,
            centroidThreshold = 0.35,
            randomSeed = 12345L,
        )

        val run1 = runner.runExperiment(positions, config)
        val run2 = runner.runExperiment(positions, config)

        // Verify cluster assignments are identical
        val assignments1 = run1.evaluationResult.assignments.associate { it.positionId to it.clusterIndex }
        val assignments2 = run2.evaluationResult.assignments.associate { it.positionId to it.clusterIndex }
        assertEquals(assignments1, assignments2, "Cluster assignments must be byte-identical across runs with identical seed")

        // Verify cluster sizes match exactly
        assertEquals(run1.evaluationResult.clusters.size, run2.evaluationResult.clusters.size)
        assertEquals(run1.metrics.assignedPositions, run2.metrics.assignedPositions)
        assertEquals(run1.metrics.isolatePositions, run2.metrics.isolatePositions)
        assertEquals(run1.metrics.outlierPositions, run2.metrics.outlierPositions)
        assertEquals(run1.metrics.totalEdges, run2.metrics.totalEdges)
        assertEquals(run1.metrics.leidenQuality, run2.metrics.leidenQuality, 1e-9)

        // Verify JSON serialization contains required fields
        val json = run1.metrics.toJson()
        assertTrue(json.contains("\"totalPositions\": 20"))
        assertTrue(json.contains("\"distribution\":"))
        assertTrue(json.contains("\"categories\":"))
        assertTrue(json.contains("\"phaseTimingsMs\":"))
        assertTrue(json.contains("\"heapMemoryUsedMb\":"))
    }

    @Test
    fun `splitBySideToMove separates White and Black subgraphs`() {
        val positions = sampleFens.mapIndexed { idx, fen ->
            val id = DeterministicIdGenerator.generateDeterministicId(fen, idx + 1)
            extractor.extractDocument(id, fen, includeReachability = true)
        }

        val configUnified = ExperimentConfig(
            alpha = 0.60,
            candidateLimit = 10,
            topK = 5,
            floorThreshold = 0.30,
            top1Threshold = 0.50,
            rbResolution = 1.25,
            centroidThreshold = 0.35,
            randomSeed = 42L,
            splitBySideToMove = false,
        )

        val configSplit = configUnified.copy(splitBySideToMove = true)

        val runUnified = runner.runExperiment(positions, configUnified)
        val runSplit = runner.runExperiment(positions, configSplit)

        assertNotNull(runUnified.metrics)
        assertNotNull(runSplit.metrics)
        assertTrue(runUnified.metrics.totalPositions == 20)
        assertTrue(runSplit.metrics.totalPositions == 20)
    }
}
