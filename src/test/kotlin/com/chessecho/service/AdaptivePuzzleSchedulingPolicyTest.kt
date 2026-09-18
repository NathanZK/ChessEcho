package com.chessecho.service

import org.junit.jupiter.api.Test
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
    fun `presentation and start do not change baseline urgency`() {
        val baseline = policy.schedule(10.0, position, emptyList(), now)
        val exposed =
            policy.schedule(
                10.0,
                position,
                listOf(event(SchedulingEventType.PRESENTED), event(SchedulingEventType.STARTED)),
                now,
            )

        assertEquals(baseline, exposed)
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
    fun `solved and skipped are temporary deprioritization not removal`() {
        val solved = policy.schedule(10.0, position, listOf(event(SchedulingEventType.SOLVED)), now)
        val skipped = policy.schedule(10.0, position, listOf(event(SchedulingEventType.SKIPPED)), now)

        assertTrue(solved < 10.0)
        assertTrue(skipped < 10.0)
        assertTrue(solved.isFinite() && skipped.isFinite())
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
