package com.chessecho.dto

import java.util.UUID

/**
 * The current-session summary returned by `GET /api/me`. It deliberately exposes
 * no reusable credential — no raw secret, no token hash, and no session id.
 */
data class CurrentUserResponse(
    val userId: UUID,
    val devPrincipal: Boolean,
    val email: String? = null,
)
