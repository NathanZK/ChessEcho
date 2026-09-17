package com.chessecho.integration.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles

/**
 * Issue #276 — registration and login share the existing opaque-session and
 * double-submit CSRF boundary. Login failures deliberately reveal no account
 * existence information.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LocalAuthIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    private val csrfCookieName = "XSRF-TOKEN"
    private val csrfHeaderName = "X-XSRF-TOKEN"
    private val sessionCookieName = "CHESSECHO_SESSION"

    private fun url(path: String): String = "http://localhost:$port$path"

    private fun cookieValue(
        headers: HttpHeaders,
        name: String,
    ): String? =
        headers[HttpHeaders.SET_COOKIE]
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("$name=")
            ?.substringBefore(";")

    private fun csrfToken(): String {
        val response = restTemplate.getForEntity(url("/api/me"), String::class.java)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        return requireNotNull(cookieValue(response.headers, csrfCookieName))
    }

    private fun post(
        path: String,
        body: String,
        csrf: String? = null,
    ): ResponseEntity<String> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        if (csrf != null) {
            headers.add(HttpHeaders.COOKIE, "$csrfCookieName=$csrf")
            headers.add(csrfHeaderName, csrf)
        }
        return restTemplate.exchange(url(path), HttpMethod.POST, HttpEntity(body, headers), String::class.java)
    }

    @Test
    fun `register and login reject missing CSRF before processing credentials`() {
        listOf("/api/register", "/api/login").forEach { endpoint ->
            val response = post(endpoint, """{"email":"person@example.com","password":"correct horse battery staple"}""")

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode, endpoint)
            assertTrue(response.body!!.contains("CSRF_FAILED"), endpoint)
        }
    }

    @Test
    fun `registration canonicalizes Unicode-trimmed email without provider rewriting and issues only an opaque session`() {
        val csrf = csrfToken()
        val response =
            post(
                "/api/register",
                """{"email":"\u2003 Alice.Tag+chess@Example.COM \u2003","password":"correct horse battery staple"}""",
                csrf,
            )

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertTrue(response.body!!.contains("alice.tag+chess@example.com"))
        assertFalse(response.body!!.contains("correct horse battery staple"))
        assertFalse(response.body!!.contains("sessionId", ignoreCase = true))
        assertFalse(response.body!!.contains("secret", ignoreCase = true))
        assertFalse(response.body!!.contains("hash", ignoreCase = true))

        val session = requireNotNull(cookieValue(response.headers, sessionCookieName))
        assertTrue(session.length >= 32, "session cookie must contain an opaque, high-entropy secret")
        assertFalse(response.body!!.contains(session), "raw session secret must never be returned in JSON")
    }

    @Test
    fun `duplicate canonical registration conflicts while login resolves the same canonical email`() {
        val csrf = csrfToken()
        val password = "correct horse battery staple"
        assertEquals(
            HttpStatus.CREATED,
            post("/api/register", """{"email":" Alice@Example.COM ","password":"$password"}""", csrf).statusCode,
        )

        val duplicate = post("/api/register", """{"email":"alice@example.com","password":"$password"}""", csrf)
        assertEquals(HttpStatus.CONFLICT, duplicate.statusCode)

        val login = post("/api/login", """{"email":"\u2003ALICE@example.com\u2003","password":"$password"}""", csrf)
        assertEquals(HttpStatus.OK, login.statusCode)
        assertNotNull(cookieValue(login.headers, sessionCookieName))
    }

    @Test
    fun `unknown email and wrong password have equivalent generic failure responses and no session`() {
        val csrf = csrfToken()
        val password = "correct horse battery staple"
        assertEquals(
            HttpStatus.CREATED,
            post("/api/register", """{"email":"known@example.com","password":"$password"}""", csrf).statusCode,
        )

        val unknown = post("/api/login", """{"email":"unknown@example.com","password":"$password"}""", csrf)
        val wrongPassword = post("/api/login", """{"email":"known@example.com","password":"wrong password"}""", csrf)

        assertEquals(unknown.statusCode, wrongPassword.statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, unknown.statusCode)
        assertEquals(unknown.body, wrongPassword.body)
        assertFalse(unknown.body!!.contains("unknown@example.com"))
        assertFalse(unknown.body!!.contains("known@example.com"))
        assertNull(cookieValue(unknown.headers, sessionCookieName))
        assertNull(cookieValue(wrongPassword.headers, sessionCookieName))
    }
}
