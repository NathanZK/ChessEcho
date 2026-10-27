package com.chessecho.integration.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.NestedTestConfiguration
import org.springframework.test.context.TestPropertySource

/**
 * Issue #615 — end-to-end verification of cookie transport security flags.
 *
 * Verifies that under the default/production profile, session and CSRF cookies
 * are emitted with the `Secure` attribute by default, while development and test
 * profiles permit plain-HTTP execution with `cookie.secure=false` without
 * emitting the `Secure` attribute. Invariants on `HttpOnly` attributes
 * (session is HttpOnly, CSRF is readable by JavaScript) are preserved across both.
 */
class CookieTransportSecurityIntegrationTest {
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @TestPropertySource(
        properties = [
            "spring.datasource.url=jdbc:h2:mem:prodcookie;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.flyway.enabled=false",
        ],
    )
    inner class ProductionDefaultProfile {
        @Autowired
        private lateinit var restTemplate: TestRestTemplate

        @LocalServerPort
        private var port: Int = 0

        private val csrfCookieName = "XSRF-TOKEN"
        private val csrfHeaderName = "X-XSRF-TOKEN"
        private val sessionCookieName = "CHESSECHO_SESSION"

        private fun url(path: String): String = "http://localhost:$port$path"

        private fun setCookieLine(
            headers: HttpHeaders,
            name: String,
        ): String? = headers[HttpHeaders.SET_COOKIE]?.firstOrNull { it.startsWith("$name=") }

        private fun cookieValue(
            headers: HttpHeaders,
            name: String,
        ): String? =
            setCookieLine(headers, name)
                ?.substringAfter("$name=")
                ?.substringBefore(";")

        @Test
        fun `production default profile emits Secure and preserves HttpOnly invariants`() {
            // 1. GET /api/me seeds XSRF-TOKEN with Secure=true and HttpOnly=false
            val bootstrap = restTemplate.getForEntity(url("/api/me"), String::class.java)
            assertEquals(HttpStatus.UNAUTHORIZED, bootstrap.statusCode)

            val csrfLine = setCookieLine(bootstrap.headers, csrfCookieName)
            assertNotNull(csrfLine, "GET /api/me must seed $csrfCookieName")
            assertTrue(csrfLine!!.contains("Secure", ignoreCase = true), "CSRF cookie must be Secure in production")
            assertFalse(
                csrfLine.contains("HttpOnly", ignoreCase = true),
                "CSRF cookie must not be HttpOnly for double-submit access",
            )

            val csrfValue = requireNotNull(cookieValue(bootstrap.headers, csrfCookieName))

            // 2. POST /api/register issues CHESSECHO_SESSION with Secure=true and HttpOnly=true
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_JSON
            headers.add(HttpHeaders.COOKIE, "$csrfCookieName=$csrfValue")
            headers.add(csrfHeaderName, csrfValue)

            val registrationBody = """{"email":"operator@example.com","password":"secure password 1234"}"""
            val registrationResponse =
                restTemplate.exchange(
                    url("/api/register"),
                    HttpMethod.POST,
                    HttpEntity(registrationBody, headers),
                    String::class.java,
                )

            assertEquals(HttpStatus.CREATED, registrationResponse.statusCode)
            val sessionLine = setCookieLine(registrationResponse.headers, sessionCookieName)
            assertNotNull(sessionLine, "Registration must emit $sessionCookieName cookie")
            assertTrue(
                sessionLine!!.contains("Secure", ignoreCase = true),
                "Session cookie must be Secure in production",
            )
            assertTrue(
                sessionLine.contains("HttpOnly", ignoreCase = true),
                "Session cookie must be HttpOnly",
            )
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @ActiveProfiles("test", "dev")
    @TestPropertySource(
        properties = [
            "chessecho.auth.cookie.secure=false",
            "chessecho.auth.dev-mode.enabled=true",
        ],
    )
    inner class DevelopmentProfile {
        @Autowired
        private lateinit var restTemplate: TestRestTemplate

        @LocalServerPort
        private var port: Int = 0

        private val csrfCookieName = "XSRF-TOKEN"
        private val csrfHeaderName = "X-XSRF-TOKEN"
        private val sessionCookieName = "CHESSECHO_SESSION"

        private fun url(path: String): String = "http://localhost:$port$path"

        private fun setCookieLine(
            headers: HttpHeaders,
            name: String,
        ): String? = headers[HttpHeaders.SET_COOKIE]?.firstOrNull { it.startsWith("$name=") }

        private fun cookieValue(
            headers: HttpHeaders,
            name: String,
        ): String? =
            setCookieLine(headers, name)
                ?.substringAfter("$name=")
                ?.substringBefore(";")

        @Test
        fun `development profile with cookie secure false permits plain HTTP without Secure flag`() {
            // 1. GET /api/me seeds XSRF-TOKEN without Secure flag
            val bootstrap = restTemplate.getForEntity(url("/api/me"), String::class.java)
            assertEquals(HttpStatus.UNAUTHORIZED, bootstrap.statusCode)

            val csrfLine = setCookieLine(bootstrap.headers, csrfCookieName)
            assertNotNull(csrfLine, "GET /api/me must seed $csrfCookieName")
            assertFalse(
                csrfLine!!.contains("Secure", ignoreCase = true),
                "CSRF cookie must not be Secure in plain-HTTP dev mode",
            )
            assertFalse(
                csrfLine.contains("HttpOnly", ignoreCase = true),
                "CSRF cookie must not be HttpOnly",
            )

            val csrfValue = requireNotNull(cookieValue(bootstrap.headers, csrfCookieName))

            // 2. Dev sign-in sets CHESSECHO_SESSION without Secure flag but with HttpOnly
            val signInHeaders = HttpHeaders()
            signInHeaders.add(HttpHeaders.COOKIE, "$csrfCookieName=$csrfValue")
            signInHeaders.add(csrfHeaderName, csrfValue)

            val signInResponse =
                restTemplate.exchange(
                    url("/api/dev/session"),
                    HttpMethod.POST,
                    HttpEntity<Void>(signInHeaders),
                    String::class.java,
                )

            assertEquals(HttpStatus.OK, signInResponse.statusCode)
            val sessionLine = setCookieLine(signInResponse.headers, sessionCookieName)
            assertNotNull(sessionLine, "Dev sign-in must emit $sessionCookieName cookie")
            assertFalse(
                sessionLine!!.contains("Secure", ignoreCase = true),
                "Session cookie must not be Secure in plain-HTTP dev mode",
            )
            assertTrue(
                sessionLine.contains("HttpOnly", ignoreCase = true),
                "Session cookie must remain HttpOnly in dev mode",
            )
        }
    }
}
