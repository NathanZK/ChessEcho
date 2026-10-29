package com.chessecho.service

import com.chessecho.domain.DecisionSeverity
import com.chessecho.domain.TimeControl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DecisionTimingFilterIntersectionTest {
    private val policy = DecisionTimingPolicy()

    @Test
    fun `when minDuration is below severity range, severity minimum governs`() {
        // Blitz MINOR is 15s to 30s (15_000ms to 30_000ms). User requests 10s.
        val bounds =
            policy.calculateEffectiveBounds(
                userMinDurationSeconds = 10,
                severity = DecisionSeverity.MINOR,
                timeControl = TimeControl.BLITZ,
            )

        assertEquals(15_000L, bounds.minMs)
        assertEquals(30_000L, bounds.maxMs)
        assertFalse(bounds.isEmpty)
    }

    @Test
    fun `when minDuration is inside severity range, user minimum duration narrows the lower bound`() {
        // Blitz MINOR is 15s to 30s. User requests 20s.
        val bounds =
            policy.calculateEffectiveBounds(
                userMinDurationSeconds = 20,
                severity = DecisionSeverity.MINOR,
                timeControl = TimeControl.BLITZ,
            )

        assertEquals(20_000L, bounds.minMs)
        assertEquals(30_000L, bounds.maxMs)
        assertFalse(bounds.isEmpty)
    }

    @Test
    fun `when minDuration is at upper boundary of severity range, intersection is empty`() {
        // Blitz MINOR is 15s to 30s. User requests 30s.
        val bounds =
            policy.calculateEffectiveBounds(
                userMinDurationSeconds = 30,
                severity = DecisionSeverity.MINOR,
                timeControl = TimeControl.BLITZ,
            )

        assertEquals(30_000L, bounds.minMs)
        assertEquals(30_000L, bounds.maxMs)
        assertTrue(bounds.isEmpty)
    }

    @Test
    fun `when minDuration is above severity range, intersection is empty`() {
        // Blitz MINOR is 15s to 30s. User requests 45s.
        val bounds =
            policy.calculateEffectiveBounds(
                userMinDurationSeconds = 45,
                severity = DecisionSeverity.MINOR,
                timeControl = TimeControl.BLITZ,
            )

        assertEquals(45_000L, bounds.minMs)
        assertEquals(30_000L, bounds.maxMs)
        assertTrue(bounds.isEmpty)
    }

    @Test
    fun `when severity is CRITICAL, upper bound is null and user min duration can exceed critical threshold`() {
        // Blitz CRITICAL is >= 60s. User requests 75s.
        val bounds =
            policy.calculateEffectiveBounds(
                userMinDurationSeconds = 75,
                severity = DecisionSeverity.CRITICAL,
                timeControl = TimeControl.BLITZ,
            )

        assertEquals(75_000L, bounds.minMs)
        assertNull(bounds.maxMs)
        assertFalse(bounds.isEmpty)
    }

    @Test
    fun `when severity is null, bounds span from user min duration to infinity`() {
        val bounds =
            policy.calculateEffectiveBounds(
                userMinDurationSeconds = 25,
                severity = null,
                timeControl = TimeControl.RAPID,
            )

        assertEquals(25_000L, bounds.minMs)
        assertNull(bounds.maxMs)
        assertFalse(bounds.isEmpty)
    }

    @Test
    fun `when user min duration is null or negative, it defaults to 0 ms`() {
        val nullMin =
            policy.calculateEffectiveBounds(
                userMinDurationSeconds = null,
                severity = DecisionSeverity.NO_CONCERN,
                timeControl = TimeControl.BULLET,
            )
        assertEquals(0L, nullMin.minMs)
        assertEquals(5_000L, nullMin.maxMs)
        assertFalse(nullMin.isEmpty)

        val negativeMin =
            policy.calculateEffectiveBounds(
                userMinDurationSeconds = -5,
                severity = DecisionSeverity.NO_CONCERN,
                timeControl = TimeControl.BULLET,
            )
        assertEquals(0L, negativeMin.minMs)
        assertEquals(5_000L, negativeMin.maxMs)
        assertFalse(negativeMin.isEmpty)
    }

    @Test
    fun `when timeControl is null, returns user min duration with no upper bound`() {
        val bounds =
            policy.calculateEffectiveBounds(
                userMinDurationSeconds = 15,
                severity = DecisionSeverity.SIGNIFICANT,
                timeControl = null,
            )

        assertEquals(15_000L, bounds.minMs)
        assertNull(bounds.maxMs)
        assertFalse(bounds.isEmpty)
    }
}
