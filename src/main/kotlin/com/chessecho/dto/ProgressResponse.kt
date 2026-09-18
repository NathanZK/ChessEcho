package com.chessecho.dto

import java.time.Instant
import java.util.UUID

data class ProgressResponse(
    val positionId: UUID,
    val playerColor: String,
    val points: List<ProgressPoint>,
    val mistakeRateChange: Double?,
    val winRateChange: Double?,
    val assessment: String,
)

data class ProgressPoint(
    val occurredAt: Instant,
    val mistakeRate: Double,
    val winRate: Double,
    val attempts: Int,
)
