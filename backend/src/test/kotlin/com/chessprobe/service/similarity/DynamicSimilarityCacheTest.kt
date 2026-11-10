package com.chessprobe.service.similarity

import com.chessprobe.similarity.DynamicInvertedIndex
import com.chessprobe.similarity.PositionDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

class DynamicSimilarityCacheTest {
    private fun createDoc(fen: String = "8/8/8/8/8/8/8/4K2k w - - 0 1"): PositionDocument =
        PositionDocument(
            positionId = UUID.randomUUID(),
            fen = fen,
            canonicalFen = fen.substringBefore(" -"),
            terms = mapOf("tactical_token" to 1.0),
        )

    @Test
    fun `constructor rejects non-positive maxCachedAccounts or non-positive idleTtl`() {
        assertThrows(IllegalArgumentException::class.java) {
            DynamicSimilarityCache(maxCachedAccounts = 0)
        }
        val boundaryCache = DynamicSimilarityCache(maxCachedAccounts = 1)
        assertEquals(1, boundaryCache.maxCachedAccounts)

        assertThrows(IllegalArgumentException::class.java) {
            DynamicSimilarityCache(idleTtl = Duration.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DynamicSimilarityCache(idleTtl = Duration.ofSeconds(-5))
        }
    }

    @Test
    fun `getOrBuild caches index and reuses it on subsequent calls`() {
        val cache = DynamicSimilarityCache()
        val accountId = UUID.randomUUID()
        var buildCount = 0

        val index1 =
            cache.getOrBuild(accountId) {
                buildCount++
                Thread.sleep(10)
                DynamicInvertedIndex(listOf(createDoc()))
            }

        val index2 =
            cache.getOrBuild(accountId) {
                buildCount++
                DynamicInvertedIndex(listOf(createDoc()))
            }

        assertSame(index1, index2)
        assertEquals(1, buildCount)
        assertEquals(1, cache.activeCachedAccountCount())
        assertTrue(cache.totalHeapFootprintBytes() > 0)
        val buildMs = cache.lastBuildDurationMs(accountId)
        assertNotNull(buildMs)
        assertTrue(buildMs!! >= 5L)
        assertTrue(buildMs <= 5000L)
    }

    @Test
    fun `invalidate advances generation counter and forces rebuild on next getOrBuild`() {
        val cache = DynamicSimilarityCache()
        val accountId = UUID.randomUUID()
        var buildCount = 0

        val index1 =
            cache.getOrBuild(accountId) {
                buildCount++
                DynamicInvertedIndex(listOf(createDoc()))
            }

        val gen1 = cache.invalidate(accountId)
        assertEquals(1L, gen1)
        assertEquals(0, cache.activeCachedAccountCount())

        val index2 =
            cache.getOrBuild(accountId) {
                buildCount++
                DynamicInvertedIndex(listOf(createDoc()))
            }

        assertEquals(2, buildCount)
        assertEquals(1, cache.activeCachedAccountCount())
        val fetchedAfterRebuild = cache.get(accountId)
        assertSame(index2, fetchedAfterRebuild)

        val gen2 = cache.invalidate(accountId)
        assertEquals(2L, gen2)
    }

    @Test
    fun `LRU eviction evicts least recently accessed account when maxCachedAccounts is reached`() {
        val cache = DynamicSimilarityCache(maxCachedAccounts = 2)
        val acc1 = UUID.randomUUID()
        val acc2 = UUID.randomUUID()
        val acc3 = UUID.randomUUID()

        cache.getOrBuild(acc1) { DynamicInvertedIndex(listOf(createDoc())) }
        Thread.sleep(10)
        cache.getOrBuild(acc2) { DynamicInvertedIndex(listOf(createDoc())) }
        assertEquals(2, cache.activeCachedAccountCount())

        // Access acc1 so acc2 becomes LRU
        Thread.sleep(10)
        cache.getOrBuild(acc1) { DynamicInvertedIndex(listOf(createDoc())) }

        // Add acc3 -> acc2 should be evicted
        Thread.sleep(10)
        cache.getOrBuild(acc3) { DynamicInvertedIndex(listOf(createDoc())) }

        assertEquals(2, cache.activeCachedAccountCount())
        assertEquals(1L, cache.totalEvictionCount())
        assertNotNull(cache.get(acc1))
        assertNotNull(cache.get(acc3))
        assertNull(cache.get(acc2))
    }

    @Test
    fun `idle TTL eviction evicts expired account entries`() {
        val cache = DynamicSimilarityCache(idleTtl = Duration.ofMillis(50))
        val acc = UUID.randomUUID()

        val index1 = cache.getOrBuild(acc) { DynamicInvertedIndex(listOf(createDoc())) }
        assertSame(index1, cache.get(acc))

        Thread.sleep(60)

        assertNull(cache.get(acc))
        assertEquals(0, cache.activeCachedAccountCount())
        assertEquals(1L, cache.totalEvictionCount())
    }

    @Test
    fun `builder failure preserves existing valid entry and rethrows exception`() {
        val cache = DynamicSimilarityCache()
        val accountId = UUID.randomUUID()

        val validIndex =
            cache.getOrBuild(accountId) {
                DynamicInvertedIndex(listOf(createDoc()))
            }

        val recoveredIndex =
            cache.getOrBuild(accountId) {
                throw IllegalStateException("Database query failed")
            }
        assertSame(validIndex, recoveredIndex)

        val newAccountId = UUID.randomUUID()
        assertThrows(IllegalStateException::class.java) {
            cache.getOrBuild(newAccountId) {
                throw IllegalStateException("Initial load failed")
            }
        }
    }

    @Test
    fun `cold start and warm query telemetry latencies are recorded and accessible`() {
        val cache = DynamicSimilarityCache()
        assertNull(cache.lastColdStartDurationMs())
        assertNull(cache.lastWarmQueryDurationMs())

        cache.recordColdStartDurationMs(120L)
        cache.recordWarmQueryDurationMs(3L)

        assertEquals(120L, cache.lastColdStartDurationMs())
        assertEquals(3L, cache.lastWarmQueryDurationMs())
    }
}
