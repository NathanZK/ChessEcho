package com.chessecho.web

import com.chessecho.config.SessionCookieProperties
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.service.auth.IdentitySessionService
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
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
    private val properties = SessionCookieProperties()
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

        val csrfCookie = response.getCookie(properties.csrf.cookieName)
        assertNotNull(csrfCookie)
        assertTrue(!csrfCookie!!.value.isNullOrBlank())
    }
}
