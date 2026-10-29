package com.chessecho.service

import com.chessecho.domain.DecisionSeverity
import com.chessecho.domain.TimeControl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DecisionTimingPolicyTest {
    private val policy = DecisionTimingPolicy()

    @ParameterizedTest
    @CsvSource(
        "0, NO_CONCERN",
        "4999, NO_CONCERN",
        "5000, MINOR",
        "9999, MINOR",
        "10000, SIGNIFICANT",
        "19999, SIGNIFICANT",
        "20000, CRITICAL",
        "45000, CRITICAL",
    )
    fun `classifies Bullet decision duration correctly`(
        durationMs: Long,
        expected: DecisionSeverity,
    ) {
        assertEquals(expected, policy.classify(TimeControl.BULLET, durationMs))
    }

    @ParameterizedTest
    @CsvSource(
        "0, NO_CONCERN",
        "14999, NO_CONCERN",
        "15000, MINOR",
        "29999, MINOR",
        "30000, SIGNIFICANT",
        "59999, SIGNIFICANT",
        "60000, CRITICAL",
        "120000, CRITICAL",
    )
    fun `classifies Blitz decision duration correctly`(
        durationMs: Long,
        expected: DecisionSeverity,
    ) {
        assertEquals(expected, policy.classify(TimeControl.BLITZ, durationMs))
    }

    @ParameterizedTest
    @CsvSource(
        "0, NO_CONCERN",
        "29999, NO_CONCERN",
        "30000, MINOR",
        "59999, MINOR",
        "60000, SIGNIFICANT",
        "119999, SIGNIFICANT",
        "120000, CRITICAL",
        "240000, CRITICAL",
    )
    fun `classifies Rapid decision duration correctly`(
        durationMs: Long,
        expected: DecisionSeverity,
    ) {
        assertEquals(expected, policy.classify(TimeControl.RAPID, durationMs))
    }

    @ParameterizedTest
    @CsvSource(
        "0, NO_CONCERN",
        "59999, NO_CONCERN",
        "60000, MINOR",
        "179999, MINOR",
        "180000, SIGNIFICANT",
        "299999, SIGNIFICANT",
        "300000, CRITICAL",
        "600000, CRITICAL",
    )
    fun `classifies Classical decision duration correctly`(
        durationMs: Long,
        expected: DecisionSeverity,
    ) {
        assertEquals(expected, policy.classify(TimeControl.CLASSICAL, durationMs))
    }

    @Test
    fun `classifies null or unsupported time control as UNKNOWN`() {
        assertEquals(DecisionSeverity.UNKNOWN, policy.classify(null, 30_000L))
        assertEquals(DecisionSeverity.UNKNOWN, policy.classify(null, 5_000L))
    }

    @Test
    fun `classifies negative duration as UNKNOWN`() {
        assertEquals(DecisionSeverity.UNKNOWN, policy.classify(TimeControl.BLITZ, -100L))
    }

    @Test
    fun `computes severity range bounds for Bullet`() {
        val noConcern = policy.severityRangeMs(TimeControl.BULLET, DecisionSeverity.NO_CONCERN)
        assertEquals(0L, noConcern.minMs)
        assertEquals(5_000L, noConcern.maxMs)

        val minor = policy.severityRangeMs(TimeControl.BULLET, DecisionSeverity.MINOR)
        assertEquals(5_000L, minor.minMs)
        assertEquals(10_000L, minor.maxMs)

        val significant = policy.severityRangeMs(TimeControl.BULLET, DecisionSeverity.SIGNIFICANT)
        assertEquals(10_000L, significant.minMs)
        assertEquals(20_000L, significant.maxMs)

        val critical = policy.severityRangeMs(TimeControl.BULLET, DecisionSeverity.CRITICAL)
        assertEquals(20_000L, critical.minMs)
        assertEquals(null, critical.maxMs)
    }

    @Test
    fun `computes severity range bounds for Blitz`() {
        val noConcern = policy.severityRangeMs(TimeControl.BLITZ, DecisionSeverity.NO_CONCERN)
        assertEquals(0L, noConcern.minMs)
        assertEquals(15_000L, noConcern.maxMs)

        val minor = policy.severityRangeMs(TimeControl.BLITZ, DecisionSeverity.MINOR)
        assertEquals(15_000L, minor.minMs)
        assertEquals(30_000L, minor.maxMs)

        val significant = policy.severityRangeMs(TimeControl.BLITZ, DecisionSeverity.SIGNIFICANT)
        assertEquals(30_000L, significant.minMs)
        assertEquals(60_000L, significant.maxMs)

        val critical = policy.severityRangeMs(TimeControl.BLITZ, DecisionSeverity.CRITICAL)
        assertEquals(60_000L, critical.minMs)
        assertEquals(null, critical.maxMs)
    }

    @Test
    fun `computes severity range bounds for Rapid`() {
        val noConcern = policy.severityRangeMs(TimeControl.RAPID, DecisionSeverity.NO_CONCERN)
        assertEquals(0L, noConcern.minMs)
        assertEquals(30_000L, noConcern.maxMs)

        val minor = policy.severityRangeMs(TimeControl.RAPID, DecisionSeverity.MINOR)
        assertEquals(30_000L, minor.minMs)
        assertEquals(60_000L, minor.maxMs)

        val significant = policy.severityRangeMs(TimeControl.RAPID, DecisionSeverity.SIGNIFICANT)
        assertEquals(60_000L, significant.minMs)
        assertEquals(120_000L, significant.maxMs)

        val critical = policy.severityRangeMs(TimeControl.RAPID, DecisionSeverity.CRITICAL)
        assertEquals(120_000L, critical.minMs)
        assertEquals(null, critical.maxMs)
    }

    @Test
    fun `computes severity range bounds for Classical`() {
        val noConcern = policy.severityRangeMs(TimeControl.CLASSICAL, DecisionSeverity.NO_CONCERN)
        assertEquals(0L, noConcern.minMs)
        assertEquals(60_000L, noConcern.maxMs)

        val minor = policy.severityRangeMs(TimeControl.CLASSICAL, DecisionSeverity.MINOR)
        assertEquals(60_000L, minor.minMs)
        assertEquals(180_000L, minor.maxMs)

        val significant = policy.severityRangeMs(TimeControl.CLASSICAL, DecisionSeverity.SIGNIFICANT)
        assertEquals(180_000L, significant.minMs)
        assertEquals(300_000L, significant.maxMs)

        val critical = policy.severityRangeMs(TimeControl.CLASSICAL, DecisionSeverity.CRITICAL)
        assertEquals(300_000L, critical.minMs)
        assertEquals(null, critical.maxMs)
    }

    @Test
    fun `computes severity range bounds for UNKNOWN severity or null time control`() {
        val unknownSeverity = policy.severityRangeMs(TimeControl.BLITZ, DecisionSeverity.UNKNOWN)
        assertEquals(0L, unknownSeverity.minMs)
        assertEquals(null, unknownSeverity.maxMs)

        val nullTimeControl = policy.severityRangeMs(null, DecisionSeverity.CRITICAL)
        assertEquals(0L, nullTimeControl.minMs)
        assertEquals(null, nullTimeControl.maxMs)
    }
}
