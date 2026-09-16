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
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Issue #252 ownership boundary. Username/platform are lookup inputs, never an
 * authorization key: private rows are selected through the authenticated owner
 * and a foreign owner receives 403 without an existence leak.
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
    fun `two principals isolate games weaknesses puzzles jobs and derived data`() {
        val owner = session("owner-${UUID.randomUUID()}")
        val other = session("other-${UUID.randomUUID()}")
        val username = "owned-${UUID.randomUUID()}"

        val account = claim(owner, username)
        assertEquals(HttpStatus.CREATED, account.statusCode)

        privateReads(username, accountId(account)).forEach { path ->
            assertEquals(HttpStatus.OK, get(path, owner).statusCode, "owner must read $path")
            assertEquals(HttpStatus.FORBIDDEN, get(path, other).statusCode, "foreign owner must be denied $path")
            assertEquals(HttpStatus.UNAUTHORIZED, get(path, null).statusCode, "missing principal must be rejected for $path")
        }

        val jobResponse = startImport(owner, username, accountId(account))
        assertEquals(HttpStatus.ACCEPTED, jobResponse.statusCode)
        val jobPath = "/api/jobs/${objectMapper.readTree(jobResponse.body).path("jobId").asText()}"
        assertEquals(HttpStatus.OK, get(jobPath, owner).statusCode)
        assertEquals(HttpStatus.FORBIDDEN, get(jobPath, other).statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, get(jobPath, null).statusCode)
        assertEquals(HttpStatus.NOT_FOUND, get("/api/jobs/${UUID.randomUUID()}", owner).statusCode)
    }

    @Test
    fun `guest can read an unclaimed username but cannot claim an owned account`() {
        val owner = session("owner-${UUID.randomUUID()}")
        val other = session("other-${UUID.randomUUID()}")
        val username = "unclaimed-${UUID.randomUUID()}"

        privateReads(username).forEach { path ->
            assertNotEquals(HttpStatus.UNAUTHORIZED, get(path, null).statusCode, "guest read must remain public: $path")
        }

        assertEquals(HttpStatus.CREATED, claim(owner, username).statusCode)
        assertEquals(HttpStatus.CONFLICT, claim(other, username).statusCode)
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
