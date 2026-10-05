package com.chessecho.dto

import com.chessecho.domain.SchedulingEventType
import java.util.UUID

data class PuzzleEventRequest(
    val positionId: UUID,
    val playerColor: String,
    val eventType: SchedulingEventType,
    val accountId: UUID? = null,
    val submissionId: UUID? = null,
    val submittedMove: String? = null,
)

data class PuzzleSubmissionReceipt(val submissionId: UUID)

data class PuzzleAttemptCountResponse(val solvedCount: Long, val failedCount: Long)
