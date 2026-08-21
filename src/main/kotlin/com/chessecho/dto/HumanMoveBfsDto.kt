package com.chessecho.dto

data class HumanMoveBfsRequest(
    val ratingBand: String,
    val seedPlayers: List<String>,
    val maxQualifyingGames: Int = 2000,
    val maxGamesPerPlayer: Int = 100,
    val maxPlayers: Int = 100,
    val maxDepth: Int = 3,
    val minObservations: Int = 5,
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
