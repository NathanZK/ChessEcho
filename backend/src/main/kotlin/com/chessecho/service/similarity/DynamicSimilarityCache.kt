package com.chessecho.service.similarity

import com.chessecho.similarity.DynamicInvertedIndex
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Component
class DynamicSimilarityCache(
    @Value("\${chess.similarity.dynamic.cache.max-accounts:100}")
    val maxCachedAccounts: Int = DEFAULT_MAX_ACCOUNTS,
    val idleTtl: Duration = Duration.ofMinutes(DEFAULT_IDLE_TTL_MINUTES),
) {
    init {
        require(maxCachedAccounts >= 1) { "maxCachedAccounts must be >= 1, but was: $maxCachedAccounts" }
        require(!idleTtl.isNegative && !idleTtl.isZero) { "idleTtl must be positive, but was: $idleTtl" }
    }

    private val generations = ConcurrentHashMap<UUID, AtomicLong>()
    private val cache = ConcurrentHashMap<UUID, CachedEntry>()
    private val locks = ConcurrentHashMap<UUID, Any>()
    private val lastBuildDurations = ConcurrentHashMap<UUID, Long>()

    private val evictionCount = AtomicLong(0L)
    private val lastColdStartMs = AtomicReference<Long?>(null)
    private val lastWarmQueryMs = AtomicReference<Long?>(null)

    fun invalidate(accountId: UUID): Long {
        val gen = generations.computeIfAbsent(accountId) { AtomicLong(0L) }
        val newGen = gen.incrementAndGet()
        cache.remove(accountId)
        return newGen
    }

    fun get(accountId: UUID): DynamicInvertedIndex? {
        val gen = generations[accountId]?.get() ?: 0L
        val entry = cache[accountId] ?: return null
        if (entry.generation != gen) {
            cache.remove(accountId)
            return null
        }
        val now = Instant.now()
        if (Duration.between(entry.lastAccessedAt, now) > idleTtl) {
            cache.remove(accountId)
            evictionCount.incrementAndGet()
            return null
        }
        entry.lastAccessedAt = now
        return entry.index
    }

    fun getOrBuild(
        accountId: UUID,
        builder: () -> DynamicInvertedIndex,
    ): DynamicInvertedIndex {
        val existing = get(accountId)
        if (existing != null) {
            return existing
        }

        val lock = locks.computeIfAbsent(accountId) { Any() }
        synchronized(lock) {
            val recheck = get(accountId)
            if (recheck != null) {
                return recheck
            }

            val gen = generations.computeIfAbsent(accountId) { AtomicLong(0L) }
            val startGen = gen.get()
            val prevEntry = cache[accountId]

            val startNs = System.nanoTime()
            val newIndex =
                try {
                    builder()
                } catch (e: Exception) {
                    if (prevEntry != null && prevEntry.generation == startGen) {
                        return prevEntry.index
                    }
                    throw e
                }
            val buildDurationMs = (System.nanoTime() - startNs) / 1_000_000

            if (gen.get() == startGen) {
                evictExpiredAndOverCapacity()
                val newEntry =
                    CachedEntry(
                        generation = startGen,
                        index = newIndex,
                        lastAccessedAt = Instant.now(),
                        buildDurationMs = buildDurationMs,
                    )
                cache[accountId] = newEntry
                lastBuildDurations[accountId] = buildDurationMs
            }
            return newIndex
        }
    }

    private fun evictExpiredAndOverCapacity() {
        val now = Instant.now()
        val iter = cache.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next().value
            if (Duration.between(entry.lastAccessedAt, now) > idleTtl) {
                iter.remove()
                evictionCount.incrementAndGet()
            }
        }
        while (cache.size >= maxCachedAccounts) {
            val oldest = cache.entries.minByOrNull { it.value.lastAccessedAt } ?: break
            cache.remove(oldest.key)
            evictionCount.incrementAndGet()
        }
    }

    fun activeCachedAccountCount(): Int {
        val now = Instant.now()
        val iter = cache.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next().value
            if (Duration.between(entry.lastAccessedAt, now) > idleTtl) {
                iter.remove()
                evictionCount.incrementAndGet()
            }
        }
        return cache.size
    }

    fun totalEvictionCount(): Long = evictionCount.get()

    fun totalHeapFootprintBytes(): Long = cache.values.sumOf { it.estimatedHeapBytes }

    fun lastBuildDurationMs(accountId: UUID): Long? = lastBuildDurations[accountId]

    fun recordColdStartDurationMs(durationMs: Long) {
        lastColdStartMs.set(durationMs)
    }

    fun recordWarmQueryDurationMs(durationMs: Long) {
        lastWarmQueryMs.set(durationMs)
    }

    fun lastColdStartDurationMs(): Long? = lastColdStartMs.get()

    fun lastWarmQueryDurationMs(): Long? = lastWarmQueryMs.get()

    private data class CachedEntry(
        val generation: Long,
        val index: DynamicInvertedIndex,
        val createdAt: Instant = Instant.now(),
        @Volatile var lastAccessedAt: Instant = Instant.now(),
        val buildDurationMs: Long = 0L,
        val estimatedHeapBytes: Long = index.estimatedHeapFootprintBytes(),
    )

    companion object {
        const val DEFAULT_MAX_ACCOUNTS = 100
        const val DEFAULT_IDLE_TTL_MINUTES = 30L
    }
}
