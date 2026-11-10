package com.chessprobe.dto

import com.chessprobe.service.EvidenceCombination
import com.chessprobe.service.ObjectiveEvidenceState
import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

@JsonInclude(JsonInclude.Include.ALWAYS)
data class PuzzleResponse(
    val puzzleId: UUID,
    val fen: String,
    val playerColor: String,
    val targetMove: String?,
    val acceptableMoves: List<AcceptableMove>,
    val movesPlayed: List<MoveBreakdown>,
    val priority: Double,
    val timesReached: Int,
    val mistakeCount: Int,
    val mistakeRate: Double,
    val evalCp: Int? = null,
    val gameUrls: List<String> = emptyList(),
    val recommendationPriority: Double,
    val objectiveEvidenceState: ObjectiveEvidenceState,
    val evidenceCombination: EvidenceCombination?,
    val practicalEvidence: PracticalEvidenceResponse,
)
