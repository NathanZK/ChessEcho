package com.chessecho.research

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Depth8CacheSerializationTest {

    private val mapper = jacksonObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Test
    fun `test exact 15-parameter preimage format and field count`() {
        val canonicalFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq -"
        val normalizedFen = Depth8CacheKeyGenerator.normalizeFen(canonicalFen)
        assertEquals("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 1", normalizedFen)

        val preimage = Depth8CacheKeyGenerator.computePreimage(
            multiPv = 5,
            normalizedFen = normalizedFen,
            binarySha256 = "dc2f18c34ae962dff591b66147d220ec06e61d756b93d8a4f5e04fd8e55c251f"
        )

        // Split by colon and verify exactly 15 key-value pairs
        val fields = preimage.split(":")
        assertEquals(15, fields.size, "Preimage must contain exactly 15 key-value parameters")

        // Verify presence and order of all required parameters
        assertTrue(fields[0].startsWith("engine=Stockfish 19"))
        assertTrue(fields[1].startsWith("binHash=dc2f18c34ae962dff591b66147d220ec06e61d756b93d8a4f5e04fd8e55c251f"))
        assertEquals("depth=8", fields[2])
        assertEquals("multipv=5", fields[3])
        assertEquals("threads=1", fields[4])
        assertEquals("hash=64", fields[5])
        assertEquals("ponder=false", fields[6])
        assertEquals("syzygy=none", fields[7])
        assertEquals("analyseMode=true", fields[8])
        assertEquals("hashPolicy=clearHash", fields[9])
        assertEquals("ext=bounded_pv", fields[10])
        assertEquals("extLimits=d8_n5000_p10", fields[11])
        assertEquals("extTimeoutMs=10000", fields[12])
        assertEquals("tokenVersion=bizjak_v1", fields[13])
        assertEquals("fen=$normalizedFen", fields[14])

        // Verify SHA-256 key is a 64-character hex string
        val cacheKey = Depth8CacheKeyGenerator.computeCacheKey(5, normalizedFen)
        assertEquals(64, cacheKey.length)
        assertTrue(cacheKey.matches(Regex("^[a-f0-9]{64}$")))
    }

    @Test
    fun `test Depth8CachedEngineRecord JSON round trip`() {
        val record = Depth8CachedEngineRecord(
            canonicalFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq -",
            normalizedFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 1",
            depth = 8,
            multiPv = 5,
            bestMove = "f1c4",
            scoreCp = 35,
            principalVariations = listOf(
                Depth8CachedPvLine(
                    rank = 1,
                    scoreCp = 35,
                    moves = listOf("f1c4", "g8f6", "d2d3", "f8c5"),
                    nominalDepth = 8,
                    nominalMoves = listOf("f1c4", "g8f6", "d2d3"),
                    extensionMoves = listOf("f8c5"),
                    terminationReason = "QUIET_STOP",
                    nominalNodes = 1420L,
                    extensionNodes = 680L,
                    totalNodes = 2100L
                )
            ),
            dynamicTokens = mapOf("!-B" to 2.0, "!-N" to 1.0, "!-P" to 1.0),
            tokensByRank = mapOf(
                "1" to mapOf("!-B" to 1.0, "!-N" to 1.0, "!-P" to 1.0),
                "2" to mapOf("!-B" to 1.0)
            ),
            elapsedMs = 38L,
            timedOut = false
        )

        val json = mapper.writeValueAsString(record)
        val deserialized: Depth8CachedEngineRecord = mapper.readValue(json)

        assertEquals(record.canonicalFen, deserialized.canonicalFen)
        assertEquals(record.normalizedFen, deserialized.normalizedFen)
        assertEquals(record.multiPv, deserialized.multiPv)
        assertEquals(1, deserialized.principalVariations.size)
        val pv = deserialized.principalVariations[0]
        assertEquals("QUIET_STOP", pv.terminationReason)
        assertEquals(1420L, pv.nominalNodes)
        assertEquals(680L, pv.extensionNodes)
        assertEquals(2100L, pv.totalNodes)
        assertEquals(2.0, deserialized.dynamicTokens["!-B"])
        assertEquals(2, deserialized.tokensByRank.size)
    }

    @Test
    fun `test TimingPilotResults JSON round trip`() {
        val pilotResults = Depth8TimingPilotResults(
            metadata = PilotMetadata(
                generatedAt = "2026-11-06T12:00:00Z",
                sampleCount = 50,
                workerCount = 6,
                stockfishBinarySha256 = "dc2f...251f",
                status = "PASSED"
            ),
            feasibilitySummary = PilotFeasibilitySummary(
                combinedCorpusMeanLatencyMs = 384.2,
                combinedCorpusProjectedWallClockMinutes = 10.67,
                combinedCorpusCeilingMinutes = 60.0,
                gateBreached = false
            ),
            configurations = mapOf(
                "MPV1" to PilotConfigMetrics(
                    nominalDepth = 8,
                    multiPv = 1,
                    totalLatencyMs = PilotMetricStats(mean = 42.1, median = 38.0, p90 = 65.0, min = 21.0, max = 112.0),
                    nominalLatencyMs = PilotMetricStats(mean = 28.5, median = 26.0, p90 = 41.0),
                    extensionLatencyMs = PilotMetricStats(mean = 13.6, median = 12.0, p90 = 24.0),
                    nominalNodes = PilotMetricStats(mean = 1420.0, median = 1310.0, p90 = 2100.0),
                    extensionNodes = PilotMetricStats(mean = 680.0, median = 510.0, p90 = 1240.0),
                    totalNodes = PilotMetricStats(mean = 2100.0, median = 1820.0, p90 = 3340.0),
                    terminationReasonCounts = mapOf("QUIET_STOP" to 38, "TERMINAL" to 2, "PLY_LIMIT" to 4),
                    strataLatencyMs = mapOf(
                        "early-game" to PilotMetricStats(mean = 36.2, median = 34.0, p90 = 48.0),
                        "middle-game" to PilotMetricStats(mean = 48.4, median = 42.0, p90 = 72.0),
                        "late-game" to PilotMetricStats(mean = 35.1, median = 31.0, p90 = 52.0)
                    )
                )
            )
        )

        val json = mapper.writeValueAsString(pilotResults)
        val deserialized: Depth8TimingPilotResults = mapper.readValue(json)

        assertEquals("PASSED", deserialized.metadata.status)
        assertEquals(384.2, deserialized.feasibilitySummary.combinedCorpusMeanLatencyMs)
        assertFalse(deserialized.feasibilitySummary.gateBreached)
        assertNotNull(deserialized.configurations["MPV1"])
        assertEquals(42.1, deserialized.configurations["MPV1"]?.totalLatencyMs?.mean)
    }
}
