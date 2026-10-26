package com.chessecho.dto

import java.time.Instant
import java.util.UUID

data class SimilarPositionsResponse(
    val queryFen: String,
    val candidatesEvaluated: Int,
    val results: List<SimilarPositionResult>,
)

data class SimilarPositionResult(
    val rank: Int,
    val score: Double,
    val positionId: UUID,
    val fen: String,
    val canonicalFen: String,
    val occurrences: List<SimilarPositionOccurrenceDto>,
)

data class SimilarPositionOccurrenceDto(
    val id: UUID,
    val platformGameId: String,
    val playedMove: String,
    val plyNumber: Int,
    val playerColor: String,
    val playedAt: Instant?,
    val opponentUsername: String?,
    val timeControl: String?,
    val gameResult: String?,
    val chessComUrl: String?,
)
