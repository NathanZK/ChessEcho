package com.chessecho.service.auth

import java.time.Instant
import java.util.UUID

/**
 * The request-scoped authenticated principal. It deliberately carries no raw
 * session secret and no session id — only the internal owner key and whether the
 * session was established through the development principal path.
 */
data class AuthenticatedPrincipal(
    val appUserId: UUID,
    val devPrincipal: Boolean,
)

/**
 * The result of establishing (or rotating) a session. The [rawSecret] is returned
 * exclusively so the cookie writer can emit it; it is never persisted or logged.
 */
data class EstablishedSession(
    val rawSecret: String,
    val idleExpiresAt: Instant,
    val absoluteExpiresAt: Instant,
    val principal: AuthenticatedPrincipal,
)
