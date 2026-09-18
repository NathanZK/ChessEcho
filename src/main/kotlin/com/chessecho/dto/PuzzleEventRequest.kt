package com.chessecho.dto

import com.chessecho.domain.SchedulingEventType
import java.util.UUID

data class PuzzleEventRequest(
    val positionId: UUID,
    val playerColor: String,
    val eventType: SchedulingEventType,
)
