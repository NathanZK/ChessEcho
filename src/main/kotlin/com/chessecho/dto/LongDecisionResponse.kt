package com.chessecho.dto

import java.time.Instant
import java.util.UUID

data class LongDecisionOccurrenceResponse(
    val id: UUID,
    val positionId: UUID,
    val fen: String,
    val playerColor: String,
    val plyNumber: Int,
    val movePlayed: String,
    val decisionTimeMs: Long,
    val timeControl: String,
    val platformGameId: String,
    val playedAt: Instant?,
    val opponentUsername: String?,
)

data class LongDecisionPageResponse(
    val content: List<LongDecisionOccurrenceResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
)
