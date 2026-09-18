package com.chessecho.service

import com.chessecho.domain.SchedulingEventType
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.exp

typealias SchedulingEventType = com.chessecho.domain.SchedulingEventType

data class SchedulingEvent(
    val type: SchedulingEventType,
    val occurredAt: Instant,
    val sourceOccurrenceId: UUID? = null,
)

data class Candidate(
    val positionId: UUID,
    val baselinePriority: Double,
    val events: List<SchedulingEvent>,
)

@Component
class AdaptivePuzzleSchedulingPolicy {
    fun schedule(
        baselinePriority: Double,
        positionId: UUID,
        events: List<SchedulingEvent>,
        asOf: Instant,
    ): Double {
        val mistakeBoost =
            events
                .filter { it.type == SchedulingEventType.GAME_MISTAKE }
                .sumOf {
                    exp(-Duration.between(it.occurredAt, asOf).toHours().coerceAtLeast(0) / (24.0 * 14.0)) * 12.0
                }
        val handled =
            events.filter { it.type == SchedulingEventType.GAME_HANDLED_SUCCESSFULLY }
                .sumOf { exp(-Duration.between(it.occurredAt, asOf).toHours().coerceAtLeast(0) / (24.0 * 21.0)) * 4.0 }
        val failures = events.count { it.type == SchedulingEventType.FAILED }
        val failureBoost = minOf(3.0, failures * 0.5)
        val solved = events.filter { it.type == SchedulingEventType.SOLVED }.maxOfOrNull { it.occurredAt }
        val skipped = events.filter { it.type == SchedulingEventType.SKIPPED }.maxOfOrNull { it.occurredAt }
        val cooldown =
            solved?.let { exp(-Duration.between(it, asOf).toHours().coerceAtLeast(0) / (24.0 * 3.0)) * 2.0 } ?: 0.0
        val skipPenalty =
            skipped?.let {
                exp(-Duration.between(it, asOf).toHours().coerceAtLeast(0) / (24.0 * 2.0)) * 0.75
            } ?: 0.0
        return (baselinePriority + mistakeBoost - handled + failureBoost - cooldown - skipPenalty)
            .coerceAtLeast(0.0)
    }

    fun sourceClaimKey(
        sourceOccurrenceId: UUID,
        eventType: SchedulingEventType,
    ): String {
        return "$sourceOccurrenceId:$eventType"
    }

    fun order(
        candidates: List<Candidate>,
        asOf: Instant,
    ): List<Candidate> {
        return candidates.sortedWith(
            compareByDescending<Candidate> { schedule(it.baselinePriority, it.positionId, it.events, asOf) }
                .thenBy { it.positionId.toString() },
        )
    }
}
