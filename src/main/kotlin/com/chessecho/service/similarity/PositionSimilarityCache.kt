package com.chessecho.service.similarity

import com.chessecho.similarity.PositionInvertedIndex
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Component
class PositionSimilarityCache {
    private val generations = ConcurrentHashMap<UUID, AtomicLong>()
    private val cache = ConcurrentHashMap<UUID, CachedEntry>()
    private val locks = ConcurrentHashMap<UUID, Any>()

    fun invalidate(accountId: UUID) {
        val gen = generations.computeIfAbsent(accountId) { AtomicLong(0L) }
        gen.incrementAndGet()
        cache.remove(accountId)
    }

    fun getOrBuild(
        accountId: UUID,
        builder: () -> PositionInvertedIndex,
    ): PositionInvertedIndex {
        val gen = generations.computeIfAbsent(accountId) { AtomicLong(0L) }
        val currentGen = gen.get()
        val cached = cache[accountId]
        if (cached != null && cached.generation == currentGen) {
            return cached.index
        }

        val lock = locks.computeIfAbsent(accountId) { Any() }
        synchronized(lock) {
            val lockedGen = gen.get()
            val recheck = cache[accountId]
            if (recheck != null && recheck.generation == lockedGen) {
                return recheck.index
            }

            val startGen = lockedGen
            val newIndex =
                try {
                    builder()
                } catch (e: Exception) {
                    if (recheck != null && recheck.generation == lockedGen) {
                        return recheck.index
                    }
                    throw e
                }

            if (gen.get() == startGen) {
                cache[accountId] = CachedEntry(startGen, newIndex)
            }
            return newIndex
        }
    }

    private data class CachedEntry(
        val generation: Long,
        val index: PositionInvertedIndex,
    )
}
