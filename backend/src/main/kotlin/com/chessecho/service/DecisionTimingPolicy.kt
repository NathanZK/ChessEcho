package com.chessecho.service

import com.chessecho.domain.DecisionSeverity
import com.chessecho.domain.TimeControl
import org.springframework.stereotype.Component

data class SeverityRangeMs(
    val minMs: Long,
    val maxMs: Long?,
) {
    val isEmpty: Boolean
        get() = maxMs != null && minMs >= maxMs
}

data class TimeControlThresholds(
    val minorMs: Long,
    val significantMs: Long,
    val criticalMs: Long,
)

@Component
class DecisionTimingPolicy {
    fun classify(
        timeControl: TimeControl?,
        decisionTimeMs: Long,
    ): DecisionSeverity {
        if (timeControl == null || decisionTimeMs < 0) {
            return DecisionSeverity.UNKNOWN
        }
        val thresholds = THRESHOLDS[timeControl] ?: return DecisionSeverity.UNKNOWN

        return when {
            decisionTimeMs < thresholds.minorMs -> DecisionSeverity.NO_CONCERN
            decisionTimeMs < thresholds.significantMs -> DecisionSeverity.MINOR
            decisionTimeMs < thresholds.criticalMs -> DecisionSeverity.SIGNIFICANT
            else -> DecisionSeverity.CRITICAL
        }
    }

    fun severityRangeMs(
        timeControl: TimeControl?,
        severity: DecisionSeverity?,
    ): SeverityRangeMs {
        if (timeControl == null || severity == null || severity == DecisionSeverity.UNKNOWN) {
            return SeverityRangeMs(0L, null)
        }
        val thresholds = THRESHOLDS[timeControl] ?: return SeverityRangeMs(0L, null)

        return when (severity) {
            DecisionSeverity.NO_CONCERN -> SeverityRangeMs(0L, thresholds.minorMs)
            DecisionSeverity.MINOR -> SeverityRangeMs(thresholds.minorMs, thresholds.significantMs)
            DecisionSeverity.SIGNIFICANT -> SeverityRangeMs(thresholds.significantMs, thresholds.criticalMs)
            DecisionSeverity.CRITICAL -> SeverityRangeMs(thresholds.criticalMs, null)
            DecisionSeverity.UNKNOWN -> SeverityRangeMs(0L, null)
        }
    }

    fun calculateEffectiveBounds(
        userMinDurationSeconds: Int?,
        severity: DecisionSeverity?,
        timeControl: TimeControl?,
    ): SeverityRangeMs {
        val userMinMs = (userMinDurationSeconds ?: 0).coerceAtLeast(0) * MS_PER_SECOND
        val severityRange = severityRangeMs(timeControl, severity)
        return SeverityRangeMs(
            minMs = maxOf(userMinMs, severityRange.minMs),
            maxMs = severityRange.maxMs,
        )
    }

    companion object {
        const val MS_PER_SECOND = 1_000L

        const val BULLET_MINOR_MS = 5_000L
        const val BULLET_SIGNIFICANT_MS = 10_000L
        const val BULLET_CRITICAL_MS = 20_000L

        const val BLITZ_MINOR_MS = 15_000L
        const val BLITZ_SIGNIFICANT_MS = 30_000L
        const val BLITZ_CRITICAL_MS = 60_000L

        const val RAPID_MINOR_MS = 30_000L
        const val RAPID_SIGNIFICANT_MS = 60_000L
        const val RAPID_CRITICAL_MS = 120_000L

        const val CLASSICAL_MINOR_MS = 60_000L
        const val CLASSICAL_SIGNIFICANT_MS = 180_000L
        const val CLASSICAL_CRITICAL_MS = 300_000L

        private val THRESHOLDS =
            mapOf(
                TimeControl.BULLET to
                    TimeControlThresholds(
                        minorMs = BULLET_MINOR_MS,
                        significantMs = BULLET_SIGNIFICANT_MS,
                        criticalMs = BULLET_CRITICAL_MS,
                    ),
                TimeControl.BLITZ to
                    TimeControlThresholds(
                        minorMs = BLITZ_MINOR_MS,
                        significantMs = BLITZ_SIGNIFICANT_MS,
                        criticalMs = BLITZ_CRITICAL_MS,
                    ),
                TimeControl.RAPID to
                    TimeControlThresholds(
                        minorMs = RAPID_MINOR_MS,
                        significantMs = RAPID_SIGNIFICANT_MS,
                        criticalMs = RAPID_CRITICAL_MS,
                    ),
                TimeControl.CLASSICAL to
                    TimeControlThresholds(
                        minorMs = CLASSICAL_MINOR_MS,
                        significantMs = CLASSICAL_SIGNIFICANT_MS,
                        criticalMs = CLASSICAL_CRITICAL_MS,
                    ),
            )
    }
}
