package com.chessecho.service.clustering

import com.chessecho.domain.CommunityPatternClassification
import com.chessecho.domain.DecisionSeverity
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.PositionWeaknessSeverity
import com.chessecho.domain.TimeControl
import com.chessecho.domain.clustering.PositionCluster
import com.chessecho.service.DecisionTimingPolicy
import com.chessecho.service.EvaluationLossPolicy
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
    val evalLossDistribution: Map<PositionWeaknessSeverity, Int>,
    val evalLossPercentages: Map<PositionWeaknessSeverity, Double>,
    val timingProfile: Map<DecisionSeverity, Int>,
    val formatBreakdown: Map<String, Int>,
)

@Service
class CommunityWeaknessService(
    private val decisionTimingPolicy: DecisionTimingPolicy,
    @Value("\${chessecho.weakness.community.min-size:10}")
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
        val distribution: Map<PositionWeaknessSeverity, Int>,
        val percentages: Map<PositionWeaknessSeverity, Double>,
    )

    private fun computeLossMetrics(
        memberOccurrences: List<PositionOccurrence>,
        occurrenceEvaluations: Map<UUID, Double?>,
        totalOccurrences: Int,
    ): LossMetrics {
        val distribution = PositionWeaknessSeverity.entries.associateWith { 0 }.toMutableMap()
        var mistakeCount = 0
        var criticalCount = 0

        for (occ in memberOccurrences) {
            val evalLoss = occurrenceEvaluations[occ.id]
            val severity = EvaluationLossPolicy.classifySeverity(evalLoss)
            distribution[severity] = (distribution[severity] ?: 0) + 1
            if (severity != PositionWeaknessSeverity.NO_CONCERN) {
                mistakeCount++
            }
            if (severity == PositionWeaknessSeverity.CRITICAL) {
                criticalCount++
            }
        }

        val percentages =
            distribution.mapValues { (_, count) ->
                round((count.toDouble() / totalOccurrences) * 10000.0) / 100.0
            }

        val mistakeFrequency = round((mistakeCount.toDouble() / totalOccurrences) * 10000.0) / 100.0
        val blunderRate = round((criticalCount.toDouble() / totalOccurrences) * 10000.0) / 100.0

        return LossMetrics(
            mistakeCount = mistakeCount,
            mistakeFrequency = mistakeFrequency,
            blunderRate = blunderRate,
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
