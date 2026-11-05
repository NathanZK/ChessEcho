package com.chessecho.research

import com.chessecho.similarity.DynamicTacticalScorer
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class CachedPvLine(
    val rank: Int,
    val scoreCp: Int,
    val moves: List<String>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CachedEngineRecord(
    val canonicalFen: String,
    val normalizedFen: String,
    val depth: Int,
    val multiPv: Int,
    val bestMove: String,
    val scoreCp: Int,
    val principalVariations: List<CachedPvLine>,
    val dynamicTokens: Map<String, Double>,
    val elapsedMs: Long = 0L,
    val timedOut: Boolean = false,
)

class DynamicEngineResearchRunner(
    private val binaryPath: String = "/opt/homebrew/bin/stockfish",
    private val binarySha256: String = "dc2f18c34ae962dff591b66147d220ec06e61d756b93d8a4f5e04fd8e55c251f",
    val depth: Int = 16,
    val multiPv: Int = 1,
    val hardCeiling: Int = 11_000,
    private val cacheFile: File = File("src/test/resources/research/engine_evaluation_cache_d16_mpv1.json"),
) {
    private val mapper =
        jacksonObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val dynamicScorer = DynamicTacticalScorer()
    val cache = ConcurrentHashMap<String, CachedEngineRecord>()

    val actualEngineSearchCalls = AtomicInteger(0)
    val cacheHits = AtomicInteger(0)

    init {
        loadCache()
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

    fun isSupportedByEngine(canonicalFen: String): Boolean {
        val piecePlacement = canonicalFen.trim().split(Regex("\\s+"))[0]
        val ranks = piecePlacement.split("/")
        if (ranks.size != 8) return false
        val rank8 = ranks[0]
        val rank1 = ranks[7]
        if (rank8.contains('p', ignoreCase = true) || rank1.contains('p', ignoreCase = true)) {
            return false
        }
        return true
    }

    fun computeCacheKey(normalizedFen: String): String {
        val raw =
            "engine=Stockfish 19:binHash=$binarySha256:depth=$depth:multipv=$multiPv:" +
                "threads=1:hash=64:ponder=false:syzygy=none:fen=$normalizedFen"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun loadCache() {
        if (cacheFile.exists()) {
            try {
                val records: Map<String, CachedEngineRecord> = mapper.readValue(cacheFile)
                cache.putAll(records)
                println("Loaded ${cache.size} records from cache: ${cacheFile.name}")
            } catch (e: Exception) {
                println("Warning: failed to load existing cache from ${cacheFile.absolutePath}: ${e.message}")
            }
        }
    }

    @Synchronized
    fun persistCache() {
        cacheFile.parentFile?.mkdirs()
        mapper.writeValue(cacheFile, cache)
    }

    fun evaluateSingle(
        worker: SingleEngineWorker,
        canonicalFen: String,
    ): CachedEngineRecord {
        val normFen = normalizeFen(canonicalFen)
        val key = computeCacheKey(normFen)

        // 1. Check cache BEFORE engine invocation
        val existing = cache[key]
        if (existing != null) {
            cacheHits.incrementAndGet()
            return existing
        }

        // 2. Filter unsupported positions (e.g. pawns on rank 1 or 8 from custom arena setups)
        if (!isSupportedByEngine(canonicalFen)) {
            val fallback =
                CachedEngineRecord(
                    canonicalFen = canonicalFen,
                    normalizedFen = normFen,
                    depth = depth,
                    multiPv = multiPv,
                    bestMove = "(none)",
                    scoreCp = 0,
                    principalVariations = emptyList(),
                    dynamicTokens = emptyMap(),
                    elapsedMs = 0L,
                    timedOut = false,
                )
            cache[key] = fallback
            return fallback
        }

        // 3. Strict counter and ceiling invariant
        val callCount = actualEngineSearchCalls.incrementAndGet()
        if (callCount > hardCeiling) {
            throw IllegalStateException(
                "Strict Stockfish invocation ceiling of $hardCeiling exceeded at invocation #$callCount",
            )
        }

        // 4. Execute actual engine search
        val record = worker.search(canonicalFen, normFen, depth, multiPv)
        cache[key] = record
        return record
    }

    /**
     * Batch evaluation with thread pool of independent engine workers.
     */
    fun evaluateBatch(
        positions: List<String>,
        workerCount: Int = 6,
        progressInterval: Int = 500,
    ): Map<String, CachedEngineRecord> {
        val remaining =
            positions.filter { fen ->
                val normFen = normalizeFen(fen)
                val key = computeCacheKey(normFen)
                !cache.containsKey(key)
            }

        println(
            "Batch evaluate: ${positions.size} total positions. Cached: ${positions.size - remaining.size}, Pending search: ${remaining.size}",
        )
        if (remaining.isEmpty()) {
            return positions.associateWith { fen ->
                val normFen = normalizeFen(fen)
                cache.getValue(computeCacheKey(normFen))
            }
        }

        val pool = Executors.newFixedThreadPool(workerCount)
        val workers = (1..workerCount).map { SingleEngineWorker(binaryPath, multiPv) }
        val workerQueue = java.util.concurrent.LinkedBlockingQueue(workers)
        val completedCount = AtomicInteger(0)
        val totalPending = remaining.size

        try {
            val futures =
                remaining.map { fen ->
                    pool.submit {
                        val worker = workerQueue.take()
                        try {
                            evaluateSingle(worker, fen)
                            val done = completedCount.incrementAndGet()
                            if (done % progressInterval == 0 || done == totalPending) {
                                println(
                                    "  [Search Progress] $done / $totalPending engine searches completed (Calls: ${actualEngineSearchCalls.get()} / $hardCeiling, Total cached: ${cache.size})...",
                                )
                                persistCache()
                            }
                        } finally {
                            workerQueue.put(worker)
                        }
                    }
                }

            for (f in futures) {
                f.get()
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)
            workers.forEach { it.close() }
            persistCache()
        }

        return positions.associateWith { fen ->
            val normFen = normalizeFen(fen)
            cache.getValue(computeCacheKey(normFen))
        }
    }

    inner class SingleEngineWorker(
        private val binaryPath: String,
        private val multiPv: Int,
    ) : AutoCloseable {
        private var process: Process? = null
        private var writer: BufferedWriter? = null
        private var reader: BufferedReader? = null
        private var searchesDone = 0

        init {
            startProcess()
        }

        @Synchronized
        private fun startProcess() {
            close()
            val pb = ProcessBuilder(binaryPath)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
            reader = BufferedReader(InputStreamReader(proc.inputStream))

            sendCommand("uci")
            waitForResponse { it == "uciok" }
            sendCommand("setoption name Threads value 1")
            sendCommand("setoption name Hash value 64")
            sendCommand("setoption name MultiPV value $multiPv")
            sendCommand("setoption name Ponder value false")
            sendCommand("setoption name SyzygyPath value <empty>")
            sendCommand("isready")
            waitForResponse { it == "readyok" }
            searchesDone = 0
        }

        @Synchronized
        fun search(
            canonicalFen: String,
            normalizedFen: String,
            depth: Int,
            multiPv: Int,
        ): CachedEngineRecord {
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    if (searchesDone >= 500 || process?.isAlive != true) {
                        startProcess()
                    }
                    val record = doSearch(canonicalFen, normalizedFen, depth, multiPv)
                    searchesDone++
                    return record
                } catch (e: Exception) {
                    lastError = e
                    println("Worker search attempt $attempt failed on $canonicalFen (${e.message}), restarting engine process...")
                    startProcess()
                }
            }
            throw IllegalStateException("Failed to evaluate position after 3 attempts: $normalizedFen", lastError)
        }

        private fun doSearch(
            canonicalFen: String,
            normalizedFen: String,
            depth: Int,
            multiPv: Int,
        ): CachedEngineRecord {
            val rd = reader ?: throw IOException("Reader not initialized")
            sendCommand("ucinewgame")
            sendCommand("isready")
            waitForResponse { it == "readyok" }

            sendCommand("position fen $normalizedFen")
            sendCommand("go depth $depth")

            val startTime = System.currentTimeMillis()
            var bestMove = ""
            val pvMap = mutableMapOf<Int, CachedPvLine>()

            while (true) {
                val line = rd.readLine() ?: throw IOException("Stockfish process output terminated prematurely")
                if (line.startsWith("info") && line.contains("score")) {
                    val pvRank = parseTokenInt(line, "multipv") ?: 1
                    val score = parseScore(line)
                    val pvIdx = line.indexOf(" pv ")
                    val moves =
                        if (pvIdx != -1) {
                            line.substring(pvIdx + 4).trim().split("\\s+".toRegex())
                        } else {
                            emptyList()
                        }
                    pvMap[pvRank] = CachedPvLine(rank = pvRank, scoreCp = score, moves = moves)
                } else if (line.startsWith("bestmove")) {
                    val parts = line.split(" ")
                    if (parts.size >= 2) {
                        bestMove = parts[1]
                    }
                    break
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            val sortedPvs = (1..multiPv).mapNotNull { pvMap[it] }
            val rank1 = sortedPvs.firstOrNull { it.rank == 1 }
            val scoreCp = rank1?.scoreCp ?: 0
            val rank1Moves = rank1?.moves.orEmpty()

            val tokens =
                if (rank1Moves.isNotEmpty()) {
                    dynamicScorer.extractDynamicTokens(normalizedFen, rank1Moves)
                } else {
                    emptyMap()
                }

            return CachedEngineRecord(
                canonicalFen = canonicalFen,
                normalizedFen = normalizedFen,
                depth = depth,
                multiPv = multiPv,
                bestMove = bestMove,
                scoreCp = scoreCp,
                principalVariations = sortedPvs,
                dynamicTokens = tokens,
                elapsedMs = elapsed,
                timedOut = false,
            )
        }

        private fun parseScore(line: String): Int {
            val cpIdx = line.indexOf("score cp ")
            if (cpIdx != -1) {
                val after = line.substring(cpIdx + 9).trim()
                val token = after.split(" ")[0]
                val cp = token.toIntOrNull() ?: 0
                return cp.coerceIn(-1000, 1000)
            }
            val mateIdx = line.indexOf("score mate ")
            if (mateIdx != -1) {
                val after = line.substring(mateIdx + 11).trim()
                val token = after.split(" ")[0]
                val mateMoves = token.toIntOrNull() ?: 1
                return if (mateMoves > 0) 1000 else -1000
            }
            return 0
        }

        private fun parseTokenInt(
            line: String,
            token: String,
        ): Int? {
            val pattern = " $token "
            val idx = line.indexOf(pattern)
            if (idx == -1) return null
            val after = line.substring(idx + pattern.length).trim()
            val word = after.split(" ")[0]
            return word.toIntOrNull()
        }

        private fun sendCommand(cmd: String) {
            val wr = writer ?: throw IOException("Writer not initialized")
            wr.write(cmd)
            wr.newLine()
            wr.flush()
        }

        private fun waitForResponse(
            timeoutMs: Long = 10_000L,
            predicate: (String) -> Boolean,
        ) {
            val rd = reader ?: throw IOException("Reader not initialized")
            while (true) {
                val line = rd.readLine() ?: throw IOException("Process stream closed while waiting for response")
                if (predicate(line)) return
            }
        }

        @Synchronized
        override fun close() {
            try {
                writer?.let {
                    it.write("quit\n")
                    it.flush()
                }
                process?.waitFor(500, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
            } finally {
                process?.destroyForcibly()
                process = null
                writer = null
                reader = null
            }
        }
    }
}
