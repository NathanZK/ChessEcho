package com.chessecho.research

import com.fasterxml.jackson.annotation.JsonInclude
import java.security.MessageDigest

/**
 * Depth-8 MultiPV Research models and serialization DTOs according to
 * SPEC-RESEARCH-DYNAMIC-SIMILARITY-DEPTH8-MULTIPV (§10).
 */

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Depth8CachedPvLine(
    val rank: Int,
    val scoreCp: Int,
    val moves: List<String>,
    val nominalDepth: Int = 8,
    val nominalMoves: List<String> = emptyList(),
    val extensionMoves: List<String> = emptyList(),
    val terminationReason: String = "QUIET_STOP",
    val nominalNodes: Long? = null,
    val extensionNodes: Long? = null,
    val totalNodes: Long? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Depth8CachedEngineRecord(
    val canonicalFen: String,
    val normalizedFen: String,
    val depth: Int = 8,
    val multiPv: Int,
    val bestMove: String,
    val scoreCp: Int,
    val principalVariations: List<Depth8CachedPvLine>,
    val dynamicTokens: Map<String, Double>,
    val tokensByRank: Map<String, Map<String, Double>> = emptyMap(),
    val elapsedMs: Long = 0L,
    val timedOut: Boolean = false,
)

object Depth8CacheKeyGenerator {
    const val DEFAULT_STOCKFISH_SHA256 = "dc2f18c34ae962dff591b66147d220ec06e61d756b93d8a4f5e04fd8e55c251f"
    const val TOKEN_VERSION = "bizjak_v1"
    const val EXT_TIMEOUT_MS = 10000

    /**
     * Constructs the exact canonical 15-parameter preimage string:
     * engine=Stockfish 19:binHash=<binSha256>:depth=8:multipv=<k>:threads=1:hash=64:ponder=false:
     * syzygy=none:analyseMode=true:hashPolicy=clearHash:ext=bounded_pv:extLimits=d8_n5000_p10:
     * extTimeoutMs=10000:tokenVersion=bizjak_v1:fen=<normalizedFen>
     */
    fun computePreimage(
        multiPv: Int,
        normalizedFen: String,
        binarySha256: String = DEFAULT_STOCKFISH_SHA256,
        depth: Int = 8,
        threads: Int = 1,
        hash: Int = 64,
        ponder: Boolean = false,
        syzygy: String = "none",
        analyseMode: Boolean = true,
        hashPolicy: String = "clearHash",
        ext: String = "bounded_pv",
        extLimits: String = "d8_n5000_p10",
        extTimeoutMs: Int = EXT_TIMEOUT_MS,
        tokenVersion: String = TOKEN_VERSION,
    ): String {
        return "engine=Stockfish 19:binHash=$binarySha256:depth=$depth:multipv=$multiPv:" +
            "threads=$threads:hash=$hash:ponder=$ponder:syzygy=$syzygy:analyseMode=$analyseMode:" +
            "hashPolicy=$hashPolicy:ext=$ext:extLimits=$extLimits:extTimeoutMs=$extTimeoutMs:" +
            "tokenVersion=$tokenVersion:fen=$normalizedFen"
    }

    fun computeCacheKey(
        multiPv: Int,
        normalizedFen: String,
        binarySha256: String = DEFAULT_STOCKFISH_SHA256,
    ): String {
        val preimage = computePreimage(multiPv, normalizedFen, binarySha256)
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(preimage.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun normalizeFen(fen: String): String {
        val fields = fen.trim().split(Regex("\\s+"))
        val canonicalPart =
            if (fields.size >= 4) {
                "${fields[0]} ${fields[1]} ${fields[2]} ${fields[3]}"
            } else {
                fen.trim()
            }
        return "$canonicalPart 0 1"
    }

    fun toCanonicalFen(fen: String): String {
        val fields = fen.trim().split(Regex("\\s+"))
        return if (fields.size >= 4) {
            "${fields[0]} ${fields[1]} ${fields[2]} ${fields[3]}"
        } else {
            fen.trim()
        }
    }

    fun isSupportedByEngine(canonicalFen: String): Boolean {
        val piecePlacement = canonicalFen.trim().split(Regex("\\s+"))[0]
        val ranks = piecePlacement.split("/")
        if (ranks.size != 8) return false
        val rank8 = ranks[0]
        val rank1 = ranks[7]
        return !rank8.contains('p', ignoreCase = true) && !rank1.contains('p', ignoreCase = true)
    }
}

// -------------------------------------------------------------
// Artifact 1: Timing Pilot Results Models (§10)
// -------------------------------------------------------------

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PilotMetricStats(
    val mean: Double,
    val median: Double,
    val p90: Double,
    val min: Double? = null,
    val max: Double? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PilotMetadata(
    val generatedAt: String,
    val sampleCount: Int,
    val workerCount: Int,
    val stockfishVersion: String = "Stockfish 19",
    val stockfishBinarySha256: String,
    val status: String, // PENDING | PASSED | GATE_BREACHED
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PilotFeasibilitySummary(
    val combinedCorpusMeanLatencyMs: Double,
    val combinedCorpusProjectedWallClockMinutes: Double,
    val combinedCorpusCeilingMinutes: Double = 60.0,
    val gateBreached: Boolean,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PilotConfigMetrics(
    val nominalDepth: Int = 8,
    val multiPv: Int,
    val totalLatencyMs: PilotMetricStats,
    val nominalLatencyMs: PilotMetricStats,
    val extensionLatencyMs: PilotMetricStats,
    val nominalNodes: PilotMetricStats,
    val extensionNodes: PilotMetricStats,
    val totalNodes: PilotMetricStats,
    val terminationReasonCounts: Map<String, Int>,
    val strataLatencyMs: Map<String, PilotMetricStats>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Depth8TimingPilotResults(
    val metadata: PilotMetadata,
    val feasibilitySummary: PilotFeasibilitySummary,
    val configurations: Map<String, PilotConfigMetrics>,
)

// -------------------------------------------------------------
// Artifact 3: Retrieval Benchmark Results Models (§10)
// -------------------------------------------------------------

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Depth8BudgetMetrics(
    val recallAt10: Double,
    val recallAt20: Double,
    val precisionAt10: Double,
    val precisionAtN: Double,
    val mrrAtNTop1: Double,
    val mrrAtNFirstRef: Double,
    val exhaustionRate: Double,
    val relevantRecoveredCount: Int,
    val uniqueCandidatesRetrieved: Int,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Depth8ConditionResults(
    val bm25: Map<String, Depth8BudgetMetrics>,
    val tfidf: Map<String, Depth8BudgetMetrics>,
    val incompleteLinesAblation: Map<String, Any>? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class Depth8RetrievalResults(
    val metadata: Map<String, Any>,
    val conditions: Map<String, Depth8ConditionResults>,
)
