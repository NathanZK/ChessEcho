package com.chessprobe.isolation

import com.chessprobe.AbstractIntegrationTest
import com.chessprobe.domain.AccountConnection
import com.chessprobe.domain.AppUser
import com.chessprobe.domain.AuthSession
import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.Platform
import com.chessprobe.domain.PlayerColor
import com.chessprobe.domain.TimeControl
import com.chessprobe.dto.ImportGamesRequest
import com.chessprobe.service.auth.SessionSecrets
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Issue #798 (AC 3.1–3.4): Real filter- and persistence-backed dual-tenant authorization verification.
 * Exercises the full servlet filter pipeline (SessionAuthenticationFilter), cookie parsing, token hashing,
 * and PostgreSQL AccountOwnershipService queries without mocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DualTenantSecurityFilterIntegrationTest : AbstractIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var authSessionRepository: com.chessprobe.repository.AuthSessionRepository

    @LocalServerPort
    private var port: Int = 0

    private val sessionCookieName = "CHESSPROBE_SESSION"
    private val csrfCookieName = "XSRF-TOKEN"
    private val csrfHeaderName = "X-XSRF-TOKEN"
    private val csrfTokenValue = "test-csrf-token-val"

    private lateinit var userA: AppUser
    private lateinit var accountA: ChessAccount
    private lateinit var sessionA: AuthSession
    private val tokenSecretA = "secret-tenant-a-" + UUID.randomUUID()

    private lateinit var userB: AppUser
    private lateinit var accountB: ChessAccount
    private lateinit var sessionB: AuthSession
    private val tokenSecretB = "secret-tenant-b-" + UUID.randomUUID()

    private fun url(path: String): String = "http://localhost:$port$path"

    private fun authenticatedHeaders(tokenSecret: String?): HttpHeaders {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        headers.add(HttpHeaders.COOKIE, "$csrfCookieName=$csrfTokenValue")
        headers.add(csrfHeaderName, csrfTokenValue)
        if (tokenSecret != null) {
            headers.add(HttpHeaders.COOKIE, "$sessionCookieName=$tokenSecret; $csrfCookieName=$csrfTokenValue")
        }
        return headers
    }

    @BeforeEach
    fun setupTenants() {
        val now = Instant.now()

        // Tenant A
        userA = appUserRepository.saveAndFlush(AppUser(email = "tenant-a-${UUID.randomUUID()}@example.com"))
        accountA =
            chessAccountRepository.saveAndFlush(
                ChessAccount(
                    platform = Platform.CHESS_COM.name,
                    username = "tenant_a_${UUID.randomUUID().toString().take(8)}",
                ),
            )
        accountConnectionRepository.saveAndFlush(AccountConnection(appUser = userA, chessAccount = accountA))
        sessionA =
            authSessionRepository.saveAndFlush(
                AuthSession(
                    tokenHash = SessionSecrets.sha256Hex(tokenSecretA),
                    user = userA,
                    devPrincipal = false,
                    createdAt = now,
                    lastSeenAt = now,
                    idleExpiresAt = now.plusSeconds(3600),
                    absoluteExpiresAt = now.plusSeconds(86400),
                ),
            )

        // Tenant B
        userB = appUserRepository.saveAndFlush(AppUser(email = "tenant-b-${UUID.randomUUID()}@example.com"))
        accountB =
            chessAccountRepository.saveAndFlush(
                ChessAccount(
                    platform = Platform.CHESS_COM.name,
                    username = "tenant_b_${UUID.randomUUID().toString().take(8)}",
                ),
            )
        accountConnectionRepository.saveAndFlush(AccountConnection(appUser = userB, chessAccount = accountB))
        sessionB =
            authSessionRepository.saveAndFlush(
                AuthSession(
                    tokenHash = SessionSecrets.sha256Hex(tokenSecretB),
                    user = userB,
                    devPrincipal = false,
                    createdAt = now,
                    lastSeenAt = now,
                    idleExpiresAt = now.plusSeconds(3600),
                    absoluteExpiresAt = now.plusSeconds(86400),
                ),
            )
    }

    @AfterEach
    fun tearDownTenants() {
        val tenantAccounts = listOfNotNull(accountA, accountB)
        for (account in tenantAccounts) {
            drainAsyncWorkerQuiescence(accountId = account.id)
        }
        for (account in tenantAccounts) {
            val context = cleanTenantData(accountId = account.id)
            assertTenantQuarantined(context)
        }
    }

    @Test
    fun `positive control - tenant A accesses own account returns 200 OK`() {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val response =
            restTemplate.exchange(
                url("/api/positions/similar?fen=$fen&accountId=${accountA.id}"),
                HttpMethod.GET,
                HttpEntity<Void>(authenticatedHeaders(tokenSecretA)),
                String::class.java,
            )
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `cross tenant denial - tenant A targeting tenant B account on similar positions returns 403 Forbidden`() {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val response =
            restTemplate.exchange(
                url("/api/positions/similar?fen=$fen&accountId=${accountB.id}"),
                HttpMethod.GET,
                HttpEntity<Void>(authenticatedHeaders(tokenSecretA)),
                String::class.java,
            )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `cross tenant denial - tenant A targeting tenant B account on game import returns 403 Forbidden`() {
        val request =
            ImportGamesRequest(
                accountId = accountB.id,
                platform = Platform.CHESS_COM,
                username = accountB.username,
                timeControls = listOf(TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
            )
        val response =
            restTemplate.exchange(
                url("/api/games/import"),
                HttpMethod.POST,
                HttpEntity(request, authenticatedHeaders(tokenSecretA)),
                String::class.java,
            )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `cross tenant anti-enumeration - tenant A deleting tenant B connection returns 404 Not Found`() {
        val response =
            restTemplate.exchange(
                url("/api/accounts/${accountB.id}/connection"),
                HttpMethod.DELETE,
                HttpEntity<Void>(authenticatedHeaders(tokenSecretA)),
                String::class.java,
            )
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `unauthenticated denial - request without session cookie returns 401 Unauthorized`() {
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val response =
            restTemplate.exchange(
                url("/api/positions/similar?fen=$fen&accountId=${accountA.id}"),
                HttpMethod.GET,
                HttpEntity<Void>(authenticatedHeaders(null)),
                String::class.java,
            )
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)

        val importRequest =
            ImportGamesRequest(
                accountId = accountA.id,
                platform = Platform.CHESS_COM,
                username = accountA.username,
                timeControls = listOf(TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
            )
        val importResponse =
            restTemplate.exchange(
                url("/api/games/import"),
                HttpMethod.POST,
                HttpEntity(importRequest, authenticatedHeaders(null)),
                String::class.java,
            )
        assertEquals(HttpStatus.UNAUTHORIZED, importResponse.statusCode)

        val disconnectResponse =
            restTemplate.exchange(
                url("/api/accounts/${accountA.id}/connection"),
                HttpMethod.DELETE,
                HttpEntity<Void>(authenticatedHeaders(null)),
                String::class.java,
            )
        assertEquals(HttpStatus.UNAUTHORIZED, disconnectResponse.statusCode)
    }
}
