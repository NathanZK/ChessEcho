package com.chessecho.research

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class Depth8Rank1ConsistencyTest {

    private val mapper = jacksonObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Test
    fun `test cross-configuration rank-1 consistency and multipv contributions`() {
        val cache1File = File("src/test/resources/research/engine_evaluation_cache_d8_mpv1.json")
        val cache3File = File("src/test/resources/research/engine_evaluation_cache_d8_mpv3.json")
        val cache5File = File("src/test/resources/research/engine_evaluation_cache_d8_mpv5.json")

        assertTrue(cache1File.exists(), "MPV1 cache must exist")
        assertTrue(cache3File.exists(), "MPV3 cache must exist")
        assertTrue(cache5File.exists(), "MPV5 cache must exist")

        val typeRef = object : TypeReference<Map<String, Depth8CachedEngineRecord>>() {}
        println("Loading cache 1 (${cache1File.length() / 1024} KB)...")
        val cache1: Map<String, Depth8CachedEngineRecord> = mapper.readValue(cache1File, typeRef)
        println("Loading cache 3 (${cache3File.length() / 1024} KB)...")
        val cache3: Map<String, Depth8CachedEngineRecord> = mapper.readValue(cache3File, typeRef)
        println("Loading cache 5 (${cache5File.length() / 1024} KB)...")
        val cache5: Map<String, Depth8CachedEngineRecord> = mapper.readValue(cache5File, typeRef)

        assertEquals(10000, cache1.size, "MPV1 cache must have 10,000 entries")
        assertEquals(10000, cache3.size, "MPV3 cache must have 10,000 entries")
        assertEquals(10000, cache5.size, "MPV5 cache must have 10,000 entries")

        val analyzer = Depth8MultiPvContributionAnalyzer()

        println("=== Analyzing Rank-1 Consistency ===")
        val rank1Report = analyzer.analyzeRank1Consistency(cache1, cache3, cache5)
        println(mapper.writeValueAsString(rank1Report))

        assertEquals(10000, rank1Report.totalPositions)
        assertTrue(rank1Report.move1IdentityRate1vs3 >= 50.0, "Move 1 identity 1vs3 should be >= 50.0%: ${rank1Report.move1IdentityRate1vs3}%")
        assertTrue(rank1Report.move1IdentityRate1vs5 >= 50.0, "Move 1 identity 1vs5 should be >= 50.0%: ${rank1Report.move1IdentityRate1vs5}%")
        assertTrue(rank1Report.meanPvJaccard1vs3 > 0.2, "Mean PV Jaccard 1vs3 should be > 0.2: ${rank1Report.meanPvJaccard1vs3}")
        assertTrue(rank1Report.meanTokenJaccard1vs3 > 0.3, "Mean Token Jaccard 1vs3 should be > 0.3: ${rank1Report.meanTokenJaccard1vs3}")

        println("=== Analyzing MultiPV Contributions & Token Novelty ===")
        val contribReport = analyzer.analyzeMultiPvContributions(cache1, cache3, cache5)
        println(mapper.writeValueAsString(contribReport))

        assertEquals(10000, contribReport.totalPositions)
        assertTrue(contribReport.vocabularySizes["MPV1"]!! in 100..200)
        assertTrue(contribReport.vocabularySizes["MPV3"]!! in 100..200)
        assertTrue(contribReport.vocabularySizes["MPV5"]!! in 100..200)
        assertTrue(contribReport.meanDocumentLengths["MPV1"]!! < contribReport.meanDocumentLengths["MPV3"]!!)
        assertTrue(contribReport.meanDocumentLengths["MPV3"]!! < contribReport.meanDocumentLengths["MPV5"]!!)
        assertTrue(contribReport.meanTokenNovelty["MPV3"]!! > 0.0)
        assertTrue(contribReport.meanTokenNovelty["MPV5"]!! > 0.0)

        // Save consistency report artifact for T7 synthesis
        val reportFile = File("src/test/resources/research/depth8_multipv_consistency_results.json")
        val combinedResults = mapOf(
            "rank1Consistency" to rank1Report,
            "multiPvContributions" to contribReport,
        )
        mapper.writeValue(reportFile, combinedResults)
        println("Saved consistency report to ${reportFile.absolutePath}")
    }
}
