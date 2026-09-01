package com.chessecho.dto

import com.chessecho.config.ComparatorMethod
import com.chessecho.config.ConfidenceMethod
import com.chessecho.service.PracticalAssessment
import com.chessecho.service.PracticalConfidenceState
import com.chessecho.service.PracticalConfigurationState
import com.fasterxml.jackson.annotation.JsonInclude

enum class PracticalEvidenceScopeType {
    POSITION,
    DECISION,
}

enum class PracticalEvidenceCohort {
    STANDARD_ALL_IMPORTED_TIME_CONTROLS,
}

@JsonInclude(JsonInclude.Include.ALWAYS)
data class PracticalEvidenceResponse(
    val scope: PracticalEvidenceScopeType,
    val decisionSan: String?,
    val candidateGames: Int,
    val eligibleGames: Int,
    val ineligibleGames: Int,
    val excludedGames: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val sideCorroborationConflictGames: Int,
    val scoreRate: Double?,
    val comparatorMethod: ComparatorMethod,
    val comparatorScoreRate: Double?,
    val confidenceMethod: ConfidenceMethod,
    val confidenceLowerBound: Double?,
    val confidenceUpperBound: Double?,
    val confidenceState: PracticalConfidenceState,
    val practicalAssessment: PracticalAssessment?,
    val sampleFloor: Int?,
    val meaningfulDifference: Double?,
    val observationWindowDays: Long?,
    val cohort: PracticalEvidenceCohort,
    val policyVersion: String,
    val configurationState: PracticalConfigurationState,
    val rankingApplied: Boolean,
)
