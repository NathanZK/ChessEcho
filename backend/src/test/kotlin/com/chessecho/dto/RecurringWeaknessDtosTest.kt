package com.chessecho.dto

import com.chessecho.domain.DiscoveryJobStatus
import com.chessecho.domain.DiscoveryStage
import com.chessecho.domain.clustering.ClusterType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class RecurringWeaknessDtosTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `DiscoveryJobStatus legal transitions and terminal state detection`() {
        assertFalse(DiscoveryJobStatus.QUEUED.isTerminal())
        assertFalse(DiscoveryJobStatus.PROCESSING.isTerminal())
        assertTrue(DiscoveryJobStatus.COMPLETED.isTerminal())
        assertTrue(DiscoveryJobStatus.FAILED.isTerminal())
        assertTrue(DiscoveryJobStatus.CANCELLED.isTerminal())

        assertTrue(DiscoveryJobStatus.QUEUED.canTransitionTo(DiscoveryJobStatus.PROCESSING))
        assertTrue(DiscoveryJobStatus.QUEUED.canTransitionTo(DiscoveryJobStatus.CANCELLED))
        assertFalse(DiscoveryJobStatus.QUEUED.canTransitionTo(DiscoveryJobStatus.COMPLETED))

        assertTrue(DiscoveryJobStatus.PROCESSING.canTransitionTo(DiscoveryJobStatus.COMPLETED))
        assertTrue(DiscoveryJobStatus.PROCESSING.canTransitionTo(DiscoveryJobStatus.FAILED))
        assertTrue(DiscoveryJobStatus.PROCESSING.canTransitionTo(DiscoveryJobStatus.CANCELLED))
        assertFalse(DiscoveryJobStatus.PROCESSING.canTransitionTo(DiscoveryJobStatus.QUEUED))

        assertFalse(DiscoveryJobStatus.COMPLETED.canTransitionTo(DiscoveryJobStatus.CANCELLED))
        assertFalse(DiscoveryJobStatus.CANCELLED.canTransitionTo(DiscoveryJobStatus.PROCESSING))
    }

    @Test
    fun `RecurringDiscoveryRequest validates non-negative minEvalLoss and canonical time controls`() {
        val valid =
            RecurringDiscoveryRequest(
                timeControls = listOf("RAPID", "BLITZ"),
                minEvalLoss = 0.8,
            )
        valid.validate()

        val negativeLoss = RecurringDiscoveryRequest(minEvalLoss = -0.1)
        assertThrows<IllegalArgumentException> {
            negativeLoss.validate()
        }

        val invalidTimeControl = RecurringDiscoveryRequest(timeControls = listOf("INVALID_FORMAT"))
        assertThrows<IllegalArgumentException> {
            invalidTimeControl.validate()
        }
    }

    @Test
    fun `RecurringDiscoveryJobResponse serialization round-trip with warningMessage`() {
        val now = Instant.parse("2026-11-09T00:00:00Z")
        val id = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val dto =
            RecurringDiscoveryJobResponse(
                jobId = id,
                accountId = accountId,
                status = DiscoveryJobStatus.COMPLETED.name,
                progressPercent = 100,
                stage = DiscoveryStage.COMPLETE.name,
                errorMessage = null,
                warningMessage = "Partial discovery: structural clustering failed; dynamic clusters refreshed.",
                createdAt = now,
                updatedAt = now,
            )

        val json = objectMapper.writeValueAsString(dto)
        val deserialized = objectMapper.readValue<RecurringDiscoveryJobResponse>(json)

        assertEquals(dto, deserialized)
        assertEquals("Partial discovery: structural clustering failed; dynamic clusters refreshed.", deserialized.warningMessage)
    }

    @Test
    fun `RecurringWeaknessResponse serialization round-trip`() {
        val clusterId = UUID.randomUUID()
        val dto =
            RecurringWeaknessResponse(
                clusterId = clusterId,
                clusterType = ClusterType.STRUCTURAL,
                recommendationPriority = 20.0,
                representativeFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
                memberPositionCount = 12,
                totalOccurrences = 15,
                distinctGamesCount = 4,
                mistakeCount = 10,
                mistakeFrequency = 66.67,
                blunderRate = 20.0,
                evaluatedOccurrences = 12,
                unevaluatedOccurrences = 3,
                evalLossDistribution = mapOf("CRITICAL" to 3, "SIGNIFICANT" to 7),
                evalLossPercentages = mapOf("CRITICAL" to 20.0, "SIGNIFICANT" to 46.67),
                timingProfile = mapOf("RAPID_DECISION" to 5),
                formatBreakdown = mapOf("BLITZ" to 15),
            )

        val json = objectMapper.writeValueAsString(dto)
        val deserialized = objectMapper.readValue<RecurringWeaknessResponse>(json)

        assertEquals(dto, deserialized)
    }

    @Test
    fun `RecurringDiscoveryRequest validates chronological date ranges`() {
        val validRange = RecurringDiscoveryRequest(fromDate = "2024-01-01", toDate = "2024-06-30")
        validRange.validate()

        val validMonths = RecurringDiscoveryRequest(fromDate = "2024-01", toDate = "2024-06")
        validMonths.validate()

        val invertedRange = RecurringDiscoveryRequest(fromDate = "2024-06-30", toDate = "2024-01-01")
        assertThrows<IllegalArgumentException> {
            invertedRange.validate()
        }
    }
}
