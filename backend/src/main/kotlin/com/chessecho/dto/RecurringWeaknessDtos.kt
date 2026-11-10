package com.chessecho.dto

import com.chessecho.domain.DecisionSeverity
import com.chessecho.domain.MistakeSeverity
import com.chessecho.domain.clustering.ClusterType
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

data class RecurringDiscoveryRequest(
    val accountId: UUID? = null,
    val username: String? = null,
    val clusterType: ClusterType? = null,
    val timeControls: List<String> = listOf("RAPID", "BLITZ", "BULLET"),
    val minMistakeSeverity: MistakeSeverity? = null,
    val minEvalLoss: Double? = null,
    val fromDate: String? = null,
    val toDate: String? = null,
) {
    fun validate() {
        if (minEvalLoss != null) {
            require(minEvalLoss >= 0.0) { "minEvalLoss must be non-negative: $minEvalLoss" }
        }
        val invalidControls = timeControls.filter { it.uppercase() !in ALLOWED_TIME_CONTROLS }
        require(invalidControls.isEmpty()) {
            "Invalid time controls: $invalidControls. Allowed: $ALLOWED_TIME_CONTROLS"
        }
        if (!fromDate.isNullOrBlank() && !toDate.isNullOrBlank()) {
            val from = parseDateBoundary(fromDate, isEnd = false)
            val to = parseDateBoundary(toDate, isEnd = true)
            if (from != null && to != null) {
                require(!from.isAfter(to)) { "fromDate must not be after toDate: $fromDate > $toDate" }
            }
        }
    }

    companion object {
        val ALLOWED_TIME_CONTROLS = setOf("RAPID", "BLITZ", "BULLET", "CLASSICAL")

        fun parseDateBoundary(
            dateStr: String,
            isEnd: Boolean,
        ): Instant? =
            runCatching {
                val trimmed = dateStr.trim()
                when {
                    trimmed.length == 7 -> {
                        val ym = YearMonth.parse(trimmed)
                        if (isEnd) {
                            ym
                                .atEndOfMonth()
                                .atTime(23, 59, 59)
                                .atZone(ZoneOffset.UTC)
                                .toInstant()
                        } else {
                            ym
                                .atDay(1)
                                .atStartOfDay()
                                .toInstant(ZoneOffset.UTC)
                        }
                    }
                    trimmed.length == 10 -> {
                        val ld = LocalDate.parse(trimmed)
                        if (isEnd) {
                            ld
                                .atTime(23, 59, 59)
                                .atZone(ZoneOffset.UTC)
                                .toInstant()
                        } else {
                            ld
                                .atStartOfDay()
                                .toInstant(ZoneOffset.UTC)
                        }
                    }
                    else -> Instant.parse(trimmed)
                }
            }.getOrNull()
    }
}

data class RecurringDiscoveryJobResponse(
    val jobId: UUID,
    val accountId: UUID,
    val status: String,
    val progressPercent: Int,
    val stage: String,
    val errorMessage: String? = null,
    val warningMessage: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class RecurringWeaknessResponse(
    val clusterId: UUID,
    val clusterType: ClusterType,
    val recommendationPriority: Double,
    val representativeFen: String,
    val memberPositionCount: Int,
    val totalOccurrences: Int,
    val distinctGamesCount: Int,
    val mistakeCount: Int,
    val mistakeFrequency: Double,
    val blunderRate: Double,
    val evaluatedOccurrences: Int,
    val unevaluatedOccurrences: Int,
    val evalLossDistribution: Map<String, Int>,
    val evalLossPercentages: Map<String, Double>,
    val timingProfile: Map<String, Int>,
    val formatBreakdown: Map<String, Int>,
)

data class ClusterMemberPositionDto(
    val positionId: UUID,
    val fen: String,
    val playerColor: String,
    val movePlayed: String,
    val targetMove: String? = null,
    val acceptableMoves: List<AcceptableMove>? = null,
    val evalLoss: Double? = null,
    val mistakeSeverity: MistakeSeverity? = null,
    val decisionTimeMs: Long? = null,
    val decisionSeverity: DecisionSeverity = DecisionSeverity.UNKNOWN,
    val timeControl: String? = null,
    val gameId: UUID,
    val platformGameId: String,
    val plyNumber: Int,
    val moveNumber: Int = (plyNumber + 1) / 2,
)

data class ClusterMemberPositionsResponse(
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
    val positions: List<ClusterMemberPositionDto>,
)
