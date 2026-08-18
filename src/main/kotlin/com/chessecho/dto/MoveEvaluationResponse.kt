package com.chessecho.dto

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.ALWAYS)
data class MoveEvaluationResponse(
    val fen: String,
    val move: String,
    val bestMove: String,
    val bestEvalCp: Int?,
    val evalCp: Int?,
    val evalLoss: Double,
    val maxEvalLoss: Double,
    val threshold: Double,
    val acceptable: Boolean,
)
