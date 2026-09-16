package com.chessecho.controller

import com.chessecho.config.SessionCookieProperties
import com.chessecho.config.SessionWebConfig
import com.chessecho.dto.AccountAssociationRequest
import com.chessecho.service.AccountClaimConflictException
import com.chessecho.service.AccountOwnershipService
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.service.auth.IdentitySessionService
import com.chessecho.web.AuthenticatedPrincipalArgumentResolver
import com.chessecho.web.CsrfEnforcementInterceptor
import com.chessecho.web.SessionAuthenticationFilter
import com.chessecho.web.SessionCookieWriter
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID

@WebMvcTest(AccountController::class)
@EnableAutoConfiguration
@EnableConfigurationProperties(SessionCookieProperties::class)
@Import(
    SessionAuthenticationFilter::class,
    AuthenticatedPrincipalArgumentResolver::class,
    CsrfEnforcementInterceptor::class,
    SessionWebConfig::class,
    SessionCookieWriter::class,
)
@TestPropertySource(properties = ["chessecho.auth.cookie.name=CHESSECHO_SESSION"])
class AccountControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var accountOwnershipService: AccountOwnershipService

    @MockBean
    private lateinit var identitySessionService: IdentitySessionService

    @Test
    fun `POST accounts rejects foreign ownership claims with ACCOUNT_CLAIM_CONFLICT`() {
        val principal = AuthenticatedPrincipal(UUID.randomUUID(), devPrincipal = false)
        whenever(identitySessionService.resolveSession("good-secret")).thenReturn(principal)
        doThrow(AccountClaimConflictException())
            .whenever(accountOwnershipService)
            .associate(any(), any<AccountAssociationRequest>())

        mockMvc.post("/api/accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platform":"CHESS_COM","username":"caseplayer"}"""
            cookie(Cookie("CHESSECHO_SESSION", "good-secret"), Cookie("XSRF-TOKEN", "csrf-1"))
            header("X-XSRF-TOKEN", "csrf-1")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error") { value("ACCOUNT_CLAIM_CONFLICT") }
        }
    }

    @Test
    fun `POST account association without the CSRF token is rejected with 403`() {
        mockMvc.post("/api/accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platform":"CHESS_COM","username":"caseplayer"}"""
            cookie(Cookie("CHESSECHO_SESSION", "good-secret"), Cookie("XSRF-TOKEN", "csrf-1"))
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error") { value("CSRF_FAILED") }
        }
    }
}
