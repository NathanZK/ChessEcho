package com.chessecho.research

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parallel offline batch generator for Corpus A under Depth 8 MultiPV search
 * according to SPEC-RESEARCH-DYNAMIC-SIMILARITY-DEPTH8-MULTIPV (§10, §11).
 */
class Depth8MultiPvCorpusGenerator(
    val binaryPath: String = "/opt/homebrew/bin/stockfish",
    val binarySha256: String = Depth8CacheKeyGenerator.DEFAULT_STOCKFISH_SHA256,
    val corpusFile: File = File("src/test/resources/research/corpus_a.json"),
    val workerCount: Int = 6,
) {
    private val mapper = jacksonObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun generateConfiguration(
        multiPv: Int,
        cacheFile: File,
    ): Map<String, Depth8CachedEngineRecord> {
        val corpus: List<CorpusEntry> = mapper.readValue(corpusFile)
        val cache = ConcurrentHashMap<String, Depth8CachedEngineRecord>()

        if (cacheFile.exists()) {
            try {
                val existing: Map<String, Depth8CachedEngineRecord> = mapper.readValue(cacheFile)
                cache.putAll(existing)
                println("Loaded ${cache.size} existing records from ${cacheFile.name}")
            } catch (e: Exception) {
                System.err.println("Warning: failed to load existing cache from ${cacheFile.name}: ${e.message}")
            }
        }

        // Determine missing entries
        val pendingEntries = corpus.filter { entry ->
            val normFen = Depth8CacheKeyGenerator.normalizeFen(entry.canonicalFen)
            val key = Depth8CacheKeyGenerator.computeCacheKey(multiPv, normFen, binarySha256)
            !cache.containsKey(key)
        }

        println("Configuration MPV $multiPv: Total ${corpus.size}, Cached ${cache.size}, Pending ${pendingEntries.size}")
        if (pendingEntries.isEmpty()) {
            return cache
        }

        val pool = Executors.newFixedThreadPool(workerCount)
        val workers = (1..workerCount).map {
            Depth8EngineWorker(
                binaryPath = binaryPath,
                binarySha256 = binarySha256,
                configuredMultiPv = multiPv,
                nominalDepth = 8,
            )
        }

        val completedCount = AtomicInteger(0)
        val totalPending = pendingEntries.size
        val startTime = System.currentTimeMillis()

        try {
            val chunked = pendingEntries.chunked(workerCount)
            for (chunk in chunked) {
                val futures = chunk.mapIndexed { idx, entry ->
                    val worker = workers[idx % workerCount]
                    pool.submit {
                        val normFen = Depth8CacheKeyGenerator.normalizeFen(entry.canonicalFen)
                        val key = Depth8CacheKeyGenerator.computeCacheKey(multiPv, normFen, binarySha256)
                        val record = worker.evaluate(entry.canonicalFen)
                        cache[key] = record

                        val done = completedCount.incrementAndGet()
                        if (done % 1000 == 0 || done == totalPending) {
                            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                            val rate = if (elapsedSec > 0) done / elapsedSec else 0.0
                            println("MPV $multiPv Progress: $done / $totalPending (${String.format("%.1f", rate)} pos/s, elapsed: ${String.format("%.1f", elapsedSec)}s)")
                            saveCache(cacheFile, cache)
                        }
                    }
                }
                for (f in futures) {
                    f.get()
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)
            workers.forEach { it.close() }
            saveCache(cacheFile, cache)
        }

        println("Completed MPV $multiPv generation: ${cache.size} records in ${cacheFile.name}")
        return cache
    }

    @Synchronized
    private fun saveCache(cacheFile: File, cache: Map<String, Depth8CachedEngineRecord>) {
        cacheFile.parentFile?.mkdirs()
        mapper.writeValue(cacheFile, cache)
    }
}
