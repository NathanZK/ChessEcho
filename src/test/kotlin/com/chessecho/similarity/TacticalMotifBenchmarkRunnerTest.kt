package com.chessecho.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path

class TacticalMotifBenchmarkRunnerTest {
    @Test
    fun `tampered corpus digest throws IllegalStateException`() {
        val runner = TacticalMotifBenchmarkRunner()
        assertThrows(IllegalStateException::class.java) {
            runner.verifyCorpusDigest("corrupt-expected-sha256", "some text content".toByteArray())
        }
    }

    @Test
    fun `benchmark executes all 4 configurations on original pool without mutating historical artifact`() {
        val runner = TacticalMotifBenchmarkRunner()
        val originalFile = File("docs/experiments/tactical-motif-v1-results.json")
        val runPath =
            if (originalFile.exists()) {
                File.createTempFile("tactical-motif-v1-orig-run", ".json").apply { deleteOnExit() }.toPath()
            } else {
                originalFile.toPath()
            }

        val result = runner.runBenchmark(outputPath = runPath)

        assertNotNull(result)
        assertEquals(32, result.totalQueries)
        assertEquals(4, result.configurations.size)

        val configs = result.configurations.keys
        assertTrue(configs.contains("baseline_ganguly_static"))
        assertTrue(configs.contains("bizjak_static_only"))
        assertTrue(configs.contains("bizjak_dynamic_only"))
        assertTrue(configs.contains("bizjak_combined"))

        result.configurations.values.forEach { configResult ->
            assertTrue(configResult.macroHitAt1 in 0.0..1.0)
            assertTrue(configResult.macroHitAt5 in 0.0..1.0)
            assertTrue(configResult.macroHitAt10 in 0.0..1.0)
            assertTrue(configResult.macroMrr in 0.0..1.0)
            assertEquals(32, configResult.perQueryResults.size)
        }

        assertTrue(originalFile.exists(), "Original historical results file must exist")
    }

    @Test
    fun `benchmark executes all 4 configurations on independent pool`() {
        val runner = TacticalMotifBenchmarkRunner()
        val independentFile = File("docs/experiments/tactical-motif-v1-independent-results.json")
        val runPath =
            if (independentFile.exists()) {
                File.createTempFile("tactical-motif-v1-indep-run", ".json").apply { deleteOnExit() }.toPath()
            } else {
                independentFile.toPath()
            }

        val result =
            runner.runBenchmark(
                frozenPoolsPath = Path.of("src/test/resources/tactical-motif-independent-pools.json"),
                digestPath = Path.of("docs/experiments/tactical-motif-independent-pools.sha256"),
                outputPath = runPath,
            )

        assertNotNull(result)
        assertEquals(32, result.totalQueries)
        assertEquals(4, result.configurations.size)

        val configs = result.configurations.keys
        assertTrue(configs.contains("baseline_ganguly_static"))
        assertTrue(configs.contains("bizjak_static_only"))
        assertTrue(configs.contains("bizjak_dynamic_only"))
        assertTrue(configs.contains("bizjak_combined"))

        result.configurations.values.forEach { configResult ->
            assertTrue(configResult.macroHitAt1 in 0.0..1.0)
            assertTrue(configResult.macroHitAt5 in 0.0..1.0)
            assertTrue(configResult.macroHitAt10 in 0.0..1.0)
            assertTrue(configResult.macroMrr in 0.0..1.0)
            assertEquals(32, configResult.perQueryResults.size)
        }
    }
}
