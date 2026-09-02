package com.chessecho.integration.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * Issue #113 (AC3/AC4/AC5/AC6/AC9/AC10, #79 D2/D7) — end-to-end proof that the
 * development principal works only through the shared identity/session boundary:
 * dev sign-in sets an HttpOnly session cookie, carrying it to /api/me yields 200,
 * logout revokes it so the same cookie yields 401, and missing/garbage cookies
 * fail closed. Credentialed CORS is honored for a configured origin and refused
 * for an unlisted one. Because @SpringBootTest boots via SpringApplication.run()
 * -> callRunners(), a successful boot under the allowlisted `dev` profile also
 * confirms the always-registered DevModeStartupGuard ran and permitted boot.
 *
 * Written test-first against the planned session boundary, so it is expected to
 * be red until production exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test", "dev")
@TestPropertySource(properties = ["chessecho.auth.dev-mode.enabled=true"])
class SessionCookieAuthIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    private val sessionCookieName = "CHESSECHO_SESSION"
    private val csrfCookieName = "XSRF-TOKEN"
    private val csrfHeaderName = "X-XSRF-TOKEN"

    private fun url(path: String): String = "http://localhost:$port$path"

    private fun cookieValue(
        headers: HttpHeaders,
        name: String,
    ): String? =
        headers[HttpHeaders.SET_COOKIE]
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("$name=")
            ?.substringBefore(";")

    private fun setCookieLine(
        headers: HttpHeaders,
        name: String,
    ): String? = headers[HttpHeaders.SET_COOKIE]?.firstOrNull { it.startsWith("$name=") }

    private fun get(cookieHeader: String?): org.springframework.http.ResponseEntity<String> {
        val headers = HttpHeaders()
        if (cookieHeader != null) headers.add(HttpHeaders.COOKIE, cookieHeader)
        return restTemplate.exchange(url("/api/me"), HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    @Test
    fun `dev sign-in establishes a session that flows through the shared boundary`() {
        // 1. An unauthenticated /api/me fails closed and seeds the readable CSRF cookie.
        val bootstrap = get(null)
        assertEquals(HttpStatus.UNAUTHORIZED, bootstrap.statusCode)
        val csrf = cookieValue(bootstrap.headers, csrfCookieName)
        assertNotNull(csrf, "GET /api/me must seed an $csrfCookieName cookie")

        // 2. Dev sign-in through the same identity/session code path.
        val signInHeaders = HttpHeaders()
        signInHeaders.add(HttpHeaders.COOKIE, "$csrfCookieName=$csrf")
        signInHeaders.add(csrfHeaderName, csrf)
        val signIn =
            restTemplate.exchange(
                url("/api/dev/session"),
                HttpMethod.POST,
                HttpEntity<Void>(signInHeaders),
                String::class.java,
            )
        assertEquals(HttpStatus.OK, signIn.statusCode)
        val sessionLine = setCookieLine(signIn.headers, sessionCookieName)
        assertNotNull(sessionLine, "dev sign-in must set a $sessionCookieName cookie")
        assertTrue(sessionLine!!.contains("HttpOnly", ignoreCase = true), "session cookie must be HttpOnly")
        val sessionValue = cookieValue(signIn.headers, sessionCookieName)

        // 3. Carrying the session cookie authenticates /api/me.
        val authed = get("$sessionCookieName=$sessionValue")
        assertEquals(HttpStatus.OK, authed.statusCode)

        // 4. Logout (with CSRF) revokes the session.
        val logoutHeaders = HttpHeaders()
        logoutHeaders.add(HttpHeaders.COOKIE, "$sessionCookieName=$sessionValue; $csrfCookieName=$csrf")
        logoutHeaders.add(csrfHeaderName, csrf)
        val logout =
            restTemplate.exchange(
                url("/api/logout"),
                HttpMethod.POST,
                HttpEntity<Void>(logoutHeaders),
                String::class.java,
            )
        assertEquals(HttpStatus.NO_CONTENT, logout.statusCode)

        // 5. The same cookie now fails closed.
        assertEquals(HttpStatus.UNAUTHORIZED, get("$sessionCookieName=$sessionValue").statusCode)
    }

    @Test
    fun `a garbage session cookie fails closed`() {
        assertEquals(HttpStatus.UNAUTHORIZED, get("$sessionCookieName=not-a-real-secret").statusCode)
    }

    @Test
    fun `credentialed CORS preflight is allowed for a configured origin`() {
        val headers = HttpHeaders()
        headers.add(HttpHeaders.ORIGIN, "http://localhost:3000")
        headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        val response =
            restTemplate.exchange(
                url("/api/logout"),
                HttpMethod.OPTIONS,
                HttpEntity<Void>(headers),
                String::class.java,
            )

        assertEquals("http://localhost:3000", response.headers.accessControlAllowOrigin)
        assertEquals("true", response.headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
    }

    @Test
    fun `credentialed CORS preflight is refused for an unlisted origin`() {
        val headers = HttpHeaders()
        headers.add(HttpHeaders.ORIGIN, "http://evil.example.com")
        headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        val response =
            restTemplate.exchange(
                url("/api/logout"),
                HttpMethod.OPTIONS,
                HttpEntity<Void>(headers),
                String::class.java,
            )

        assertNull(response.headers.accessControlAllowOrigin)
    }
}
