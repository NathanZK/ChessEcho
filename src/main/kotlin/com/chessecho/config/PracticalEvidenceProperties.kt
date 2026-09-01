package com.chessecho.config

import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.context.properties.ConfigurationProperties

enum class ComparatorMethod {
    DISABLED,
    FIXED_SCORE_RATE,
}

enum class ConfidenceMethod {
    DISABLED,
    BERNOULLI_WILSON_SCORE_POINTS_HALF_DRAW_CONSERVATIVE,
}

@ConfigurationProperties(prefix = "chess.weakness.practical")
data class PracticalEvidenceProperties(
    val rankingEnabled: Boolean = false,
    val sampleFloor: Int? = null,
    val comparatorMethod: ComparatorMethod = ComparatorMethod.DISABLED,
    val comparatorScoreRate: Double? = null,
    val confidenceMethod: ConfidenceMethod = ConfidenceMethod.DISABLED,
    val wilsonZScore: Double? = null,
    val meaningfulDifference: Double? = null,
    val maxPriorityAdjustment: Double? = null,
    val observationWindowDays: Long? = null,
    val policyVersion: String = "uncalibrated-v1",
) : InitializingBean {
    override fun afterPropertiesSet() {
        validate()
    }

    fun validate() {
        require(policyVersion.isNotBlank()) { "policyVersion must not be blank" }
        sampleFloor?.let { require(it >= 1) { "sampleFloor must be at least one" } }
        comparatorScoreRate?.let { requireRate(it, "comparatorScoreRate") }
        wilsonZScore?.let {
            require(it.isFinite() && it > 0.0) { "wilsonZScore must be positive and finite" }
        }
        meaningfulDifference?.let { requireRate(it, "meaningfulDifference") }
        maxPriorityAdjustment?.let {
            require(it.isFinite() && it >= 0.0 && it < 1.0) {
                "maxPriorityAdjustment must be finite and in [0, 1)"
            }
        }
        observationWindowDays?.let {
            require(it > 0) { "observationWindowDays must be positive" }
        }

        if (comparatorScoreRate != null && meaningfulDifference != null) {
            require(comparatorScoreRate - meaningfulDifference >= 0.0) {
                "comparatorScoreRate - meaningfulDifference must be in [0, 1]"
            }
            require(comparatorScoreRate + meaningfulDifference <= 1.0) {
                "comparatorScoreRate + meaningfulDifference must be in [0, 1]"
            }
        }

        if (rankingEnabled) {
            require(comparatorMethod == ComparatorMethod.FIXED_SCORE_RATE) {
                "ranking requires FIXED_SCORE_RATE comparatorMethod"
            }
            require(
                confidenceMethod ==
                    ConfidenceMethod.BERNOULLI_WILSON_SCORE_POINTS_HALF_DRAW_CONSERVATIVE,
            ) {
                "ranking requires BERNOULLI_WILSON_SCORE_POINTS_HALF_DRAW_CONSERVATIVE confidenceMethod"
            }
            requireNotNull(sampleFloor) { "ranking requires sampleFloor" }
            requireNotNull(comparatorScoreRate) { "ranking requires comparatorScoreRate" }
            requireNotNull(wilsonZScore) { "ranking requires wilsonZScore" }
            requireNotNull(meaningfulDifference) { "ranking requires meaningfulDifference" }
            requireNotNull(maxPriorityAdjustment) { "ranking requires maxPriorityAdjustment" }
        }
    }

    fun isCalibrated(): Boolean =
        sampleFloor != null &&
            comparatorMethod == ComparatorMethod.FIXED_SCORE_RATE &&
            comparatorScoreRate != null &&
            confidenceMethod ==
            ConfidenceMethod.BERNOULLI_WILSON_SCORE_POINTS_HALF_DRAW_CONSERVATIVE &&
            wilsonZScore != null &&
            meaningfulDifference != null &&
            maxPriorityAdjustment != null

    private fun requireRate(
        value: Double,
        name: String,
    ) {
        require(value.isFinite() && value in 0.0..1.0) {
            "$name must be finite and in [0, 1]"
        }
    }
}
