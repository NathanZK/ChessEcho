package com.chessprobe.service.clustering

import com.chessprobe.domain.CommunityPatternClassification
import com.chessprobe.domain.DecisionSeverity
import com.chessprobe.domain.MistakeSeverity
import com.chessprobe.domain.PositionOccurrence
import com.chessprobe.domain.TimeControl
import com.chessprobe.domain.clustering.PositionCluster
import com.chessprobe.service.DecisionTimingPolicy
import com.chessprobe.service.EvaluationLossPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.math.round

data class CommunityWeaknessProfile(
    val clusterId: UUID,
    val memberPositionCount: Int,
    val totalOccurrences: Int,
    val distinctGamesCount: Int,
    val classification: CommunityPatternClassification,
    val mistakeCount: Int,
    val mistakeFrequency: Double,
    val blunderRate: Double,
    val evaluatedOccurrences: Int = totalOccurrences,
    val unevaluatedOccurrences: Int = 0,
    val evalLossDistribution: Map<MistakeSeverity, Int> = emptyMap(),
    val evalLossPercentages: Map<MistakeSeverity, Double> = emptyMap(),
    val timingProfile: Map<DecisionSeverity, Int> = emptyMap(),
    val formatBreakdown: Map<String, Int> = emptyMap(),
)

@Service
class CommunityWeaknessService(
    private val decisionTimingPolicy: DecisionTimingPolicy,
    @Value("\${chessprobe.weakness.community.min-size:10}")
    private val minCommunitySize: Int = 10,
) {
    fun profileCommunity(
        cluster: PositionCluster,
        memberOccurrences: List<PositionOccurrence>,
        occurrenceEvaluations: Map<UUID, Double?>,
    ): CommunityWeaknessProfile? {
        if (cluster.size < minCommunitySize || memberOccurrences.isEmpty()) {
            return null
        }

        val totalOccurrences = memberOccurrences.size
        val distinctGamesCount = memberOccurrences.map { it.game.id }.distinct().size
        val classification =
            if (distinctGamesCount >= 2) {
                CommunityPatternClassification.RECURRING_WEAKNESS
            } else {
                CommunityPatternClassification.SINGLE_GAME_PATTERN
            }

        val lossMetrics = computeLossMetrics(memberOccurrences, occurrenceEvaluations, totalOccurrences)
        val timingProfile = computeTimingProfile(memberOccurrences)
        val formatBreakdown = computeFormatBreakdown(memberOccurrences)

        return CommunityWeaknessProfile(
            clusterId = cluster.id,
            memberPositionCount = cluster.size,
            totalOccurrences = totalOccurrences,
            distinctGamesCount = distinctGamesCount,
            classification = classification,
            mistakeCount = lossMetrics.mistakeCount,
            mistakeFrequency = lossMetrics.mistakeFrequency,
            blunderRate = lossMetrics.blunderRate,
            evaluatedOccurrences = lossMetrics.evaluatedOccurrences,
            unevaluatedOccurrences = lossMetrics.unevaluatedOccurrences,
            evalLossDistribution = lossMetrics.distribution,
            evalLossPercentages = lossMetrics.percentages,
            timingProfile = timingProfile,
            formatBreakdown = formatBreakdown,
        )
    }

    private data class LossMetrics(
        val mistakeCount: Int,
        val mistakeFrequency: Double,
        val blunderRate: Double,
        val evaluatedOccurrences: Int,
        val unevaluatedOccurrences: Int,
        val distribution: Map<MistakeSeverity, Int>,
        val percentages: Map<MistakeSeverity, Double>,
    )

    private fun computeLossMetrics(
        memberOccurrences: List<PositionOccurrence>,
        occurrenceEvaluations: Map<UUID, Double?>,
        totalOccurrences: Int,
    ): LossMetrics {
        val severities =
            memberOccurrences.map { occ ->
                EvaluationLossPolicy.classifyMistakeSeverity(occurrenceEvaluations[occ.id])
            }
        val distribution = MistakeSeverity.entries.associateWith { s -> severities.count { it == s } }
        val unevaluatedOccurrences = distribution[MistakeSeverity.UNEVALUATED] ?: 0
        val evaluatedOccurrences = totalOccurrences - unevaluatedOccurrences
        val severeCount = distribution[MistakeSeverity.SEVERE] ?: 0
        val mistakeCount = evaluatedOccurrences - (distribution[MistakeSeverity.NO_CONCERN] ?: 0)

        val percentages =
            if (totalOccurrences > 0) {
                distribution.mapValues { (_, count) ->
                    round((count.toDouble() / totalOccurrences) * 10000.0) / 100.0
                }
            } else {
                distribution.mapValues { 0.0 }
            }

        val mistakeFrequency =
            if (evaluatedOccurrences > 0) {
                round((mistakeCount.toDouble() / evaluatedOccurrences) * 10000.0) / 100.0
            } else {
                0.0
            }

        val blunderRate =
            if (evaluatedOccurrences > 0) {
                round((severeCount.toDouble() / evaluatedOccurrences) * 10000.0) / 100.0
            } else {
                0.0
            }

        return LossMetrics(
            mistakeCount = mistakeCount,
            mistakeFrequency = mistakeFrequency,
            blunderRate = blunderRate,
            evaluatedOccurrences = evaluatedOccurrences,
            unevaluatedOccurrences = unevaluatedOccurrences,
            distribution = distribution,
            percentages = percentages,
        )
    }

    private fun computeTimingProfile(memberOccurrences: List<PositionOccurrence>): Map<DecisionSeverity, Int> {
        val timingProfile = DecisionSeverity.entries.associateWith { 0 }.toMutableMap()
        for (occ in memberOccurrences) {
            val decisionTime = occ.decisionTimeMs
            val timeControl = TimeControl.fromExternal(occ.game.timeControl)
            val severity =
                if (decisionTime == null) {
                    DecisionSeverity.UNKNOWN
                } else {
                    decisionTimingPolicy.classify(timeControl, decisionTime)
                }
            timingProfile[severity] = (timingProfile[severity] ?: 0) + 1
        }
        return timingProfile
    }

    private fun computeFormatBreakdown(memberOccurrences: List<PositionOccurrence>): Map<String, Int> {
        val formatBreakdown = mutableMapOf<String, Int>()
        for (occ in memberOccurrences) {
            val key = TimeControl.fromExternal(occ.game.timeControl)?.name ?: "UNKNOWN"
            formatBreakdown[key] = (formatBreakdown[key] ?: 0) + 1
        }
        return formatBreakdown
    }
}
