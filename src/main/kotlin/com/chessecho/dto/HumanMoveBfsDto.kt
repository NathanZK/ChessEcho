package com.chessecho.dto

data class HumanMoveBfsRequest(
    val ratingBand: String,
    val seedPlayers: List<String>,
    val excludedPlayers: List<String> = emptyList(),
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

data class HumanMovePopulationDiscoveryRequest(
    val ratingBand: String,
    val seedPlayers: List<String>,
    val excludedPlayers: List<String> = emptyList(),
    val targetQualifyingPlayers: Int = 100,
    val maxPlayers: Int = 100,
    val maxGamesPerPlayer: Int = 100,
    val maxDepth: Int = 3,
)

data class HumanMovePopulationDiscoveryResponse(
    val ratingBand: String,
    val seedPlayers: List<String>,
    val excludedPlayers: List<String>,
    val targetQualifyingPlayers: Int,
    val qualifyingPlayers: List<String>,
    val qualifyingPlayerCount: Int,
    val maxPlayers: Int,
    val maxGamesPerPlayer: Int,
    val maxDepth: Int,
    val playersVisited: Int,
    val gamesInspected: Int,
    val rapidGames: Int,
    val uniqueGamesProcessed: Int,
    val maxDepthReached: Int,
    val stopReason: String,
    val playerIdentifiers: String = "Chess.com usernames; no separate stable player ID is exposed by the upstream payload",
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
