package com.chessecho.service

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptivePuzzleSchedulingPolicyTest {
    private val policy = AdaptivePuzzleSchedulingPolicy()
    private val position = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val occurrence = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val now = Instant.parse("2026-01-31T00:00:00Z")

    @Test
    fun `retired puzzle event values are absent from the persisted enum`() {
        val retiredValues = setOf("PRESENTED", "STARTED", "SKIPPED")

        assertTrue(SchedulingEventType.entries.none { it.name in retiredValues })
    }

    @Test
    fun `solved temporarily deprioritizes while failure increases priority`() {
        val solved = policy.schedule(10.0, position, listOf(event(SchedulingEventType.SOLVED)), now)
        val failed = policy.schedule(10.0, position, listOf(event(SchedulingEventType.FAILED)), now)

        assertTrue(solved < 10.0)
        assertTrue(failed > 10.0)
    }

    @Test
    fun `recent actual game mistake outranks ordinary training history`() {
        val trainingFailure = policy.schedule(10.0, position, listOf(event(SchedulingEventType.FAILED)), now)
        val actualMistake = policy.schedule(10.0, position, listOf(event(SchedulingEventType.GAME_MISTAKE)), now)

        assertTrue(actualMistake > trainingFailure)
    }

    @Test
    fun `actual game mistake decays and later success reduces urgency`() {
        val recent = policy.schedule(10.0, position, listOf(event(SchedulingEventType.GAME_MISTAKE, now.minusSeconds(3600))), now)
        val old =
            policy.schedule(
                10.0,
                position,
                listOf(event(SchedulingEventType.GAME_MISTAKE, now.minusSeconds(60L * 60L * 24L * 30L))),
                now,
            )
        val recovered =
            policy.schedule(
                10.0,
                position,
                listOf(
                    event(SchedulingEventType.GAME_MISTAKE, now.minusSeconds(3600)),
                    event(SchedulingEventType.GAME_HANDLED_SUCCESSFULLY),
                ),
                now,
            )

        assertTrue(recent > old)
        assertTrue(recovered < recent)
    }

    @Test
    fun `training failure escalation is bounded below substantially more severe weakness`() {
        val repeatedFailure =
            policy.schedule(
                2.0,
                position,
                List(20) { event(SchedulingEventType.FAILED) },
                now,
            )
        val severeUntouched = policy.schedule(10.0, position, emptyList(), now)

        assertTrue(repeatedFailure < severeUntouched)
    }

    @Test
    fun `solved cooldown remains finite`() {
        val solved = policy.schedule(10.0, position, listOf(event(SchedulingEventType.SOLVED)), now)

        assertTrue(solved < 10.0)
        assertTrue(solved.isFinite())
    }

    @Test
    fun `source occurrence and event type are independently idempotent`() {
        assertTrue(
            policy.sourceClaimKey(occurrence, SchedulingEventType.GAME_MISTAKE) ==
                policy.sourceClaimKey(occurrence, SchedulingEventType.GAME_MISTAKE),
        )
        assertTrue(
            policy.sourceClaimKey(occurrence, SchedulingEventType.GAME_MISTAKE) !=
                policy.sourceClaimKey(UUID.fromString("00000000-0000-0000-0000-000000000003"), SchedulingEventType.GAME_MISTAKE),
        )
    }

    @Test
    fun `ordering tie break is stable and untouched positions remain eligible`() {
        val first =
            policy.order(
                listOf(
                    Candidate(position, 4.0, emptyList()),
                    Candidate(UUID.fromString("00000000-0000-0000-0000-000000000004"), 4.0, emptyList()),
                ),
                now,
            )
        val second = policy.order(first, now)

        assertEquals(first.map { it.positionId }, second.map { it.positionId })
    }

    @Test
    fun `failure boost increments by 0_5 per failure and clamps strictly at 3_0 ceiling`() {
        val zeroFailures = policy.schedule(10.0, position, emptyList(), now)
        val oneFailure = policy.schedule(10.0, position, List(1) { event(SchedulingEventType.FAILED, now) }, now)
        val twoFailures = policy.schedule(10.0, position, List(2) { event(SchedulingEventType.FAILED, now) }, now)
        val sixFailures = policy.schedule(10.0, position, List(6) { event(SchedulingEventType.FAILED, now) }, now)
        val tenFailures = policy.schedule(10.0, position, List(10) { event(SchedulingEventType.FAILED, now) }, now)

        assertEquals(10.0, zeroFailures, 0.001)
        assertEquals(10.5, oneFailure, 0.001)
        assertEquals(11.0, twoFailures, 0.001)
        assertEquals(13.0, sixFailures, 0.001)
        assertEquals(13.0, tenFailures, 0.001)
    }

    @Test
    fun `game mistake boost decays exponentially across multi-day intervals`() {
        val day0 = policy.schedule(10.0, position, listOf(event(SchedulingEventType.GAME_MISTAKE, now)), now)
        val day14 =
            policy.schedule(
                10.0,
                position,
                listOf(event(SchedulingEventType.GAME_MISTAKE, now.minus(Duration.ofDays(14)))),
                now,
            )
        val day28 =
            policy.schedule(
                10.0,
                position,
                listOf(event(SchedulingEventType.GAME_MISTAKE, now.minus(Duration.ofDays(28)))),
                now,
            )

        assertEquals(22.0, day0, 0.0001)
        assertEquals(14.41455, day14, 0.0001)
        assertEquals(11.62402, day28, 0.0001)
    }

    @Test
    fun `game handled successfully dampens urgency exponentially across multi-day intervals`() {
        val day0 = policy.schedule(10.0, position, listOf(event(SchedulingEventType.GAME_HANDLED_SUCCESSFULLY, now)), now)
        val day21 =
            policy.schedule(
                10.0,
                position,
                listOf(event(SchedulingEventType.GAME_HANDLED_SUCCESSFULLY, now.minus(Duration.ofDays(21)))),
                now,
            )
        val day42 =
            policy.schedule(
                10.0,
                position,
                listOf(event(SchedulingEventType.GAME_HANDLED_SUCCESSFULLY, now.minus(Duration.ofDays(42)))),
                now,
            )

        assertEquals(6.0, day0, 0.0001)
        assertEquals(8.52848, day21, 0.0001)
        assertEquals(9.45866, day42, 0.0001)
    }

    @Test
    fun `solved cooldown deprioritizes position and decays across multi-day intervals`() {
        val day0 = policy.schedule(10.0, position, listOf(event(SchedulingEventType.SOLVED, now)), now)
        val day3 =
            policy.schedule(
                10.0,
                position,
                listOf(event(SchedulingEventType.SOLVED, now.minus(Duration.ofDays(3)))),
                now,
            )
        val day6 =
            policy.schedule(
                10.0,
                position,
                listOf(event(SchedulingEventType.SOLVED, now.minus(Duration.ofDays(6)))),
                now,
            )

        assertEquals(8.0, day0, 0.0001)
        assertEquals(9.26424, day3, 0.0001)
        assertEquals(9.72933, day6, 0.0001)
    }

    @Test
    fun `schedule clamps negative accumulated score to zero floor`() {
        val score =
            policy.schedule(
                1.0,
                position,
                listOf(event(SchedulingEventType.GAME_HANDLED_SUCCESSFULLY, now)),
                now,
            )

        assertEquals(0.0, score, 0.0001)
    }

    private fun event(
        type: SchedulingEventType,
        occurredAt: Instant = now.minusSeconds(60),
    ): SchedulingEvent =
        SchedulingEvent(
            type = type,
            occurredAt = occurredAt,
            sourceOccurrenceId = if (type.name.startsWith("GAME_")) occurrence else null,
        )
}
