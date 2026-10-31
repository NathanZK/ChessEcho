package com.chessecho.service

import java.util.UUID

data class ReferenceCoverageAnalysisInput(
    val referencePositions: Set<String>,
    val players: Map<String, EvaluationEvidencePlayer>,
    val rows: List<ReferenceCoverageAnalysisRow>,
    val analysisVersion: String,
)

data class ReferenceCoverageAnalysisRow(
    val playerId: UUID,
    val gameId: UUID,
    val occurrenceId: UUID,
    val positionIdentity: String,
    val loss: Double,
    val verifiedOccurrence: Boolean,
    val canonicalLocation: CanonicalOccurrenceLocation,
)

data class CanonicalOccurrenceLocation(
    val sourceRunId: UUID,
    val qualifyingOrdinal: Int,
    val preMovePly: Int,
    val movePlayed: String,
)

data class PositionCoverage(
    val weaknessPositionCount: Int,
    val sharedPositionCount: Int,
    val coverage: Double?,
)

data class CoverageSummary(
    val mean: Double?,
    val median: Double?,
    val minimum: Double?,
    val maximum: Double?,
)

data class LocationGroupCount(
    val numerator: Int,
    val denominator: Int,
)

data class OccurrenceLocationSummary(
    val occurrenceCount: Int,
    val distinctPositionCount: Int,
    val uniqueGameCount: Int,
    val population: String,
    val unit: String,
    val denominator: Int,
    val groupingKey: String,
    val groupsByPreMovePly: Map<Int, LocationGroupCount>,
)

data class PlayerCoverageAnalysis(
    val coverageByThreshold: Map<Double, PositionCoverage>,
    val weaknessPositions: ThresholdValues<Set<String>>,
    val sharedPositions: ThresholdValues<Set<String>>,
) {
    fun at(threshold: Double): PositionCoverage = coverageByThreshold.getValue(threshold)
}

data class ThresholdValues<T>(
    private val values: Map<Double, T>,
) {
    fun at(threshold: Double): T = values.getValue(threshold)
}

data class ReferenceCoverageAnalysisResult(
    val analysisVersion: String,
    val byPlayer: Map<String, PlayerCoverageAnalysis>,
    val pooled: ThresholdValues<PositionCoverage>,
    val summaries: ThresholdValues<CoverageSummary>,
    val locations: Map<String, ThresholdValues<OccurrenceLocationSummary>>,
)

class ReferenceCoverageAnalysisIntegrityException(
    message: String,
) : RuntimeException(message)

class ReferenceCoverageAnalysisService {
    fun analyze(input: ReferenceCoverageAnalysisInput): ReferenceCoverageAnalysisResult {
        if (input.analysisVersion.isBlank()) {
            throw ReferenceCoverageAnalysisIntegrityException("analysis version must not be blank")
        }
        validatePlayerMap(input.players)
        val declaredIds =
            input.players.values
                .map { it.id }
                .toSet()
        validateRows(input.rows, declaredIds)

        val thresholds = EvaluationEvidenceSnapshotService.APPROVED_THRESHOLDS.sorted()
        val byPlayer = computePlayerCoverageMap(thresholds, input.players, input.rows, input.referencePositions)
        val pooled = computePooledCoverage(thresholds, byPlayer)
        val summaries = computeCoverageSummaries(thresholds, byPlayer)
        val locations = computeLocationSummaries(thresholds, input.players, input.rows)

        return ReferenceCoverageAnalysisResult(input.analysisVersion, byPlayer, pooled, summaries, locations)
    }

    private fun validatePlayerMap(players: Map<String, EvaluationEvidencePlayer>) {
        if (players.isEmpty() ||
            hasDuplicatePlayerIds(players) ||
            hasMismatchedOrBlankNames(players)
        ) {
            throw ReferenceCoverageAnalysisIntegrityException("declared players are invalid")
        }
    }

    private fun hasDuplicatePlayerIds(players: Map<String, EvaluationEvidencePlayer>): Boolean =
        players.values
            .map { it.id }
            .toSet()
            .size != players.size

    private fun hasMismatchedOrBlankNames(players: Map<String, EvaluationEvidencePlayer>): Boolean =
        players.any { (name, player) -> name != player.identity || name.isBlank() }

    private fun validateRows(
        rows: List<ReferenceCoverageAnalysisRow>,
        declaredIds: Set<UUID>,
    ) {
        if (rows.any { !isValidRow(it, declaredIds) }) {
            throw ReferenceCoverageAnalysisIntegrityException("all rows must have a verified occurrence")
        }
    }

    private fun isValidRow(
        row: ReferenceCoverageAnalysisRow,
        declaredIds: Set<UUID>,
    ): Boolean =
        row.verifiedOccurrence &&
            row.playerId in declaredIds &&
            row.positionIdentity.isNotBlank() &&
            isValidLossAndLocation(row)

    private fun isValidLossAndLocation(row: ReferenceCoverageAnalysisRow): Boolean =
        row.loss.isFinite() &&
            row.loss >= 0 &&
            row.canonicalLocation.qualifyingOrdinal >= 1 &&
            row.canonicalLocation.preMovePly >= 1

    private fun computePlayerCoverageMap(
        thresholds: List<Double>,
        players: Map<String, EvaluationEvidencePlayer>,
        rows: List<ReferenceCoverageAnalysisRow>,
        referencePositions: Set<String>,
    ): Map<String, PlayerCoverageAnalysis> =
        players.mapValues { (_, player) ->
            val playerRows = rows.filter { it.playerId == player.id }
            val coverage =
                thresholds.associateWith { threshold ->
                    val qualified = playerRows.filter { it.loss >= threshold }
                    val weakness = qualified.map { it.positionIdentity }.toSet()
                    val shared = weakness intersect referencePositions
                    val coverageRatio = if (weakness.isEmpty()) null else shared.size.toDouble() / weakness.size
                    PositionCoverage(weakness.size, shared.size, coverageRatio)
                }
            PlayerCoverageAnalysis(
                coverageByThreshold = coverage,
                weaknessPositions =
                    ThresholdValues(
                        thresholds.associateWith { threshold ->
                            playerRows.filter { it.loss >= threshold }.map { it.positionIdentity }.toSet()
                        },
                    ),
                sharedPositions =
                    ThresholdValues(
                        thresholds.associateWith { threshold ->
                            playerRows
                                .filter { it.loss >= threshold }
                                .map { it.positionIdentity }
                                .toSet() intersect referencePositions
                        },
                    ),
            )
        }

    private fun computePooledCoverage(
        thresholds: List<Double>,
        byPlayer: Map<String, PlayerCoverageAnalysis>,
    ): ThresholdValues<PositionCoverage> =
        ThresholdValues(
            thresholds.associateWith { threshold ->
                val values = byPlayer.values.map { it.at(threshold) }
                val denominator = values.sumOf { it.weaknessPositionCount }
                val numerator = values.sumOf { it.sharedPositionCount }
                val coverage = if (denominator == 0) null else numerator.toDouble() / denominator
                PositionCoverage(denominator, numerator, coverage)
            },
        )

    private fun computeCoverageSummaries(
        thresholds: List<Double>,
        byPlayer: Map<String, PlayerCoverageAnalysis>,
    ): ThresholdValues<CoverageSummary> =
        ThresholdValues(
            thresholds.associateWith { threshold ->
                val defined =
                    byPlayer.values
                        .map { it.at(threshold).coverage }
                        .filterNotNull()
                        .sorted()
                val median = calculateMedian(defined)
                CoverageSummary(
                    mean = defined.takeIf { it.isNotEmpty() }?.average(),
                    median = median,
                    minimum = defined.minOrNull(),
                    maximum = defined.maxOrNull(),
                )
            },
        )

    private fun calculateMedian(values: List<Double>): Double? =
        values.takeIf { it.isNotEmpty() }?.let {
            if (it.size % 2 == 1) {
                it[it.size / 2]
            } else {
                (it[it.size / 2 - 1] + it[it.size / 2]) / 2
            }
        }

    private fun computeLocationSummaries(
        thresholds: List<Double>,
        players: Map<String, EvaluationEvidencePlayer>,
        rows: List<ReferenceCoverageAnalysisRow>,
    ): Map<String, ThresholdValues<OccurrenceLocationSummary>> =
        players.mapValues { (_, player) ->
            ThresholdValues(
                thresholds.associateWith { threshold ->
                    val qualified = rows.filter { it.playerId == player.id && it.loss >= threshold }
                    val locations =
                        qualified.distinctBy {
                            Triple(
                                it.canonicalLocation.sourceRunId,
                                it.canonicalLocation.qualifyingOrdinal,
                                it.canonicalLocation.preMovePly,
                            )
                        }
                    val groups = locations.groupBy { it.canonicalLocation.preMovePly }
                    OccurrenceLocationSummary(
                        occurrenceCount = locations.size,
                        distinctPositionCount = locations.map { it.positionIdentity }.toSet().size,
                        uniqueGameCount =
                            locations
                                .map { it.canonicalLocation.sourceRunId to it.canonicalLocation.qualifyingOrdinal }
                                .toSet()
                                .size,
                        population = "all-threshold-qualified-occurrences",
                        unit = "canonical-source-occurrence",
                        denominator = locations.size,
                        groupingKey = "preMovePly",
                        groupsByPreMovePly = groups.mapValues { LocationGroupCount(it.value.size, locations.size) },
                    )
                },
            )
        }
}
