package com.chessecho.dto

import java.time.Instant
import java.util.UUID

data class ProgressResponse(
    val positionId: UUID,
    val playerColor: String,
    val baseline: ProgressBaseline?,
    val points: List<ProgressPoint>,
    val currentIntervalState: ProgressIntervalState,
    val excludedUndatedEncounters: Int,
    val mistakeRateChange: Double?,
    val winRateChange: Double?,
    val assessment: String,
)

data class ProgressBaseline(
    val occurredAt: Instant,
    val mistakeRate: Double,
    val winRate: Double,
    val sourceEncounterCount: Int,
)

data class ProgressPoint(
    val checkpointId: UUID,
    val occurredAt: Instant,
    val mistakeRate: Double,
    val winRate: Double,
    val attempts: Int,
    val open: Boolean,
)

enum class ProgressIntervalState {
    NO_CHECKPOINT,
    OPEN_AWAITING_EVIDENCE,
    MEASURED_OPEN,
}
