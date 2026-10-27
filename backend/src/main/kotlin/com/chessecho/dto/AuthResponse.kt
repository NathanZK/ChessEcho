package com.chessecho.dto

import java.util.UUID

/**
 * Safe authentication result. The session secret is emitted only as an
 * HttpOnly cookie by the controller and never appears in this response.
 */
data class AuthResponse(
    val userId: UUID,
    val email: String,
    val devPrincipal: Boolean = false,
)
