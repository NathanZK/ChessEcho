package com.chessecho.dto

data class MoveBreakdown(
    val move: String,
    val timesPlayed: Int,
    val averageLoss: Double,
    val practicalEvidence: PracticalEvidenceResponse? = null,
)
