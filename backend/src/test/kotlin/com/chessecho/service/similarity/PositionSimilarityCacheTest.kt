package com.chessecho.service.similarity

import com.chessecho.similarity.PositionInvertedIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID

class PositionSimilarityCacheTest {
    private val cache = PositionSimilarityCache()
    private val accountId = UUID.randomUUID()

    @Test
    fun `getOrBuild returns cached index on repeated calls`() {
        var buildCount = 0
        val builder = {
            buildCount++
            PositionInvertedIndex()
        }

        val first = cache.getOrBuild(accountId, builder)
        val second = cache.getOrBuild(accountId, builder)

        assertSame(first, second)
        assertEquals(1, buildCount)
    }

    @Test
    fun `invalidate advances generation and triggers rebuild on next access`() {
        var buildCount = 0
        val builder = {
            buildCount++
            PositionInvertedIndex()
        }

        val first = cache.getOrBuild(accountId, builder)
        cache.invalidate(accountId)
        val second = cache.getOrBuild(accountId, builder)

        assertNotSame(first, second)
        assertEquals(2, buildCount)
    }

    @Test
    fun `getOrBuild discards rebuild result if invalidated concurrently during construction`() {
        var callCount = 0
        val slowBuilder = {
            callCount++
            // Simulate invalidation occurring while build was in-flight
            cache.invalidate(accountId)
            PositionInvertedIndex()
        }

        // Because it was invalidated in-flight, it should not commit as valid cached generation
        cache.getOrBuild(accountId, slowBuilder)

        // Next read should trigger rebuild again
        val nextBuilder = {
            callCount++
            PositionInvertedIndex()
        }
        cache.getOrBuild(accountId, nextBuilder)

        assertEquals(2, callCount)
    }

    @Test
    fun `getOrBuild serves stale index on transient error when prior complete index exists`() {
        val initialIndex = PositionInvertedIndex()
        cache.getOrBuild(accountId) { initialIndex }

        // Transient failure occurs during a refresh attempt where generation has not been invalidated
        val result = cache.getOrBuild(accountId) { throw IOException("database timeout") }

        assertSame(initialIndex, result, "Should serve retained prior complete index on transient error")
    }

    @Test
    fun `getOrBuild fails closed on error when no prior index exists`() {
        assertThrows(IOException::class.java) {
            cache.getOrBuild(accountId) { throw IOException("cold db connection failure") }
        }
    }
}
