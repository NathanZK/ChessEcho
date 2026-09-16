package com.chessecho.service

import com.chessecho.domain.AsyncJob
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AsyncJobConfigurationInvariantTest {
    @Test
    fun `unresolved jobs fail closed without an account or import configuration`() {
        val job =
            AsyncJob(
                username = "legacy-player",
                platform = "CHESS_COM",
                configurationState = AsyncJob.CONFIGURATION_UNRESOLVED,
            )

        assertDoesNotThrow { job.validateConfiguration() }
        assertDoesNotThrow { check(!job.isReady()) }
    }

    @Test
    fun `ready jobs reject noncanonical time control snapshots`() {
        val job =
            AsyncJob(
                username = "player",
                platform = "CHESS_COM",
                timeControlsCsv = "RAPID,BLITZ",
                playerColor = "WHITE",
                configurationState = AsyncJob.CONFIGURATION_READY,
            )

        assertThrows(IllegalArgumentException::class.java) { job.validateConfiguration() }
    }

    @Test
    fun `configuration changes are rejected after persisted snapshot capture`() {
        val job =
            AsyncJob(
                username = "player",
                platform = "CHESS_COM",
                configurationState = AsyncJob.CONFIGURATION_UNRESOLVED,
            )
        job.capturePersistedConfiguration()
        job.fromDate = "2026-01"

        assertThrows(java.lang.IllegalStateException::class.java) { job.validateBeforeUpdate() }
    }
}
