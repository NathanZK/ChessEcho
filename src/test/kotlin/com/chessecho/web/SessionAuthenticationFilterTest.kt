package com.chessecho.web

import com.chessecho.config.SessionCookieProperties
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.service.auth.IdentitySessionService
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

/**
 * Issue #113 (AC5/AC10, #79 D2/D4) — the request session filter resolves the
 * opaque session cookie into a request-scoped principal and fails closed when
 * the cookie is missing or does not resolve, and it seeds the readable
 * double-submit CSRF cookie on the response.
 *
 * Written test-first against the planned SessionAuthenticationFilter surface, so
 * it is expected to be red until production exists.
 */
class SessionAuthenticationFilterTest {
    private val service = mock<IdentitySessionService>()
    private val properties = SessionCookieProperties(cookie = SessionCookieProperties.Cookie(secure = false))
    private val filter = SessionAuthenticationFilter(service, properties)

    @Test
    fun `resolves a valid session cookie into a request-scoped principal`() {
        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)
        whenever(service.resolveSession("good-secret")).thenReturn(principal)

        val request = MockHttpServletRequest("GET", "/api/me")
        request.setCookies(Cookie(properties.cookie.name, "good-secret"))
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(principal, request.getAttribute(SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE))
        assertNotNull(chain.request)
    }

    @Test
    fun `fails closed with no principal when the session cookie is absent`() {
        val request = MockHttpServletRequest("GET", "/api/me")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertNull(request.getAttribute(SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE))
    }

    @Test
    fun `fails closed with no principal when the cookie does not resolve`() {
        whenever(service.resolveSession("garbage")).thenReturn(null)

        val request = MockHttpServletRequest("GET", "/api/me")
        request.setCookies(Cookie(properties.cookie.name, "garbage"))
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertNull(request.getAttribute(SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE))
    }

    @Test
    fun `seeds the readable CSRF cookie on the response when absent`() {
        val request = MockHttpServletRequest("GET", "/api/me")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        val setCookie = response.getHeader(HttpHeaders.SET_COOKIE)
        assertNotNull(setCookie, "Response must include Set-Cookie header for CSRF token")
        assertTrue(setCookie!!.startsWith("${properties.csrf.cookieName}="))
        assertTrue(setCookie.contains("Path=${properties.cookie.path}"))
        assertFalse(setCookie.contains("HttpOnly", ignoreCase = true))
        assertFalse(setCookie.contains("Secure", ignoreCase = true))
        assertFalse(setCookie.contains("SameSite", ignoreCase = true), "CSRF cookie must not declare SameSite policy")

        val cookie = response.getCookie(properties.csrf.cookieName)
        assertNotNull(cookie)
        assertFalse(cookie!!.isHttpOnly)
        assertFalse(cookie.secure)
        assertEquals(properties.cookie.path, cookie.path)
    }

    @Test
    fun `seeds Secure attribute on CSRF cookie when secure configuration is enabled`() {
        val secureProps = SessionCookieProperties(cookie = SessionCookieProperties.Cookie(secure = true))
        val secureFilter = SessionAuthenticationFilter(service, secureProps)
        val request = MockHttpServletRequest("GET", "/api/me")
        val response = MockHttpServletResponse()

        secureFilter.doFilter(request, response, MockFilterChain())

        val setCookie = response.getHeader(HttpHeaders.SET_COOKIE)
        assertNotNull(setCookie)
        assertTrue(setCookie!!.contains("Secure", ignoreCase = true))
        assertFalse(setCookie.contains("HttpOnly", ignoreCase = true))
        assertFalse(setCookie.contains("SameSite", ignoreCase = true))

        val cookie = response.getCookie(secureProps.csrf.cookieName)
        assertNotNull(cookie)
        assertTrue(cookie!!.secure)
        assertFalse(cookie.isHttpOnly)
    }

    @Test
    fun `does not reseed CSRF cookie when already present on request`() {
        val request = MockHttpServletRequest("GET", "/api/me")
        request.setCookies(Cookie(properties.csrf.cookieName, "existing-token"))
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertNull(response.getHeader(HttpHeaders.SET_COOKIE))
        assertNull(response.getCookie(properties.csrf.cookieName))
    }
}
