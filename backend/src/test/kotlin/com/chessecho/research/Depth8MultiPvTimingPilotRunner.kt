package com.chessecho.research

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.time.Instant
import kotlin.random.Random

@JsonIgnoreProperties(ignoreUnknown = true)
data class CorpusEntry(
    val canonicalFen: String,
    val samplePly: Int = 0,
    val sideToMove: String = "w",
)

class Depth8MultiPvTimingPilotRunner(
    private val binaryPath: String = "/opt/homebrew/bin/stockfish",
    private val corpusFile: File = File("src/test/resources/research/corpus_a.json"),
    private val outputFile: File = File("src/test/resources/research/depth8_multipv_timing_pilot_results.json"),
) {
    private val mapper = jacksonObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun runPilot(): Depth8TimingPilotResults {
        val corpus: List<CorpusEntry> = mapper.readValue(corpusFile)

        // Filter valid engine-supported positions then deterministic stratified sampling per §7.2
        val validCorpus = corpus.filter { Depth8CacheKeyGenerator.isSupportedByEngine(it.canonicalFen) }
        val early = validCorpus.filter { it.samplePly <= 12 }.sortedBy { it.canonicalFen }.shuffled(Random(42L)).take(15)
        val mid = validCorpus.filter { it.samplePly in 13..40 }.sortedBy { it.canonicalFen }.shuffled(Random(42L)).take(25)
        val late = validCorpus.filter { it.samplePly > 40 }.sortedBy { it.canonicalFen }.shuffled(Random(42L)).take(10)

        val pilotPositions = early + mid + late
        require(pilotPositions.size == 50) { "Pilot sample must be exactly 50 positions, got ${pilotPositions.size}" }

        val worker1 = Depth8EngineWorker(binaryPath = binaryPath, configuredMultiPv = 1)
        val worker3 = Depth8EngineWorker(binaryPath = binaryPath, configuredMultiPv = 3)
        val worker5 = Depth8EngineWorker(binaryPath = binaryPath, configuredMultiPv = 5)

        val workers = mapOf(
            "MPV1" to worker1,
            "MPV3" to worker3,
            "MPV5" to worker5,
        )

        try {
            // Warm-up: 5 untimed dummy positions
            val warmupFens = corpus.take(5).map { it.canonicalFen }
            for (fen in warmupFens) {
                worker1.evaluate(fen)
                worker3.evaluate(fen)
                worker5.evaluate(fen)
            }

            // Data collection structures per config
            val configRuns = mapOf(
                "MPV1" to mutableListOf<SingleRunMetrics>(),
                "MPV3" to mutableListOf<SingleRunMetrics>(),
                "MPV5" to mutableListOf<SingleRunMetrics>(),
            )

            // Cyclic interleaved execution order: [A, B, C], [B, C, A], [C, A, B]
            val configKeys = listOf("MPV1", "MPV3", "MPV5")

            for ((i, entry) in pilotPositions.withIndex()) {
                val shift = i % 3
                val runOrder = listOf(
                    configKeys[shift],
                    configKeys[(shift + 1) % 3],
                    configKeys[(shift + 2) % 3],
                )

                val stratum = when {
                    entry.samplePly <= 12 -> "early-game"
                    entry.samplePly in 13..40 -> "middle-game"
                    else -> "late-game"
                }

                for (cfg in runOrder) {
                    val w = workers[cfg] ?: error("Missing worker for $cfg")
                    val start = System.currentTimeMillis()
                    val record = w.evaluate(entry.canonicalFen)
                    val totalMs = System.currentTimeMillis() - start

                    val rank1 = record.principalVariations.firstOrNull { it.rank == 1 }
                    val termReason = rank1?.terminationReason ?: "QUIET_STOP"
                    val nomNodes = record.principalVariations.mapNotNull { it.nominalNodes }.sum()
                    val extNodes = record.principalVariations.mapNotNull { it.extensionNodes }.sum()
                    val totalNodes = nomNodes + extNodes

                    // Compute nominal latency vs extension latency estimate
                    val extEstimateMs = if (extNodes > 0 && totalNodes > 0) {
                        (totalMs * (extNodes.toDouble() / totalNodes.toDouble())).toLong()
                    } else 0L
                    val nomEstimateMs = (totalMs - extEstimateMs).coerceAtLeast(1L)

                    configRuns[cfg]?.add(
                        SingleRunMetrics(
                            stratum = stratum,
                            totalLatencyMs = totalMs,
                            nominalLatencyMs = nomEstimateMs,
                            extensionLatencyMs = extEstimateMs,
                            nominalNodes = nomNodes,
                            extensionNodes = extNodes,
                            totalNodes = totalNodes,
                            terminationReason = termReason,
                        )
                    )
                }
            }

            // Aggregate metrics
            val configMetricsMap = mutableMapOf<String, PilotConfigMetrics>()
            for (cfg in configKeys) {
                val runs = configRuns[cfg] ?: emptyList()
                val multiPvVal = when (cfg) {
                    "MPV1" -> 1
                    "MPV3" -> 3
                    "MPV5" -> 5
                    else -> 1
                }

                val totalLats = runs.map { it.totalLatencyMs.toDouble() }
                val nomLats = runs.map { it.nominalLatencyMs.toDouble() }
                val extLats = runs.map { it.extensionLatencyMs.toDouble() }
                val nomNodes = runs.map { it.nominalNodes.toDouble() }
                val extNodes = runs.map { it.extensionNodes.toDouble() }
                val totNodes = runs.map { it.totalNodes.toDouble() }

                val termCounts = runs.groupingBy { it.terminationReason }.eachCount()

                val strataMap = mutableMapOf<String, PilotMetricStats>()
                for (strat in listOf("early-game", "middle-game", "late-game")) {
                    val stratLats = runs.filter { it.stratum == strat }.map { it.totalLatencyMs.toDouble() }
                    strataMap[strat] = computeStats(stratLats)
                }

                configMetricsMap[cfg] = PilotConfigMetrics(
                    nominalDepth = 8,
                    multiPv = multiPvVal,
                    totalLatencyMs = computeStats(totalLats, includeMinMax = true),
                    nominalLatencyMs = computeStats(nomLats),
                    extensionLatencyMs = computeStats(extLats),
                    nominalNodes = computeStats(nomNodes),
                    extensionNodes = computeStats(extNodes),
                    totalNodes = computeStats(totNodes),
                    terminationReasonCounts = termCounts,
                    strataLatencyMs = strataMap,
                )
            }

            // Feasibility calculations
            val mean1 = configMetricsMap["MPV1"]?.totalLatencyMs?.mean ?: 0.0
            val mean3 = configMetricsMap["MPV3"]?.totalLatencyMs?.mean ?: 0.0
            val mean5 = configMetricsMap["MPV5"]?.totalLatencyMs?.mean ?: 0.0
            val combinedMean = mean1 + mean3 + mean5
            val projectedMinutes = combinedMean / 36.0
            val gateBreached = projectedMinutes > 60.0 || mean5 > 1800.0

            val results = Depth8TimingPilotResults(
                metadata = PilotMetadata(
                    generatedAt = Instant.now().toString(),
                    sampleCount = 50,
                    workerCount = 6,
                    stockfishVersion = "Stockfish 19",
                    stockfishBinarySha256 = Depth8CacheKeyGenerator.DEFAULT_STOCKFISH_SHA256,
                    status = if (gateBreached) "GATE_BREACHED" else "PASSED",
                ),
                feasibilitySummary = PilotFeasibilitySummary(
                    combinedCorpusMeanLatencyMs = Math.round(combinedMean * 100.0) / 100.0,
                    combinedCorpusProjectedWallClockMinutes = Math.round(projectedMinutes * 100.0) / 100.0,
                    combinedCorpusCeilingMinutes = 60.0,
                    gateBreached = gateBreached,
                ),
                configurations = configMetricsMap,
            )

            outputFile.parentFile?.mkdirs()
            mapper.writeValue(outputFile, results)
            println("Wrote timing pilot results to ${outputFile.absolutePath}")
            return results
        } finally {
            worker1.close()
            worker3.close()
            worker5.close()
        }
    }

    private fun computeStats(values: List<Double>, includeMinMax: Boolean = false): PilotMetricStats {
        if (values.isEmpty()) {
            return PilotMetricStats(0.0, 0.0, 0.0)
        }
        val sorted = values.sorted()
        val mean = Math.round(sorted.average() * 10.0) / 10.0
        val median = Math.round(percentile(sorted, 50.0) * 10.0) / 10.0
        val p90 = Math.round(percentile(sorted, 90.0) * 10.0) / 10.0
        val min = if (includeMinMax) sorted.first() else null
        val max = if (includeMinMax) sorted.last() else null
        return PilotMetricStats(mean, median, p90, min, max)
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = (p / 100.0 * (sorted.size - 1)).toInt()
        return sorted[idx.coerceIn(0, sorted.size - 1)]
    }

    private data class SingleRunMetrics(
        val stratum: String,
        val totalLatencyMs: Long,
        val nominalLatencyMs: Long,
        val extensionLatencyMs: Long,
        val nominalNodes: Long,
        val extensionNodes: Long,
        val totalNodes: Long,
        val terminationReason: String,
    )
}
