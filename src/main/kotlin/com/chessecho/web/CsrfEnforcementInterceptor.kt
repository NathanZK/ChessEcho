package com.chessecho.web

import com.chessecho.config.SessionCookieProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.cors.CorsUtils
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import java.security.MessageDigest

/**
 * Double-submit CSRF enforcement for state-changing session endpoints. The
 * readable `XSRF-TOKEN` cookie must equal the `X-XSRF-TOKEN` header, compared in
 * constant time. A missing or mismatched token throws [CsrfException] (mapped to
 * 403). CORS preflight requests are exempt so credentialed preflight succeeds.
 */
class CsrfEnforcementInterceptor(
    private val properties: SessionCookieProperties,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (CorsUtils.isPreFlightRequest(request)) {
            return true
        }
        // Only enforce for real controller handlers; a static-resource fallback
        // (e.g. an unmapped path) must fall through to its natural 404, not 403.
        if (handler !is HandlerMethod) {
            return true
        }
        val header = request.getHeader(properties.csrf.headerName)
        val cookie = request.cookies?.firstOrNull { it.name == properties.csrf.cookieName }?.value
        if (header.isNullOrEmpty() || cookie.isNullOrEmpty() || !constantTimeEquals(header, cookie)) {
            throw CsrfException()
        }
        return true
    }

    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
