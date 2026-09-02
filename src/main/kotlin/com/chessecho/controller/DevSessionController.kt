package com.chessecho.controller

import com.chessecho.dto.CurrentUserResponse
import com.chessecho.service.auth.DevIdentityProvider
import com.chessecho.service.auth.IdentitySessionService
import com.chessecho.web.SessionCookieWriter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The explicit development-only sign-in endpoint (#113 AC9, #79 D7). It is a
 * fail-closed allowlist bean: present only under the `{dev, local}` profile
 * allowlist AND when `chessecho.auth.dev-mode.enabled=true` (default off), so the
 * default/production profile can never expose it (`POST /api/dev/session` → 404).
 * When present it routes through the same [IdentitySessionService] path as any
 * provider. It is CSRF-protected by the shared enforcement interceptor.
 */
@RestController
@RequestMapping("/api/dev")
@Profile("dev", "local")
@ConditionalOnProperty(prefix = "chessecho.auth.dev-mode", name = ["enabled"], havingValue = "true")
class DevSessionController(
    private val identitySessionService: IdentitySessionService,
    private val devIdentityProvider: DevIdentityProvider,
    private val sessionCookieWriter: SessionCookieWriter,
) {
    @PostMapping("/session")
    fun establishDevSession(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): CurrentUserResponse {
        val established =
            identitySessionService.establishSession(
                devIdentityProvider.claims(request),
                devPrincipal = true,
            )
        sessionCookieWriter.writeSessionCookie(response, established)
        return CurrentUserResponse(
            userId = established.principal.appUserId,
            devPrincipal = established.principal.devPrincipal,
        )
    }
}
