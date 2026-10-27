package com.chessecho.controller

import com.chessecho.config.SessionCookieProperties
import com.chessecho.config.SessionWebConfig
import com.chessecho.dto.AccountAssociationRequest
import com.chessecho.dto.ChessAccountResponse
import com.chessecho.service.AccountConnectionLimitReachedException
import com.chessecho.service.AccountNotFoundException
import com.chessecho.service.AccountOwnershipService
import com.chessecho.service.AssociationResult
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
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
    fun `POST returns the unchanged account summary with 201 for creation and 200 for reuse`() {
        val principal = AuthenticatedPrincipal(UUID.randomUUID(), devPrincipal = false)
        val account = ChessAccountResponse(UUID.randomUUID(), "CHESS_COM", "StoredCase")
        whenever(identitySessionService.resolveSession("good-secret")).thenReturn(principal)
        whenever(accountOwnershipService.associate(any(), any<AccountAssociationRequest>()))
            .thenReturn(AssociationResult(account, true), AssociationResult(account, false))

        listOf(201, 200).forEach { expectedStatus ->
            mockMvc.post("/api/accounts") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"platform":"CHESS_COM","username":"storedcase"}"""
                cookie(Cookie("CHESSECHO_SESSION", "good-secret"), Cookie("XSRF-TOKEN", "csrf-1"))
                header("X-XSRF-TOKEN", "csrf-1")
            }.andExpect {
                status { isEqualTo(expectedStatus) }
                content { json("""{"id":"${account.id}","platform":"CHESS_COM","username":"StoredCase"}""", true) }
            }
        }
    }

    @Test
    fun `GET returns a zero or one element summary array`() {
        val principal = AuthenticatedPrincipal(UUID.randomUUID(), devPrincipal = false)
        val account = ChessAccountResponse(UUID.randomUUID(), "CHESS_COM", "player")
        whenever(identitySessionService.resolveSession("good-secret")).thenReturn(principal)
        whenever(accountOwnershipService.listOwnedAccounts(principal)).thenReturn(emptyList(), listOf(account))

        listOf("[]", """[{"id":"${account.id}","platform":"CHESS_COM","username":"player"}]""").forEach { expected ->
            mockMvc.get("/api/accounts") {
                cookie(Cookie("CHESSECHO_SESSION", "good-secret"))
            }.andExpect {
                status { isOk() }
                content { json(expected, true) }
            }
        }
    }

    @Test
    fun `DELETE missing caller connection returns ACCOUNT_NOT_FOUND`() {
        val principal = AuthenticatedPrincipal(UUID.randomUUID(), devPrincipal = false)
        whenever(identitySessionService.resolveSession("good-secret")).thenReturn(principal)
        doThrow(AccountNotFoundException()).whenever(accountOwnershipService).disconnect(any(), eq(principal))

        mockMvc.delete("/api/accounts/${UUID.randomUUID()}/connection") {
            cookie(Cookie("CHESSECHO_SESSION", "good-secret"), Cookie("XSRF-TOKEN", "csrf-1"))
            header("X-XSRF-TOKEN", "csrf-1")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value("ACCOUNT_NOT_FOUND") }
        }
    }

    @Test
    fun `POST accounts rejects a second active connection with ACCOUNT_CONNECTION_LIMIT_REACHED`() {
        val principal = AuthenticatedPrincipal(UUID.randomUUID(), devPrincipal = false)
        whenever(identitySessionService.resolveSession("good-secret")).thenReturn(principal)
        doThrow(AccountConnectionLimitReachedException())
            .whenever(accountOwnershipService)
            .associate(any(), any<AccountAssociationRequest>())

        mockMvc.post("/api/accounts") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platform":"CHESS_COM","username":"caseplayer"}"""
            cookie(Cookie("CHESSECHO_SESSION", "good-secret"), Cookie("XSRF-TOKEN", "csrf-1"))
            header("X-XSRF-TOKEN", "csrf-1")
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error") { value("ACCOUNT_CONNECTION_LIMIT_REACHED") }
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

    @Test
    fun `DELETE account connection disconnects the authenticated owner's account`() {
        val principal = AuthenticatedPrincipal(UUID.randomUUID(), devPrincipal = false)
        val accountId = UUID.randomUUID()
        whenever(identitySessionService.resolveSession("good-secret")).thenReturn(principal)

        mockMvc.delete("/api/accounts/$accountId/connection") {
            cookie(Cookie("CHESSECHO_SESSION", "good-secret"), Cookie("XSRF-TOKEN", "csrf-1"))
            header("X-XSRF-TOKEN", "csrf-1")
        }.andExpect {
            status { isNoContent() }
            content { string("") }
        }

        verify(accountOwnershipService).disconnect(eq(accountId), eq(principal))
    }

    @Test
    fun `DELETE account connection without CSRF token is rejected`() {
        whenever(identitySessionService.resolveSession("good-secret"))
            .thenReturn(AuthenticatedPrincipal(UUID.randomUUID(), devPrincipal = false))

        mockMvc.delete("/api/accounts/${UUID.randomUUID()}/connection") {
            cookie(Cookie("CHESSECHO_SESSION", "good-secret"), Cookie("XSRF-TOKEN", "csrf-1"))
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error") { value("CSRF_FAILED") }
        }
    }
}
