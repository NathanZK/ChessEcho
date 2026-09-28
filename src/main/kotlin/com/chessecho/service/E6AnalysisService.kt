package com.chessecho.service

import java.util.UUID

data class E6AnalysisInput(
    val referencePositions: Set<String>,
    val players: Map<String, EvaluationEvidencePlayer>,
    val rows: List<E6AnalysisRow>,
    val analysisVersion: String,
)

data class E6AnalysisRow(
    val playerId: UUID,
    val gameId: UUID,
    val occurrenceId: UUID,
    val positionIdentity: String,
    val loss: Double,
    val verifiedOccurrence: Boolean,
    val canonicalLocation: E6CanonicalLocation,
)

data class E6CanonicalLocation(
    val sourceRunId: UUID,
    val qualifyingOrdinal: Int,
    val preMovePly: Int,
    val movePlayed: String,
)

data class E6Coverage(
    val weaknessPositionCount: Int,
    val sharedPositionCount: Int,
    val coverage: Double?,
)

data class E6Summary(
    val mean: Double?,
    val median: Double?,
    val minimum: Double?,
    val maximum: Double?,
)

data class E6LocationGroup(
    val numerator: Int,
    val denominator: Int,
)

data class E6Locations(
    val occurrenceCount: Int,
    val distinctPositionCount: Int,
    val uniqueGameCount: Int,
    val population: String,
    val unit: String,
    val denominator: Int,
    val groupingKey: String,
    val groupsByPreMovePly: Map<Int, E6LocationGroup>,
)

data class E6PlayerAnalysis(
    val coverageByThreshold: Map<Double, E6Coverage>,
    val weaknessPositions: ThresholdValues<Set<String>>,
    val sharedPositions: ThresholdValues<Set<String>>,
) {
    fun at(threshold: Double): E6Coverage = coverageByThreshold.getValue(threshold)
}

data class ThresholdValues<T>(
    private val values: Map<Double, T>,
) {
    fun at(threshold: Double): T = values.getValue(threshold)
}

data class E6AnalysisResult(
    val analysisVersion: String,
    val byPlayer: Map<String, E6PlayerAnalysis>,
    val pooled: ThresholdValues<E6Coverage>,
    val summaries: ThresholdValues<E6Summary>,
    val locations: Map<String, ThresholdValues<E6Locations>>,
)

class E6AnalysisIntegrityException(message: String) : RuntimeException(message)

class E6AnalysisService {
    fun analyze(input: E6AnalysisInput): E6AnalysisResult {
        if (input.analysisVersion.isBlank()) {
            throw E6AnalysisIntegrityException("analysis version must not be blank")
        }
        if (input.players.isEmpty() ||
            input.players.values.map { it.id }.toSet().size != input.players.size ||
            input.players.any { (name, player) -> name != player.identity || name.isBlank() }
        ) {
            throw E6AnalysisIntegrityException("declared E6 players are invalid")
        }
        val declaredIds = input.players.values.map { it.id }.toSet()
        if (input.rows.any {
                !it.verifiedOccurrence || it.playerId !in declaredIds ||
                    it.positionIdentity.isBlank() || !it.loss.isFinite() || it.loss < 0 ||
                    it.canonicalLocation.qualifyingOrdinal < 1 || it.canonicalLocation.preMovePly < 1
            }
        ) {
            throw E6AnalysisIntegrityException("all E6 rows must have a verified occurrence")
        }

        val thresholds = EvaluationEvidenceSnapshotService.APPROVED_THRESHOLDS.sorted()
        val byPlayer =
            input.players.mapValues { (_, player) ->
                val playerRows = input.rows.filter { it.playerId == player.id }
                val coverage =
                    thresholds.associateWith { threshold ->
                        val qualified = playerRows.filter { it.loss >= threshold }
                        val weakness = qualified.map { it.positionIdentity }.toSet()
                        val shared = weakness intersect input.referencePositions
                        val coverage = if (weakness.isEmpty()) null else shared.size.toDouble() / weakness.size
                        E6Coverage(weakness.size, shared.size, coverage)
                    }
                E6PlayerAnalysis(
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
                                    .toSet() intersect input.referencePositions
                            },
                        ),
                )
            }

        val pooled =
            ThresholdValues(
                thresholds.associateWith { threshold ->
                    val values = byPlayer.values.map { it.at(threshold) }
                    val denominator = values.sumOf { it.weaknessPositionCount }
                    val numerator = values.sumOf { it.sharedPositionCount }
                    val coverage = if (denominator == 0) null else numerator.toDouble() / denominator
                    E6Coverage(denominator, numerator, coverage)
                },
            )
        val summaries =
            ThresholdValues(
                thresholds.associateWith { threshold ->
                    val defined = byPlayer.values.map { it.at(threshold).coverage }.filterNotNull().sorted()
                    val median =
                        defined.takeIf { it.isNotEmpty() }?.let { values ->
                            if (values.size % 2 == 1) {
                                values[values.size / 2]
                            } else {
                                (values[values.size / 2 - 1] + values[values.size / 2]) / 2
                            }
                        }
                    E6Summary(
                        mean = defined.takeIf { it.isNotEmpty() }?.average(),
                        median = median,
                        minimum = defined.minOrNull(),
                        maximum = defined.maxOrNull(),
                    )
                },
            )
        val locations =
            input.players.mapValues { (_, player) ->
                ThresholdValues(
                    thresholds.associateWith { threshold ->
                        val qualified = input.rows.filter { it.playerId == player.id && it.loss >= threshold }
                        val groups = qualified.groupBy { it.canonicalLocation.preMovePly }
                        E6Locations(
                            occurrenceCount = qualified.size,
                            distinctPositionCount = qualified.map { it.positionIdentity }.toSet().size,
                            uniqueGameCount =
                                qualified.map { it.canonicalLocation.sourceRunId to it.canonicalLocation.qualifyingOrdinal }.toSet().size,
                            population = "all-threshold-qualified-occurrences",
                            unit = "canonical-source-occurrence",
                            denominator = qualified.size,
                            groupingKey = "preMovePly",
                            groupsByPreMovePly = groups.mapValues { E6LocationGroup(it.value.size, qualified.size) },
                        )
                    },
                )
            }
        return E6AnalysisResult(input.analysisVersion, byPlayer, pooled, summaries, locations)
    }
}
