package com.chessecho.dto

data class HumanMoveBfsRequest(
    val ratingBand: String,
    val seedPlayers: List<String>,
    val maxQualifyingGames: Int = 2000,
    val maxGamesPerPlayer: Int = 100,
    val maxPlayers: Int = 100,
    val maxDepth: Int = 3,
    val batchSize: Int = 5000,
)

data class HumanMoveBfsResponse(
    val ratingBand: String,
    val seedPlayers: Int,
    val playersVisited: Int,
    val maxDepthReached: Int,
    val maxGamesPerPlayer: Int,
    val gamesInspected: Int,
    val rapidGames: Int,
    val qualifyingGames: Int,
    val uniqueGamesProcessed: Int,
    val uniquePositions: Int,
    val totalObservations: Int,
    val stopReason: String,
)

/**
 * Explicit finalization of the accumulated human-move distribution corpus for a
 * rating band. Applies [minObservations] globally against SUM(observation_count)
 * per position and deletes every distribution row belonging to positions that
 * do not meet the threshold. Retained positions keep all of their move rows and
 * counts intact.
 */
data class HumanMoveFinalizeRequest(
    val ratingBand: String,
    val minObservations: Int,
)

data class HumanMoveFinalizeResponse(
    val ratingBand: String,
    val minObservations: Int,
    val positionsEvaluated: Int,
    val positionsRemoved: Int,
    val rowsRemoved: Int,
    val positionsRetained: Int,
)
