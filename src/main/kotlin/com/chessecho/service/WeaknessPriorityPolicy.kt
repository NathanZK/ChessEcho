package com.chessecho.service

import com.chessecho.config.ComparatorMethod
import com.chessecho.config.ConfidenceMethod
import com.chessecho.config.PracticalEvidenceProperties
import org.springframework.stereotype.Component
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ObjectiveEvidenceState {
    INACCURATE,
    REASONABLE,
}

enum class PracticalAssessment {
    POOR,
    SUCCESSFUL,
}

enum class PracticalConfidenceState {
    INSUFFICIENT,
    INCONCLUSIVE,
    RANKING_ELIGIBLE,
}

enum class EvidenceCombination {
    CORROBORATED_OBJECTIVE_WEAKNESS,
    OBJECTIVE_WEAKNESS_PRACTICALLY_SUCCESSFUL,
    PRACTICAL_CONCERN,
    NO_CORROBORATED_WEAKNESS,
}

enum class PracticalConfigurationState {
    UNCALIBRATED,
    DISABLED,
    CALIBRATED,
}

data class WeaknessPriorityDecision(
    val recommendationPriority: Double,
    val confidenceState: PracticalConfidenceState,
    val confidenceMethod: ConfidenceMethod,
    val confidenceLowerBound: Double?,
    val confidenceUpperBound: Double?,
    val practicalAssessment: PracticalAssessment?,
    val evidenceCombination: EvidenceCombination?,
    val configurationState: PracticalConfigurationState,
    val rankingApplied: Boolean,
    val comparatorMethod: ComparatorMethod,
    val comparatorScoreRate: Double?,
    val sampleFloor: Int?,
    val meaningfulDifference: Double?,
    val observationWindowDays: Long?,
    val policyVersion: String,
)

@Component
class WeaknessPriorityPolicy(
    private val properties: PracticalEvidenceProperties,
) {
    val rankingEnabled: Boolean
        get() = properties.rankingEnabled

    val policyVersion: String
        get() = properties.policyVersion

    init {
        properties.validate()
    }

    fun evaluate(
        objectiveEvidenceState: ObjectiveEvidenceState,
        objectivePriority: Double,
        practicalEvidence: PracticalEvidenceSummary?,
    ): WeaknessPriorityDecision {
        if (practicalEvidence == null) {
            return baseDecision(objectivePriority)
        }

        val assessment = assess(practicalEvidence)
        val practicalAssessment = assessment.practicalAssessment
        if (
            assessment.confidenceState != PracticalConfidenceState.RANKING_ELIGIBLE ||
            practicalAssessment == null
        ) {
            return assessment.copy(recommendationPriority = objectivePriority)
        }

        val combined = combine(objectiveEvidenceState, objectivePriority, practicalAssessment)
        return combined.copy(
            confidenceState = assessment.confidenceState,
            confidenceLowerBound = assessment.confidenceLowerBound,
            confidenceUpperBound = assessment.confidenceUpperBound,
        )
    }

    fun assess(practicalEvidence: PracticalEvidenceSummary): WeaknessPriorityDecision {
        val configurationState = configurationState()
        if (configurationState != PracticalConfigurationState.CALIBRATED) {
            return baseDecision(
                objectivePriority = 0.0,
                configurationState = configurationState,
            )
        }

        val sampleFloor = requireNotNull(properties.sampleFloor)
        if (practicalEvidence.eligibleGames < sampleFloor) {
            return baseDecision(
                objectivePriority = 0.0,
                confidenceState = PracticalConfidenceState.INSUFFICIENT,
                configurationState = configurationState,
            )
        }

        val scoreRate = practicalEvidence.scoreRate
        if (scoreRate == null || !scoreRate.isFinite() || scoreRate !in 0.0..1.0) {
            return baseDecision(
                objectivePriority = 0.0,
                configurationState = configurationState,
            )
        }

        val (lowerBound, upperBound) =
            wilsonBounds(
                scoreRate = scoreRate,
                sampleSize = practicalEvidence.eligibleGames,
                zScore = requireNotNull(properties.wilsonZScore),
            )
        val comparator = requireNotNull(properties.comparatorScoreRate)
        val difference = requireNotNull(properties.meaningfulDifference)
        val practicalAssessment =
            when {
                upperBound <= comparator - difference -> PracticalAssessment.POOR
                lowerBound >= comparator + difference -> PracticalAssessment.SUCCESSFUL
                else -> null
            }

        return baseDecision(
            objectivePriority = 0.0,
            confidenceState =
                if (practicalAssessment == null) {
                    PracticalConfidenceState.INCONCLUSIVE
                } else {
                    PracticalConfidenceState.RANKING_ELIGIBLE
                },
            confidenceLowerBound = lowerBound,
            confidenceUpperBound = upperBound,
            practicalAssessment = practicalAssessment,
            configurationState = configurationState,
        )
    }

    fun combine(
        objectiveEvidenceState: ObjectiveEvidenceState,
        objectivePriority: Double,
        practicalAssessment: PracticalAssessment,
    ): WeaknessPriorityDecision {
        if (configurationState() != PracticalConfigurationState.CALIBRATED) {
            return baseDecision(objectivePriority)
        }

        val adjustment = requireNotNull(properties.maxPriorityAdjustment)
        val multiplier =
            when (practicalAssessment) {
                PracticalAssessment.POOR -> 1.0 + adjustment
                PracticalAssessment.SUCCESSFUL -> 1.0 - adjustment
            }
        val evidenceCombination =
            when (objectiveEvidenceState to practicalAssessment) {
                ObjectiveEvidenceState.INACCURATE to PracticalAssessment.POOR ->
                    EvidenceCombination.CORROBORATED_OBJECTIVE_WEAKNESS

                ObjectiveEvidenceState.INACCURATE to PracticalAssessment.SUCCESSFUL ->
                    EvidenceCombination.OBJECTIVE_WEAKNESS_PRACTICALLY_SUCCESSFUL

                ObjectiveEvidenceState.REASONABLE to PracticalAssessment.POOR ->
                    EvidenceCombination.PRACTICAL_CONCERN

                ObjectiveEvidenceState.REASONABLE to PracticalAssessment.SUCCESSFUL ->
                    EvidenceCombination.NO_CORROBORATED_WEAKNESS

                else -> error("Unsupported objective/practical evidence combination")
            }

        return baseDecision(
            objectivePriority = objectivePriority * multiplier,
            confidenceState = PracticalConfidenceState.RANKING_ELIGIBLE,
            practicalAssessment = practicalAssessment,
            evidenceCombination = evidenceCombination,
            configurationState = PracticalConfigurationState.CALIBRATED,
            rankingApplied = true,
        )
    }

    private fun configurationState(): PracticalConfigurationState =
        when {
            !properties.isCalibrated() -> PracticalConfigurationState.UNCALIBRATED
            !properties.rankingEnabled -> PracticalConfigurationState.DISABLED
            else -> PracticalConfigurationState.CALIBRATED
        }

    private fun baseDecision(
        objectivePriority: Double,
        confidenceState: PracticalConfidenceState = PracticalConfidenceState.INCONCLUSIVE,
        confidenceLowerBound: Double? = null,
        confidenceUpperBound: Double? = null,
        practicalAssessment: PracticalAssessment? = null,
        evidenceCombination: EvidenceCombination? = null,
        configurationState: PracticalConfigurationState = configurationState(),
        rankingApplied: Boolean = false,
    ): WeaknessPriorityDecision =
        WeaknessPriorityDecision(
            recommendationPriority = objectivePriority,
            confidenceState = confidenceState,
            confidenceMethod = properties.confidenceMethod,
            confidenceLowerBound = confidenceLowerBound,
            confidenceUpperBound = confidenceUpperBound,
            practicalAssessment = practicalAssessment,
            evidenceCombination = evidenceCombination,
            configurationState = configurationState,
            rankingApplied = rankingApplied,
            comparatorMethod = properties.comparatorMethod,
            comparatorScoreRate = properties.comparatorScoreRate,
            sampleFloor = properties.sampleFloor,
            meaningfulDifference = properties.meaningfulDifference,
            observationWindowDays = properties.observationWindowDays,
            policyVersion = properties.policyVersion,
        )

    private fun wilsonBounds(
        scoreRate: Double,
        sampleSize: Int,
        zScore: Double,
    ): Pair<Double, Double> {
        val size = sampleSize.toDouble()
        val zSquared = zScore * zScore
        val denominator = 1.0 + zSquared / size
        val center = (scoreRate + zSquared / (2.0 * size)) / denominator
        val margin =
            zScore *
                sqrt((scoreRate * (1.0 - scoreRate) + zSquared / (4.0 * size)) / size) /
                denominator
        return max(0.0, center - margin) to min(1.0, center + margin)
    }
}
