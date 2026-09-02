package com.chessecho.web

import com.chessecho.config.SessionCookieProperties
import com.chessecho.service.auth.EstablishedSession
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie

/**
 * Writes the `HttpOnly` opaque session cookie and the matching deletion cookie.
 * The deletion cookie replays the identical `Name`/`Path`/`Secure`/`SameSite`/
 * `HttpOnly` attributes with an empty value and `Max-Age=0` so browsers actually
 * delete the session cookie.
 */
class SessionCookieWriter(
    private val properties: SessionCookieProperties,
) {
    fun writeSessionCookie(
        response: HttpServletResponse,
        session: EstablishedSession,
    ) {
        val maxAge = java.time.Duration.between(java.time.Instant.now(), session.absoluteExpiresAt)
        val cookie =
            baseCookie(session.rawSecret)
                .maxAge(if (maxAge.isNegative) java.time.Duration.ZERO else maxAge)
                .build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }

    fun clearSessionCookie(response: HttpServletResponse) {
        val cookie =
            baseCookie("")
                .maxAge(0)
                .build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }

    private fun baseCookie(value: String): ResponseCookie.ResponseCookieBuilder =
        ResponseCookie.from(properties.cookie.name, value)
            .httpOnly(true)
            .secure(properties.cookie.secure)
            .path(properties.cookie.path)
            .sameSite(properties.cookie.sameSite)
}
