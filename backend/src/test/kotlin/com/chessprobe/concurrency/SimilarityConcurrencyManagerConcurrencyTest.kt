package com.chessprobe.concurrency

import com.chessprobe.service.similarity.SimilarityConcurrencyManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class SimilarityConcurrencyManagerConcurrencyTest {
    @Test
    fun `permits are strictly released under simulated engine timeouts across virtual threads`() {
        val manager = SimilarityConcurrencyManager(maxConcurrentSessions = 2)
        val threadCount = 10
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val timeoutExceptionsCount = AtomicInteger(0)
        val capacityExceededCount = AtomicInteger(0)

        for (i in 0 until threadCount) {
            Thread.ofVirtual().start {
                startLatch.await()
                try {
                    manager.withPermitOrFallback(
                        onPermitAcquired = {
                            throw TimeoutException("Stockfish timeout simulated in thread $i")
                        },
                        onCapacityExceeded = {
                            capacityExceededCount.incrementAndGet()
                            "capacity_exceeded"
                        },
                    )
                } catch (_: TimeoutException) {
                    timeoutExceptionsCount.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "All virtual threads should finish within timeout")

        // Crucial invariant ADD-13: Permits must be released in finally despite timeouts.
        // A subsequent request must immediately acquire a permit without being blocked or leaked.
        var permitAcquired = false
        val finalResult =
            manager.withPermitOrFallback(
                onPermitAcquired = {
                    permitAcquired = true
                    "success"
                },
                onCapacityExceeded = { "fallback" },
            )

        assertEquals("success", finalResult)
        assertTrue(permitAcquired, "Must acquire permit after all timeout exceptions occurred")
        assertTrue(timeoutExceptionsCount.get() > 0, "At least one thread should have encountered simulated timeout")
    }

    @Test
    fun `fallback callback executes reliably when concurrent capacity is saturated`() {
        val manager = SimilarityConcurrencyManager(maxConcurrentSessions = 1)
        val permitAcquiredLatch = CountDownLatch(1)
        val releaseHolderLatch = CountDownLatch(1)
        val completedLatch = CountDownLatch(1)

        Thread.ofVirtual().start {
            manager.withPermitOrFallback(
                onPermitAcquired = {
                    permitAcquiredLatch.countDown()
                    releaseHolderLatch.await(5, TimeUnit.SECONDS)
                    "holder_done"
                },
                onCapacityExceeded = { "unexpected" },
            )
            completedLatch.countDown()
        }

        assertTrue(permitAcquiredLatch.await(5, TimeUnit.SECONDS), "Holder must acquire permit")

        // Second caller on virtual thread should immediately receive fallback without blocking
        var fallbackExecuted = false
        val secondResult =
            manager.withPermitOrFallback(
                onPermitAcquired = { "acquired" },
                onCapacityExceeded = {
                    fallbackExecuted = true
                    "fallback_result"
                },
            )

        assertEquals("fallback_result", secondResult)
        assertTrue(fallbackExecuted, "Fallback must be invoked when permit is saturated")

        releaseHolderLatch.countDown()
        assertTrue(completedLatch.await(5, TimeUnit.SECONDS), "Holder thread should complete")

        // After release, permit is available again
        var reacquired = false
        manager.withPermitOrFallback(
            onPermitAcquired = { reacquired = true },
            onCapacityExceeded = {},
        )
        assertTrue(reacquired, "Permit must be reacquired after holder completes")
    }
}
