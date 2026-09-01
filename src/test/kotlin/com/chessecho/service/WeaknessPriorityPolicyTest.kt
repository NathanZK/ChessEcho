package com.chessecho.service

import com.chessecho.config.ComparatorMethod
import com.chessecho.config.ConfidenceMethod
import com.chessecho.config.PracticalEvidenceProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeaknessPriorityPolicyTest {
    @Test
    fun `below sample floor is insufficient and cannot influence objective priority`() {
        val decision =
            policy().evaluate(
                ObjectiveEvidenceState.INACCURATE,
                10.0,
                summary(wins = 0, draws = 0, losses = 4),
            )

        assertEquals("INSUFFICIENT", decision.confidenceState.name)
        assertNull(decision.practicalAssessment)
        assertEquals(10.0, decision.recommendationPriority)
        assertFalse(decision.rankingApplied)
        assertNull(decision.evidenceCombination)
    }

    @Test
    fun `exactly at sample floor poor evidence receives bounded boost`() {
        val decision =
            policy().evaluate(
                ObjectiveEvidenceState.INACCURATE,
                10.0,
                summary(wins = 0, draws = 0, losses = 5),
            )

        assertEquals("RANKING_ELIGIBLE", decision.confidenceState.name)
        assertEquals(PracticalAssessment.POOR, decision.practicalAssessment)
        assertEquals("CORROBORATED_OBJECTIVE_WEAKNESS", decision.evidenceCombination?.name)
        assertEquals(12.5, decision.recommendationPriority)
        assertTrue(decision.rankingApplied)
    }

    @Test
    fun `poor comparator boundary is inclusive`() {
        val evidence = summary(wins = 2, draws = 2, losses = 6)
        val preliminary = policy(comparator = 0.5, difference = 0.0).evaluate(ObjectiveEvidenceState.INACCURATE, 1.0, evidence)
        val upper = requireNotNull(preliminary.confidenceUpperBound)

        val atBoundary =
            policy(comparator = upper, difference = 0.0)
                .evaluate(ObjectiveEvidenceState.INACCURATE, 1.0, evidence)

        assertEquals(PracticalAssessment.POOR, atBoundary.practicalAssessment)
        assertTrue(atBoundary.rankingApplied)
    }

    @Test
    fun `successful comparator boundary is inclusive`() {
        val evidence = summary(wins = 6, draws = 2, losses = 2)
        val preliminary = policy(comparator = 0.5, difference = 0.0).evaluate(ObjectiveEvidenceState.INACCURATE, 1.0, evidence)
        val lower = requireNotNull(preliminary.confidenceLowerBound)

        val atBoundary =
            policy(comparator = lower, difference = 0.0)
                .evaluate(ObjectiveEvidenceState.INACCURATE, 1.0, evidence)

        assertEquals(PracticalAssessment.SUCCESSFUL, atBoundary.practicalAssessment)
        assertTrue(atBoundary.rankingApplied)
    }

    @Test
    fun `overlapping conservative interval is inconclusive and objective-only`() {
        val decision =
            policy().evaluate(
                ObjectiveEvidenceState.INACCURATE,
                7.0,
                summary(wins = 2, draws = 1, losses = 2),
            )

        assertEquals("INCONCLUSIVE", decision.confidenceState.name)
        assertNull(decision.practicalAssessment)
        assertEquals(7.0, decision.recommendationPriority)
        assertFalse(decision.rankingApplied)
    }

    @Test
    fun `zero eligible games remains insufficient with null score and no interval`() {
        val evidence = summary(wins = 0, draws = 0, losses = 0, excluded = 3)
        val decision = policy().evaluate(ObjectiveEvidenceState.INACCURATE, 4.0, evidence)

        assertNull(evidence.scoreRate)
        assertEquals("INSUFFICIENT", decision.confidenceState.name)
        assertNull(decision.confidenceLowerBound)
        assertNull(decision.confidenceUpperBound)
        assertEquals(4.0, decision.recommendationPriority)
    }

    @Test
    fun `Wilson provenance states conservative half-draw approximation and uses fractional score points`() {
        val decision =
            policy(zScore = 1.0).evaluate(
                ObjectiveEvidenceState.INACCURATE,
                1.0,
                summary(wins = 0, draws = 10, losses = 0),
            )

        assertEquals(
            "BERNOULLI_WILSON_SCORE_POINTS_HALF_DRAW_CONSERVATIVE",
            decision.confidenceMethod.name,
        )
        assertEquals(0.3492443277111182, requireNotNull(decision.confidenceLowerBound), 1e-12)
        assertEquals(0.6507556722888819, requireNotNull(decision.confidenceUpperBound), 1e-12)
    }

    @Test
    fun `ranking disabled remains display-only even with complete poor calibration`() {
        val properties = calibrated().copy(rankingEnabled = false)
        val decision =
            WeaknessPriorityPolicy(properties).evaluate(
                ObjectiveEvidenceState.INACCURATE,
                10.0,
                summary(wins = 0, draws = 0, losses = 20),
            )

        assertEquals("INCONCLUSIVE", decision.confidenceState.name)
        assertEquals("DISABLED", decision.configurationState.name)
        assertEquals(10.0, decision.recommendationPriority)
        assertFalse(decision.rankingApplied)
    }

    @Test
    fun `uncalibrated default is inconclusive objective-only evidence`() {
        val properties = PracticalEvidenceProperties()
        val decision =
            WeaknessPriorityPolicy(properties).evaluate(
                ObjectiveEvidenceState.INACCURATE,
                10.0,
                summary(wins = 0, draws = 0, losses = 20),
            )

        assertEquals("INCONCLUSIVE", decision.confidenceState.name)
        assertEquals("UNCALIBRATED", decision.configurationState.name)
        assertEquals(10.0, decision.recommendationPriority)
        assertFalse(decision.rankingApplied)
    }

    @Test
    fun `missing practical evidence falls back to objective priority`() {
        val decision = policy().evaluate(ObjectiveEvidenceState.INACCURATE, 9.0, null)

        assertEquals(9.0, decision.recommendationPriority)
        assertFalse(decision.rankingApplied)
        assertNull(decision.evidenceCombination)
        assertNull(decision.practicalAssessment)
    }

    @Test
    fun `all objective and practical quadrants have direct non-causal policy labels`() {
        val policy = policy()
        val cases =
            listOf(
                Triple(
                    ObjectiveEvidenceState.INACCURATE,
                    PracticalAssessment.POOR,
                    "CORROBORATED_OBJECTIVE_WEAKNESS",
                ),
                Triple(
                    ObjectiveEvidenceState.INACCURATE,
                    PracticalAssessment.SUCCESSFUL,
                    "OBJECTIVE_WEAKNESS_PRACTICALLY_SUCCESSFUL",
                ),
                Triple(
                    ObjectiveEvidenceState.REASONABLE,
                    PracticalAssessment.POOR,
                    "PRACTICAL_CONCERN",
                ),
                Triple(
                    ObjectiveEvidenceState.REASONABLE,
                    PracticalAssessment.SUCCESSFUL,
                    "NO_CORROBORATED_WEAKNESS",
                ),
            )

        cases.forEach { (objective, practical, expectedLabel) ->
            val decision = policy.combine(objective, 10.0, practical)

            assertEquals(expectedLabel, decision.evidenceCombination?.name)
            assertTrue(decision.rankingApplied)
        }

        assertEquals(12.5, policy.combine(ObjectiveEvidenceState.INACCURATE, 10.0, PracticalAssessment.POOR).recommendationPriority)
        assertEquals(7.5, policy.combine(ObjectiveEvidenceState.INACCURATE, 10.0, PracticalAssessment.SUCCESSFUL).recommendationPriority)
        assertEquals(12.5, policy.combine(ObjectiveEvidenceState.REASONABLE, 10.0, PracticalAssessment.POOR).recommendationPriority)
        assertEquals(7.5, policy.combine(ObjectiveEvidenceState.REASONABLE, 10.0, PracticalAssessment.SUCCESSFUL).recommendationPriority)
    }

    @Test
    fun `ranking disabled combine keeps an eligible assessment objective only`() {
        val policy = WeaknessPriorityPolicy(calibrated().copy(rankingEnabled = false))

        val decision =
            policy.combine(
                ObjectiveEvidenceState.INACCURATE,
                10.0,
                PracticalAssessment.POOR,
            )

        assertEquals(10.0, decision.recommendationPriority)
        assertFalse(decision.rankingApplied)
        assertNull(decision.evidenceCombination)
    }

    @Test
    fun `enabled configuration fails closed for every invalid boundary`() {
        val invalid =
            listOf(
                PracticalEvidenceProperties(rankingEnabled = true),
                calibrated().copy(sampleFloor = 0),
                calibrated().copy(comparatorScoreRate = -0.01),
                calibrated().copy(comparatorScoreRate = 1.01),
                calibrated().copy(wilsonZScore = 0.0),
                calibrated().copy(meaningfulDifference = -0.01),
                calibrated().copy(meaningfulDifference = 1.01),
                calibrated().copy(comparatorScoreRate = 0.05, meaningfulDifference = 0.1),
                calibrated().copy(comparatorScoreRate = 0.95, meaningfulDifference = 0.1),
                calibrated().copy(maxPriorityAdjustment = -0.01),
                calibrated().copy(maxPriorityAdjustment = 1.0),
                calibrated().copy(observationWindowDays = 0),
                calibrated().copy(policyVersion = " "),
                calibrated().copy(comparatorMethod = ComparatorMethod.DISABLED),
                calibrated().copy(confidenceMethod = ConfidenceMethod.DISABLED),
            )

        invalid.forEach { properties ->
            assertThrows<IllegalArgumentException> { properties.validate() }
        }
    }

    @Test
    fun `incomplete ranking configuration fails during Spring property binding`() {
        ApplicationContextRunner()
            .withUserConfiguration(PracticalEvidenceBindingConfiguration::class.java)
            .withPropertyValues("chess.weakness.practical.ranking-enabled=true")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
            }
    }

    @Test
    fun `inclusive property boundaries adjustment zero and null window are valid`() {
        listOf(0.0, 1.0).forEach { rate ->
            calibrated(comparator = rate, difference = 0.0)
                .copy(
                    sampleFloor = 1,
                    maxPriorityAdjustment = 0.0,
                    observationWindowDays = null,
                ).validate()
        }
    }

    @Test
    fun `present optional values are range validated while ranking is disabled`() {
        val invalidDisabled =
            PracticalEvidenceProperties(
                rankingEnabled = false,
                comparatorScoreRate = 2.0,
                policyVersion = "shadow-v1",
            )

        assertThrows<IllegalArgumentException> { invalidDisabled.validate() }
    }

    private fun policy(
        comparator: Double = 0.5,
        difference: Double = 0.1,
        zScore: Double = 1.0,
    ): WeaknessPriorityPolicy =
        WeaknessPriorityPolicy(
            calibrated(
                comparator = comparator,
                difference = difference,
                zScore = zScore,
            ),
        )

    private fun calibrated(
        comparator: Double = 0.5,
        difference: Double = 0.1,
        zScore: Double = 1.0,
    ): PracticalEvidenceProperties =
        PracticalEvidenceProperties(
            rankingEnabled = true,
            sampleFloor = 5,
            comparatorMethod = ComparatorMethod.FIXED_SCORE_RATE,
            comparatorScoreRate = comparator,
            confidenceMethod = ConfidenceMethod.BERNOULLI_WILSON_SCORE_POINTS_HALF_DRAW_CONSERVATIVE,
            wilsonZScore = zScore,
            meaningfulDifference = difference,
            maxPriorityAdjustment = 0.25,
            observationWindowDays = 365,
            policyVersion = "test-policy-v1",
        )

    private fun summary(
        wins: Int,
        draws: Int,
        losses: Int,
        ineligible: Int = 0,
        excluded: Int = 0,
    ): PracticalEvidenceSummary {
        val eligible = wins + draws + losses
        return PracticalEvidenceSummary(
            scope =
                PracticalEvidenceScope(
                    chessAccountId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    positionId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                    playerColor = "WHITE",
                ),
            candidateGames = eligible + ineligible + excluded,
            eligibleGames = eligible,
            ineligibleGames = ineligible,
            excludedGames = excluded,
            wins = wins,
            draws = draws,
            losses = losses,
            sideCorroborationConflictGames = 0,
            scoreRate = if (eligible == 0) null else (wins + 0.5 * draws) / eligible,
        )
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PracticalEvidenceProperties::class)
    private class PracticalEvidenceBindingConfiguration
}
