package com.chessecho.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class PracticalEvidenceConfigurationTest {
    @Test
    fun `normal application configuration enables the complete versioned practical policy`() {
        contextWithApplicationYaml().run { context ->
            assertThat(context).hasNotFailed()
            val properties = context.getBean(PracticalEvidenceProperties::class.java)

            assertThat(properties.rankingEnabled).isTrue()
            assertThat(properties.sampleFloor).isEqualTo(5)
            assertThat(properties.comparatorMethod).isEqualTo(ComparatorMethod.FIXED_SCORE_RATE)
            assertThat(properties.comparatorScoreRate).isEqualTo(0.5)
            assertThat(properties.confidenceMethod)
                .isEqualTo(ConfidenceMethod.BERNOULLI_WILSON_SCORE_POINTS_HALF_DRAW_CONSERVATIVE)
            assertThat(properties.wilsonZScore).isEqualTo(1.0)
            assertThat(properties.meaningfulDifference).isEqualTo(0.1)
            assertThat(properties.maxPriorityAdjustment).isEqualTo(0.25)
            assertThat(properties.policyVersion).isEqualTo("practical-score-rate-wilson-v1")
            assertThat(properties.observationWindowDays).isNull()
        }
    }

    @Test
    fun `environment overrides rollback and calibration while preserving other defaults`() {
        contextWithApplicationYaml(
            "chess.weakness.practical.ranking-enabled=false",
            "chess.weakness.practical.max-priority-adjustment=0.4",
            "chess.weakness.practical.observation-window-days=120",
        ).run { context ->
            assertThat(context).hasNotFailed()
            val properties = context.getBean(PracticalEvidenceProperties::class.java)

            assertThat(properties.rankingEnabled).isFalse()
            assertThat(properties.maxPriorityAdjustment).isEqualTo(0.4)
            assertThat(properties.observationWindowDays).isEqualTo(120)
            assertThat(properties.sampleFloor).isEqualTo(5)
            assertThat(properties.comparatorMethod).isEqualTo(ComparatorMethod.FIXED_SCORE_RATE)
        }
    }

    @Test
    fun `blank observation window keeps all-history behavior`() {
        contextWithApplicationYaml("chess.weakness.practical.observation-window-days=").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(PracticalEvidenceProperties::class.java).observationWindowDays).isNull()
        }
    }

    @Test
    fun `invalid enabled configuration fails startup`() {
        contextWithApplicationYaml("chess.weakness.practical.sample-floor=0").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `invalid provided calibration fails startup when practical ranking is disabled`() {
        contextWithApplicationYaml(
            "chess.weakness.practical.ranking-enabled=false",
            "chess.weakness.practical.sample-floor=0",
        ).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `disabled policy accepts omitted calibration fields`() {
        ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration::class.java)
            .withPropertyValues("chess.weakness.practical.ranking-enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                val properties = context.getBean(PracticalEvidenceProperties::class.java)
                assertThat(properties.rankingEnabled).isFalse()
                assertThat(properties.sampleFloor).isNull()
                assertThat(properties.comparatorMethod).isEqualTo(ComparatorMethod.DISABLED)
                assertThat(properties.confidenceMethod).isEqualTo(ConfidenceMethod.DISABLED)
            }
    }

    private fun contextWithApplicationYaml(vararg overrides: String): ApplicationContextRunner =
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BindingConfiguration::class.java)
            .withPropertyValues(*overrides)

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PracticalEvidenceProperties::class)
    private class BindingConfiguration
}
