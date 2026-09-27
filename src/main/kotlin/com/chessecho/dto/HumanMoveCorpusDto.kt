package com.chessecho.dto

import com.chessecho.domain.HumanMoveCorpusRunStatus
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Starts one Issue #423 corpus run. Bounds and traversal parameters mirror
 * [HumanMoveBfsRequest]; [sourceRevision] is operator-supplied and recorded as
 * self-reported provenance.
 */
data class HumanMoveCorpusRunRequest(
    val ratingBand: String,
    val seedPlayers: List<String>,
    val excludedPlayers: List<String> = emptyList(),
    val maxQualifyingGames: Int? = null,
    val maxGamesPerPlayer: Int = 100,
    val maxPlayers: Int? = null,
    val maxDepth: Int? = null,
    val batchSize: Int = 5000,
    val sourceRevision: String,
)

data class HumanMoveCorpusRunResponse(
    val runId: UUID,
    val ratingBand: String,
    val status: HumanMoveCorpusRunStatus,
    val seedPlayers: List<String>,
    val excludedPlayers: List<String>,
    val maxQualifyingGames: Int?,
    val maxGamesPerPlayer: Int,
    val maxPlayers: Int?,
    val maxDepth: Int?,
    val batchSize: Int,
    val algorithmVersion: String,
    val sourceRevision: String,
    val sourceRevisionProvenance: String,
    val requestSha256: String,
    val committedFrontier: Int,
    val rejectedGameCount: Int,
    val archiveFetchFailureCount: Int,
    val stopReason: String?,
    val failureDetails: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val finishedAt: Instant?,
)

/** Aggregates the run's qualifying ordinals `1..qualifyingGames`; never persisted. */
data class HumanMoveCorpusCheckpointRequest(
    @JsonProperty(required = true)
    val qualifyingGames: Int,
    val minObservations: Int = 5,
)

data class HumanMoveCorpusCheckpointRow(
    val positionId: UUID,
    val positionHash: String,
    val movePlayed: String,
    val observationCount: Int,
)

/**
 * Checkpoint evidence. Determinism covers [rows], the counts, and
 * [distributionSha256]; [calculatedAt], [committedFrontier], and [runStatus]
 * are volatile metadata.
 */
data class HumanMoveCorpusCheckpointResponse(
    val runId: UUID,
    val ratingBand: String,
    val runStatus: HumanMoveCorpusRunStatus,
    val requestedN: Int,
    val committedFrontier: Int,
    val minObservations: Int,
    val positionsEvaluated: Int,
    val positionsRemoved: Int,
    val positionsRetained: Int,
    val rowsRetained: Int,
    val observationsRetained: Int,
    val rows: List<HumanMoveCorpusCheckpointRow>,
    val distributionSha256: String,
    val calculatedAt: Instant,
    val calculationVersion: String,
)
