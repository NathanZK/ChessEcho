package com.chessprobe.service.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SimilarityConcurrencyManagerTest {
    @Test
    fun `rejects maxConcurrentSessions less than 1 with IllegalArgumentException`() {
        val exZero =
            assertThrows(IllegalArgumentException::class.java) {
                SimilarityConcurrencyManager(maxConcurrentSessions = 0)
            }
        assertTrue(exZero.message!!.contains("must be >= 1"))

        val exNeg =
            assertThrows(IllegalArgumentException::class.java) {
                SimilarityConcurrencyManager(maxConcurrentSessions = -5)
            }
        assertTrue(exNeg.message!!.contains("must be >= 1"))
    }

    @Test
    fun `executes onPermitAcquired when capacity is available and returns result`() {
        val manager = SimilarityConcurrencyManager(maxConcurrentSessions = 2)

        val result =
            manager.withPermitOrFallback(
                onPermitAcquired = { "acquired" },
                onCapacityExceeded = { "fallback" },
            )

        assertEquals("acquired", result)
    }

    @Test
    fun `executes onCapacityExceeded when all permits are currently in use`() {
        val manager = SimilarityConcurrencyManager(maxConcurrentSessions = 1)
        val acquiredLatch = CountDownLatch(1)
        val holdLatch = CountDownLatch(1)
        val fallbackInvoked = AtomicBoolean(false)

        val thread =
            Thread {
                manager.withPermitOrFallback(
                    onPermitAcquired = {
                        acquiredLatch.countDown()
                        holdLatch.await(5, TimeUnit.SECONDS)
                    },
                    onCapacityExceeded = {},
                )
            }
        thread.start()

        assertTrue(acquiredLatch.await(5, TimeUnit.SECONDS), "First thread should acquire permit")

        // Second caller should exceed capacity immediately
        val secondResult =
            manager.withPermitOrFallback(
                onPermitAcquired = { "acquired" },
                onCapacityExceeded = {
                    fallbackInvoked.set(true)
                    "fallback"
                },
            )

        assertEquals("fallback", secondResult)
        assertTrue(fallbackInvoked.get(), "Fallback should be executed when capacity exceeded")

        holdLatch.countDown()
        thread.join(5000)
    }

    @Test
    fun `releases permit in finally block even when onPermitAcquired throws exception`() {
        val manager = SimilarityConcurrencyManager(maxConcurrentSessions = 1)

        assertThrows(IllegalStateException::class.java) {
            manager.withPermitOrFallback(
                onPermitAcquired = { throw IllegalStateException("Simulated failure inside permit scope") },
                onCapacityExceeded = { "fallback" },
            )
        }

        // Next call should successfully acquire permit because it was released in finally
        var acquiredOnRetry = false
        val result =
            manager.withPermitOrFallback(
                onPermitAcquired = {
                    acquiredOnRetry = true
                    "success"
                },
                onCapacityExceeded = { "fallback" },
            )

        assertEquals("success", result)
        assertTrue(acquiredOnRetry, "Permit must be re-acquirable after exception in prior holder")
    }
}
