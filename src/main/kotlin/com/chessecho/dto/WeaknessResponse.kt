package com.chessecho.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

@JsonInclude(JsonInclude.Include.ALWAYS)
data class WeaknessResponse(
    val positionId: UUID,
    val fen: String,
    val timesReached: Int,
    val mistakeCount: Int,
    val mistakeRate: Double,
    val averageLoss: Double,
    val priority: Double,
    val bestMove: String?,
    val acceptableMoves: List<AcceptableMove>,
    val movesPlayed: List<MoveBreakdown>,
    val gameUrls: List<String> = emptyList(),
)
