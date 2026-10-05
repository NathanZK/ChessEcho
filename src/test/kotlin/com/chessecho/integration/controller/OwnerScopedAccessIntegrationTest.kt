package com.chessecho.integration.controller

import com.chessecho.service.auth.IdentitySessionService
import com.chessecho.service.auth.VerifiedIdentityClaims
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Issue #252 ownership boundary. Username/platform are lookup inputs, never an
 * authorization key.
 *
 * Amended by issue #457 (Decision 1, approved at the human gate): shared account-derived reads —
 * games, weaknesses, puzzles — are owned by the `ChessAccount` row, so any authenticated principal
 * may read them for any account that exists. The ownership boundary that remains is over
 * *operational* and *personal* data: a foreign principal still cannot read another principal's
 * import job.
 *
 * This class asserts only the HTTP authorization surface. The complementary guarantee — that the
 * per-user scheduling history layered on top of those shared reads stays scoped to the requesting
 * principal — is asserted at the service level by `SharedWeaknessCalculationReadIntegrationTest`,
 * which checks that weakness ranking order differs per principal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OwnerScopedAccessIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var identitySessionService: IdentitySessionService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val csrfCookie = "XSRF-TOKEN"
    private val csrfHeader = "X-XSRF-TOKEN"

    @Test
    fun `shared reads are open to any principal while jobs stay owner-scoped`() {
        val owner = session("owner-${UUID.randomUUID()}")
        val other = session("other-${UUID.randomUUID()}")
        val username = "owned-${UUID.randomUUID()}"

        val account = claim(owner, username)
        assertEquals(HttpStatus.CREATED, account.statusCode)

        privateReads(username, accountId(account)).forEach { path ->
            assertEquals(HttpStatus.OK, get(path, owner).statusCode, "owner must read $path")
            // #457 Decision 1: shared account-derived data is not gated on who connected the account.
            assertEquals(HttpStatus.OK, get(path, other).statusCode, "any principal must read shared $path")
            assertEquals(HttpStatus.UNAUTHORIZED, get(path, null).statusCode, "missing principal must be rejected for $path")
        }

        val jobResponse = startImport(owner, username, accountId(account))
        assertEquals(HttpStatus.ACCEPTED, jobResponse.statusCode)
        val jobPath = "/api/jobs/${objectMapper.readTree(jobResponse.body).path("jobId").asText()}"
        assertEquals(HttpStatus.OK, get(jobPath, owner).statusCode)
        assertEquals(HttpStatus.FORBIDDEN, get(jobPath, other).statusCode, "jobs remain owner-scoped")
        assertEquals(HttpStatus.UNAUTHORIZED, get(jobPath, null).statusCode)
        assertEquals(HttpStatus.NOT_FOUND, get("/api/jobs/${UUID.randomUUID()}", owner).statusCode)
    }

    @Test
    fun `guest can read an unconnected username and two users can connect the same account`() {
        val owner = session("owner-${UUID.randomUUID()}")
        val other = session("other-${UUID.randomUUID()}")
        val username = "unclaimed-${UUID.randomUUID()}"

        privateReads(username).forEach { path ->
            assertNotEquals(HttpStatus.UNAUTHORIZED, get(path, null).statusCode, "guest read must remain public: $path")
        }

        assertEquals(HttpStatus.CREATED, claim(owner, username).statusCode)
        assertEquals(HttpStatus.OK, claim(other, username).statusCode)
        privateReads(username).forEach { path ->
            assertEquals(HttpStatus.OK, get(path, null).statusCode, "guests can still read connected shared data: $path")
        }
    }

    @Test
    fun `guest can import a connected account and poll the guest job`() {
        val owner = session("owner-${UUID.randomUUID()}")
        val username = "guest-import-${UUID.randomUUID()}"

        val guestImport = startGuestImport(username)
        assertEquals(HttpStatus.ACCEPTED, guestImport.statusCode)
        val job = objectMapper.readTree(guestImport.body)
        val accountId = job.path("accountId").asText()
        val jobPath = "/api/jobs/${job.path("jobId").asText()}"

        val connection = claim(owner, username)
        assertEquals(HttpStatus.OK, connection.statusCode)
        assertEquals(accountId, accountId(connection))
        assertEquals(HttpStatus.OK, get(jobPath, null).statusCode, "connecting the account must not revoke guest job access")
        privateReads(username).forEach { path ->
            assertEquals(HttpStatus.OK, get(path, null).statusCode, "guest read must remain public after connection: $path")
        }
    }

    @Test
    fun `claim transition requires account selection for authenticated reads`() {
        val owner = session("owner-${UUID.randomUUID()}")
        val username = "transition-${UUID.randomUUID()}"

        privateReads(username).forEach { path ->
            assertNotEquals(HttpStatus.UNAUTHORIZED, get(path, null).statusCode)
        }
        val account = claim(owner, username)
        assertEquals(HttpStatus.CREATED, account.statusCode)
        privateReads(username).forEach { path ->
            val response = get(path, owner)
            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode, "authenticated guest selector must be rejected: $path")
            assertEquals("ACCOUNT_SELECTION_REQUIRED", objectMapper.readTree(response.body).path("error").asText())
        }
        privateReads(username, accountId(account)).forEach { path ->
            assertEquals(HttpStatus.OK, get(path, owner).statusCode, "claimed owner must read $path")
        }
    }

    private fun privateReads(
        username: String,
        accountId: String? = null,
    ): List<String> {
        val selector = accountId?.let { "accountId=$it" } ?: "platform=CHESS_COM&username=$username"
        return listOf(
            "/api/games?$selector",
            "/api/positions/weaknesses?$selector&playerColor=BOTH",
            "/api/puzzles?$selector&playerColor=BOTH",
        )
    }

    private fun claim(
        secret: String,
        username: String,
    ): ResponseEntity<String> {
        val headers = headers(secret, "claim-csrf")
        return restTemplate.exchange(
            "/api/accounts",
            HttpMethod.POST,
            HttpEntity("""{"platform":"CHESS_COM","username":"$username"}""", headers),
            String::class.java,
        )
    }

    private fun startImport(
        secret: String,
        username: String,
        accountId: String,
    ): ResponseEntity<String> {
        val headers = headers(secret, "import-csrf")
        return restTemplate.exchange(
            "/api/games/import",
            HttpMethod.POST,
            HttpEntity(
                """
                {"accountId":"$accountId","platform":"CHESS_COM","username":"$username",
                 "timeControls":["BLITZ"],"playerColor":"BOTH"}
                """.trimIndent(),
                headers,
            ),
            String::class.java,
        )
    }

    private fun startGuestImport(username: String): ResponseEntity<String> {
        val csrf = "guest-import-csrf"
        val headers =
            HttpHeaders().apply {
                add(HttpHeaders.COOKIE, "$csrfCookie=$csrf")
                add(csrfHeader, csrf)
                contentType = MediaType.APPLICATION_JSON
            }
        return restTemplate.exchange(
            "/api/games/import",
            HttpMethod.POST,
            HttpEntity(
                """{"platform":"CHESS_COM","username":"$username","timeControls":["BLITZ"],"playerColor":"BOTH"}""",
                headers,
            ),
            String::class.java,
        )
    }

    private fun accountId(response: ResponseEntity<String>): String {
        val body = objectMapper.readTree(response.body)
        return body.path("accountId").takeUnless { it.isMissingNode || it.isNull }?.asText()
            ?: body.path("id").asText()
    }

    private fun get(
        path: String,
        secret: String?,
    ): ResponseEntity<String> {
        val headers = HttpHeaders()
        if (secret != null) headers.add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=$secret")
        return restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    private fun headers(
        secret: String,
        csrf: String,
    ): HttpHeaders {
        val headers = HttpHeaders()
        headers.add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=$secret; $csrfCookie=$csrf")
        headers.add(csrfHeader, csrf)
        headers.contentType = org.springframework.http.MediaType.APPLICATION_JSON
        return headers
    }

    private fun session(subject: String): String =
        identitySessionService
            .establishSession(
                VerifiedIdentityClaims(
                    issuer = "integration-test",
                    subject = subject,
                    emailSnapshot = "$subject@example.test",
                    emailVerified = true,
                ),
                devPrincipal = false,
            ).rawSecret
}
