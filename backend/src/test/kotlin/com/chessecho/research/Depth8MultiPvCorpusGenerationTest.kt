package com.chessecho.research

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class Depth8MultiPvCorpusGenerationTest {

    private val binaryPath = "/opt/homebrew/bin/stockfish"

    @Test
    fun `test batch generate Corpus A for MPV1 MPV3 and MPV5`() {
        if (!File(binaryPath).exists()) {
            println("Skipping corpus generation: $binaryPath not found")
            return
        }

        val generator = Depth8MultiPvCorpusGenerator(binaryPath = binaryPath, workerCount = 6)

        val cacheMpv1File = File("src/test/resources/research/engine_evaluation_cache_d8_mpv1.json")
        val cacheMpv3File = File("src/test/resources/research/engine_evaluation_cache_d8_mpv3.json")
        val cacheMpv5File = File("src/test/resources/research/engine_evaluation_cache_d8_mpv5.json")

        // 1. Generate Configuration A: MultiPV 1
        println("=== Starting Batch Generation: Configuration A (MultiPV 1) ===")
        val cache1 = generator.generateConfiguration(multiPv = 1, cacheFile = cacheMpv1File)
        assertEquals(10000, cache1.size, "MPV 1 cache must contain exactly 10,000 evaluated records")

        // 2. Generate Configuration B: MultiPV 3
        println("=== Starting Batch Generation: Configuration B (MultiPV 3) ===")
        val cache3 = generator.generateConfiguration(multiPv = 3, cacheFile = cacheMpv3File)
        assertEquals(10000, cache3.size, "MPV 3 cache must contain exactly 10,000 evaluated records")

        // 3. Generate Configuration C: MultiPV 5
        println("=== Starting Batch Generation: Configuration C (MultiPV 5) ===")
        val cache5 = generator.generateConfiguration(multiPv = 5, cacheFile = cacheMpv5File)
        assertEquals(10000, cache5.size, "MPV 5 cache must contain exactly 10,000 evaluated records")

        println("=== All 3 Configurations Successfully Generated & Persisted ===")
        println("MPV 1 Cache: ${cacheMpv1File.absolutePath} (${cacheMpv1File.length() / (1024 * 1024)} MB)")
        println("MPV 3 Cache: ${cacheMpv3File.absolutePath} (${cacheMpv3File.length() / (1024 * 1024)} MB)")
        println("MPV 5 Cache: ${cacheMpv5File.absolutePath} (${cacheMpv5File.length() / (1024 * 1024)} MB)")
    }
}
