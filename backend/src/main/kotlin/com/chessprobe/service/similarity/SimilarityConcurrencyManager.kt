package com.chessprobe.service.similarity

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore

@Component
class SimilarityConcurrencyManager(
    @Value("\${chess.similarity.dynamic.max-concurrent-sessions:2}")
    private val maxConcurrentSessions: Int = 2,
) {
    init {
        require(maxConcurrentSessions >= 1) {
            "chess.similarity.dynamic.max-concurrent-sessions must be >= 1, but was: $maxConcurrentSessions"
        }
    }

    private val semaphore = Semaphore(maxConcurrentSessions)

    fun <T> withPermitOrFallback(
        onPermitAcquired: () -> T,
        onCapacityExceeded: () -> T,
    ): T {
        if (!semaphore.tryAcquire()) {
            return onCapacityExceeded()
        }
        return try {
            onPermitAcquired()
        } finally {
            semaphore.release()
        }
    }
}
