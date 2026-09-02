package com.chessecho.controller

import com.chessecho.config.SessionCookieProperties
import com.chessecho.config.SessionWebConfig
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.service.auth.IdentitySessionService
import com.chessecho.web.AuthenticatedPrincipalArgumentResolver
import com.chessecho.web.CsrfEnforcementInterceptor
import com.chessecho.web.SessionAuthenticationFilter
import com.chessecho.web.SessionCookieWriter
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

/**
 * Issue #113 (AC5/AC6/AC10, #79 D2) — the current-session/logout controller
 * contract: GET /api/me distinguishes authenticated (200 principal summary with
 * no reusable credential) from unauthenticated/expired (401); POST /api/logout
 * reads the raw secret from the session cookie (not the principal), revokes it,
 * clears the cookie by replaying its attributes with Max-Age=0, is idempotent,
 * and is CSRF-protected via the double-submit token.
 *
 * Written test-first against the planned SessionController + request-security
 * surface, so it is expected to be red until production exists.
 */
@WebMvcTest(SessionController::class)
@EnableConfigurationProperties(SessionCookieProperties::class)
@Import(
    SessionAuthenticationFilter::class,
    AuthenticatedPrincipalArgumentResolver::class,
    CsrfEnforcementInterceptor::class,
    SessionWebConfig::class,
    SessionCookieWriter::class,
)
@TestPropertySource(properties = ["chessecho.auth.cookie.name=CHESSECHO_SESSION"])
class SessionControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var identitySessionService: IdentitySessionService

    private val sessionCookieName = "CHESSECHO_SESSION"
    private val csrfCookieName = "XSRF-TOKEN"
    private val csrfHeaderName = "X-XSRF-TOKEN"

    @Test
    fun `GET me returns 200 principal summary without a reusable credential`() {
        val userId = UUID.randomUUID()
        whenever(identitySessionService.resolveSession("good-secret"))
            .thenReturn(AuthenticatedPrincipal(appUserId = userId, devPrincipal = true))

        mockMvc.get("/api/me") {
            cookie(Cookie(sessionCookieName, "good-secret"))
        }.andExpect {
            status { isOk() }
            jsonPath("$.userId") { value(userId.toString()) }
            jsonPath("$.devPrincipal") { value(true) }
            jsonPath("$.rawSecret") { doesNotExist() }
            jsonPath("$.tokenHash") { doesNotExist() }
            jsonPath("$.sessionId") { doesNotExist() }
        }
    }

    @Test
    fun `GET me returns 401 when no valid session is present`() {
        mockMvc.get("/api/me").andExpect {
            status { isUnauthorized() }
            jsonPath("$.error") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun `POST logout revokes the raw cookie value and clears the session cookie`() {
        val result =
            mockMvc.post("/api/logout") {
                cookie(Cookie(sessionCookieName, "good-secret"), Cookie(csrfCookieName, "csrf-1"))
                header(csrfHeaderName, "csrf-1")
            }.andExpect {
                status { isNoContent() }
            }.andReturn()

        verify(identitySessionService).revokeSession("good-secret")

        val setCookies = result.response.getHeaders("Set-Cookie")
        val cleared = setCookies.any { it.contains("$sessionCookieName=") && it.contains("Max-Age=0") }
        assertTrue(cleared, "logout must clear the session cookie with Max-Age=0; got $setCookies")
    }

    @Test
    fun `POST logout is idempotent when no session cookie is present`() {
        mockMvc.post("/api/logout") {
            cookie(Cookie(csrfCookieName, "csrf-1"))
            header(csrfHeaderName, "csrf-1")
        }.andExpect {
            status { isNoContent() }
        }

        verify(identitySessionService, never()).revokeSession(any())
    }

    @Test
    fun `POST logout without the CSRF header is rejected with 403`() {
        mockMvc.post("/api/logout") {
            cookie(Cookie(sessionCookieName, "good-secret"), Cookie(csrfCookieName, "csrf-1"))
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error") { value("CSRF_FAILED") }
        }

        verify(identitySessionService, never()).revokeSession(any())
    }

    @Test
    fun `POST logout with a mismatched CSRF token is rejected with 403`() {
        mockMvc.post("/api/logout") {
            cookie(Cookie(sessionCookieName, "good-secret"), Cookie(csrfCookieName, "csrf-1"))
            header(csrfHeaderName, "different-token")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error") { value("CSRF_FAILED") }
        }
    }
}
