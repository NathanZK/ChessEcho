package com.chessprobe.dto

data class MoveBreakdown(
    val move: String,
    val timesPlayed: Int,
    val averageLoss: Double,
    val practicalEvidence: PracticalEvidenceResponse? = null,
)
