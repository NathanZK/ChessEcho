package com.chessecho.dto

import java.time.Instant

data class GameDto(
    val id: String,
    val platformGameId: String,
    val timeControl: String?,
    val playedAt: Instant?,
    val result: String?,
    val whiteUsername: String?,
    val blackUsername: String?,
    val pgn: String,
)
