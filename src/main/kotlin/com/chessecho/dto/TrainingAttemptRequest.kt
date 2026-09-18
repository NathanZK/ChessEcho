package com.chessecho.dto

import java.time.Instant
import java.util.UUID

data class TrainingAttemptRequest(
    val attemptId: UUID,
    val puzzleId: String,
    val mode: String,
    val elapsedMs: Long,
    val allowedMs: Long? = null,
    val outcome: String,
)

data class TrainingAttemptResponse(
    val attemptId: UUID,
    val puzzleId: String,
    val mode: String,
    val elapsedMs: Long,
    val allowedMs: Long? = null,
    val outcome: String,
    val recordedAt: Instant,
)
