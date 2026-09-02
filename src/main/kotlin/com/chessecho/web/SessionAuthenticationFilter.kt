package com.chessecho.web

import com.chessecho.config.SessionCookieProperties
import com.chessecho.service.auth.IdentitySessionService
import com.chessecho.service.auth.SessionSecrets
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Resolves the opaque session cookie into a request-scoped [AuthenticatedPrincipal]
 * attribute and seeds the readable double-submit CSRF cookie when absent.
 *
 * The filter never rejects a request; it fails closed by simply not attaching a
 * principal, and authorization is enforced where a principal is required (via the
 * argument resolver).
 */
class SessionAuthenticationFilter(
    private val identitySessionService: IdentitySessionService,
    private val properties: SessionCookieProperties,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rawSecret = cookieValue(request, properties.cookie.name)
        if (rawSecret != null) {
            val principal = identitySessionService.resolveSession(rawSecret)
            if (principal != null) {
                request.setAttribute(PRINCIPAL_ATTRIBUTE, principal)
            }
        }
        ensureCsrfCookie(request, response)
        filterChain.doFilter(request, response)
    }

    private fun ensureCsrfCookie(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        if (cookieValue(request, properties.csrf.cookieName) != null) {
            return
        }
        val token = SessionSecrets.generateSecret()
        val cookie = Cookie(properties.csrf.cookieName, token)
        cookie.isHttpOnly = false
        cookie.path = properties.cookie.path
        cookie.secure = properties.cookie.secure
        response.addCookie(cookie)
    }

    private fun cookieValue(
        request: HttpServletRequest,
        name: String,
    ): String? = request.cookies?.firstOrNull { it.name == name }?.value

    companion object {
        const val PRINCIPAL_ATTRIBUTE = "com.chessecho.web.AUTHENTICATED_PRINCIPAL"
    }
}
