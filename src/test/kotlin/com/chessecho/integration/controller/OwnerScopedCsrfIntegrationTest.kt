package com.chessecho.integration.controller

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Owner mutations use the same double-submit CSRF boundary as session logout.
 * Authentication and account ownership are deliberately tested after CSRF:
 * a missing or mismatched pair is always 403, while a matching pair may then
 * produce the endpoint's normal 401/409/2xx result.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OwnerScopedCsrfIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val csrfCookie = "XSRF-TOKEN"
    private val csrfHeader = "X-XSRF-TOKEN"

    @Test
    fun `account association and import reject every incomplete or mismatched csrf pair`() {
        listOf("/api/accounts", "/api/games/import").forEach { path ->
            assertEquals(HttpStatus.FORBIDDEN, mutate(path, cookie = null, header = null).statusCode, path)
            assertEquals(HttpStatus.FORBIDDEN, mutate(path, cookie = "csrf-a", header = null).statusCode, path)
            assertEquals(HttpStatus.FORBIDDEN, mutate(path, cookie = null, header = "csrf-a").statusCode, path)
            assertEquals(HttpStatus.FORBIDDEN, mutate(path, cookie = "csrf-a", header = "csrf-b").statusCode, path)
        }
    }

    @Test
    fun `matching csrf pair passes csrf before account authentication or import validation`() {
        val account = mutate("/api/accounts", cookie = "csrf-a", header = "csrf-a")
        val import = mutate("/api/games/import", cookie = "csrf-a", header = "csrf-a")

        assertFalse(account.statusCode == HttpStatus.FORBIDDEN, "matching account CSRF must reach auth/ownership")
        assertFalse(import.statusCode == HttpStatus.FORBIDDEN, "matching import CSRF must reach auth/validation")
    }

    @Test
    fun `safe reads and head are not csrf protected`() {
        val get =
            restTemplate.exchange(
                "/api/games?platform=CHESS_COM&username=csrf-safe",
                HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders()),
                String::class.java,
            )
        val head =
            restTemplate.exchange(
                "/api/games?platform=CHESS_COM&username=csrf-safe",
                HttpMethod.HEAD,
                HttpEntity<Void>(HttpHeaders()),
                String::class.java,
            )

        assertFalse(get.statusCode == HttpStatus.FORBIDDEN)
        assertFalse(head.statusCode == HttpStatus.FORBIDDEN)
    }

    @Test
    fun `cors preflight is exempt and advertises the owner mutation contract`() {
        val headers = HttpHeaders()
        headers.add(HttpHeaders.ORIGIN, "http://localhost:3000")
        headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, csrfHeader)

        val response =
            restTemplate.exchange(
                "/api/accounts",
                HttpMethod.OPTIONS,
                HttpEntity<Void>(headers),
                String::class.java,
            )

        assertTrue(response.statusCode.is2xxSuccessful)
        assertEquals("http://localhost:3000", response.headers.accessControlAllowOrigin)
        assertTrue(response.headers.accessControlAllowMethods.contains(HttpMethod.POST))
        assertTrue(response.headers.accessControlAllowHeaders.any { it.equals(csrfHeader, ignoreCase = true) })
    }

    private fun mutate(
        path: String,
        cookie: String?,
        header: String?,
    ): ResponseEntity<String> {
        val headers = HttpHeaders()
        if (cookie != null) headers.add(HttpHeaders.COOKIE, "$csrfCookie=$cookie")
        if (header != null) headers.add(csrfHeader, header)
        headers.contentType = org.springframework.http.MediaType.APPLICATION_JSON
        val body =
            if (path == "/api/accounts") {
                """{"platform":"CHESS_COM","username":"csrf-account"}"""
            } else {
                """{"platform":"CHESS_COM","username":"csrf-import","timeControls":["BLITZ"],"playerColor":"BOTH"}"""
            }
        return restTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, headers), String::class.java)
    }
}
