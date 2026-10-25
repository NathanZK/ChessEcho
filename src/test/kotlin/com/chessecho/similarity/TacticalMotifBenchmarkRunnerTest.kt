package com.chessecho.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class TacticalMotifBenchmarkRunnerTest {
    @Test
    fun `tampered corpus digest throws IllegalStateException`() {
        val runner = TacticalMotifBenchmarkRunner()
        assertThrows(IllegalStateException::class.java) {
            runner.verifyCorpusDigest("corrupt-expected-sha256", "some text content".toByteArray())
        }
    }

    @Test
    fun `benchmark executes all 4 configurations and writes valid results`() {
        val runner = TacticalMotifBenchmarkRunner()
        val resultsFile = File("docs/experiments/tactical-motif-v1-results.json")
        val result = runner.runBenchmark(outputPath = resultsFile.toPath())

        assertNotNull(result)
        assertEquals(32, result.totalQueries)
        assertEquals(4, result.configurations.size)

        val configs = result.configurations.keys
        assertTrue(configs.contains("baseline_ganguly_static"))
        assertTrue(configs.contains("bizjak_static_only"))
        assertTrue(configs.contains("bizjak_dynamic_only"))
        assertTrue(configs.contains("bizjak_combined"))

        // Verify metrics are in valid ranges [0.0, 1.0]
        result.configurations.values.forEach { configResult ->
            assertTrue(configResult.macroHitAt1 in 0.0..1.0)
            assertTrue(configResult.macroHitAt5 in 0.0..1.0)
            assertTrue(configResult.macroHitAt10 in 0.0..1.0)
            assertTrue(configResult.macroMrr in 0.0..1.0)
            assertEquals(32, configResult.perQueryResults.size)
        }

        assertTrue(resultsFile.exists(), "Results file must be written to disk")
        assertTrue(resultsFile.length() > 0, "Results file must not be empty")
    }
}
