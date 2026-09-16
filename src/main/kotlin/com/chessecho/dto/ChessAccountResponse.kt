package com.chessecho.dto

import java.util.UUID

data class ChessAccountResponse(
    val id: UUID,
    val platform: String,
    val username: String,
)
