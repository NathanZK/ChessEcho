package com.chessecho.research

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class Depth8MultiPvTimingPilotTest {

    private val binaryPath = "/opt/homebrew/bin/stockfish"

    @Test
    fun `test execute 50-position timing pilot and verify runtime feasibility`() {
        if (!File(binaryPath).exists()) {
            println("Skipping timing pilot: $binaryPath not found")
            return
        }

        val runner = Depth8MultiPvTimingPilotRunner(binaryPath = binaryPath)
        val results = runner.runPilot()

        assertNotNull(results)
        assertEquals("PASSED", results.metadata.status, "Pilot status must be PASSED")
        assertEquals(50, results.metadata.sampleCount)
        assertEquals(6, results.metadata.workerCount)

        // Verify feasibility gates
        assertFalse(results.feasibilitySummary.gateBreached, "Feasibility gate must not be breached")
        assertTrue(
            results.feasibilitySummary.combinedCorpusProjectedWallClockMinutes <= 60.0,
            "Combined projected wall-clock runtime (${results.feasibilitySummary.combinedCorpusProjectedWallClockMinutes} min) must be <= 60 minutes"
        )

        // Verify individual configurations
        val mpv1 = results.configurations["MPV1"]
        val mpv3 = results.configurations["MPV3"]
        val mpv5 = results.configurations["MPV5"]

        assertNotNull(mpv1)
        assertNotNull(mpv3)
        assertNotNull(mpv5)

        assertTrue(mpv5!!.totalLatencyMs.mean <= 1800.0, "MPV5 mean latency (${mpv5.totalLatencyMs.mean} ms) must be <= 1,800 ms")

        println("=== Depth-8 MultiPV Timing Pilot Results ===")
        println("MPV1 Mean Latency: ${mpv1!!.totalLatencyMs.mean} ms (Median: ${mpv1.totalLatencyMs.median} ms, P90: ${mpv1.totalLatencyMs.p90} ms)")
        println("MPV3 Mean Latency: ${mpv3!!.totalLatencyMs.mean} ms (Median: ${mpv3.totalLatencyMs.median} ms, P90: ${mpv3.totalLatencyMs.p90} ms)")
        println("MPV5 Mean Latency: ${mpv5.totalLatencyMs.mean} ms (Median: ${mpv5.totalLatencyMs.median} ms, P90: ${mpv5.totalLatencyMs.p90} ms)")
        println("Combined Mean Latency: ${results.feasibilitySummary.combinedCorpusMeanLatencyMs} ms")
        println("Projected 6-Worker Full Corpus Wall Clock Time: ${results.feasibilitySummary.combinedCorpusProjectedWallClockMinutes} minutes (Ceiling: 60.0 min)")
        println("Status: ${results.metadata.status}")
    }
}
