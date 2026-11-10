package com.chessprobe.controller

import com.chessprobe.dto.CurrentUserResponse
import com.chessprobe.service.auth.AuthenticatedPrincipal
import com.chessprobe.service.auth.IdentitySessionService
import com.chessprobe.web.SessionCookieWriter
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The current-session and logout endpoints (#113 AC5/AC6/AC10).
 *
 * `GET /api/me` distinguishes an authenticated principal (200 summary, no reusable
 * credential) from an unauthenticated/expired request (401, via the argument
 * resolver failing closed). `POST /api/logout` reads the raw secret directly from
 * the session cookie (never from the principal), revokes it, and clears the cookie;
 * it is CSRF-protected and idempotent.
 */
@RestController
@RequestMapping("/api")
class SessionController(
    private val identitySessionService: IdentitySessionService,
    private val sessionCookieWriter: SessionCookieWriter,
) {
    @GetMapping("/me")
    fun currentUser(principal: AuthenticatedPrincipal): CurrentUserResponse =
        CurrentUserResponse(userId = principal.appUserId, devPrincipal = principal.devPrincipal)

    @PostMapping("/logout")
    fun logout(
        @CookieValue(name = "\${chessprobe.auth.cookie.name:CHESSPROBE_SESSION}", required = false) rawSecret: String?,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        if (rawSecret != null) {
            identitySessionService.revokeSession(rawSecret)
        }
        sessionCookieWriter.clearSessionCookie(response)
        return ResponseEntity.noContent().build()
    }
}
