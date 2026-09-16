package com.chessecho.integration.controller

import com.chessecho.service.auth.IdentitySessionService
import com.chessecho.service.auth.VerifiedIdentityClaims
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * The account association key is `(platform, lower(username))`. Creation and
 * claim must be one atomic operation: two owners cannot both observe an absent
 * row and a retry by the winning owner must be idempotent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AccountAssociationConcurrencyIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var identitySessionService: IdentitySessionService

    @Test
    fun `two principals racing an absent account yield one creation and one conflict`() {
        val username = "race-${UUID.randomUUID()}"
        val first = session("race-first-${UUID.randomUUID()}")
        val second = session("race-second-${UUID.randomUUID()}")

        val responses =
            concurrently(
                Callable { claim(first, username) },
                Callable { claim(second, username) },
            )

        assertEquals(1, responses.count { it.statusCode == HttpStatus.CREATED })
        assertEquals(1, responses.count { it.statusCode == HttpStatus.CONFLICT })
    }

    @Test
    fun `two principals racing an existing unclaimed account yield one claim and one conflict`() {
        val username = "imported-${UUID.randomUUID()}"
        val seedHeaders = HttpHeaders()
        seedHeaders.add(HttpHeaders.COOKIE, "XSRF-TOKEN=seed-csrf")
        seedHeaders.add("X-XSRF-TOKEN", "seed-csrf")
        seedHeaders.contentType = org.springframework.http.MediaType.APPLICATION_JSON
        val seeded =
            restTemplate.exchange(
                "/api/games/import",
                HttpMethod.POST,
                HttpEntity(
                    """{"platform":"CHESS_COM","username":"$username","timeControls":["BLITZ"],"playerColor":"BOTH"}""",
                    seedHeaders,
                ),
                String::class.java,
            )
        assertEquals(HttpStatus.ACCEPTED, seeded.statusCode)

        val first = session("claim-first-${UUID.randomUUID()}")
        val second = session("claim-second-${UUID.randomUUID()}")
        val responses =
            concurrently(
                Callable { claim(first, username) },
                Callable { claim(second, username) },
            )

        assertEquals(1, responses.count { it.statusCode == HttpStatus.OK })
        assertEquals(1, responses.count { it.statusCode == HttpStatus.CONFLICT })
    }

    @Test
    fun `winning owner can repeat a claim idempotently while another owner receives 409`() {
        val username = "idempotent-${UUID.randomUUID()}"
        val owner = session("idempotent-owner-${UUID.randomUUID()}")
        val other = session("idempotent-other-${UUID.randomUUID()}")

        assertEquals(HttpStatus.CREATED, claim(owner, username).statusCode)
        assertEquals(HttpStatus.OK, claim(owner, username).statusCode)
        assertEquals(HttpStatus.CONFLICT, claim(other, username).statusCode)
    }

    private fun concurrently(vararg tasks: Callable<ResponseEntity<String>>): List<ResponseEntity<String>> {
        val pool = Executors.newFixedThreadPool(tasks.size)
        return try {
            val start = java.util.concurrent.CountDownLatch(1)
            val futures =
                tasks.map { task ->
                    pool.submit(
                        Callable {
                            start.await(10, TimeUnit.SECONDS)
                            task.call()
                        },
                    )
                }
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun claim(
        secret: String,
        username: String,
    ): ResponseEntity<String> {
        val headers = HttpHeaders()
        headers.add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=$secret; XSRF-TOKEN=claim-csrf")
        headers.add("X-XSRF-TOKEN", "claim-csrf")
        headers.contentType = org.springframework.http.MediaType.APPLICATION_JSON
        return restTemplate.exchange(
            "/api/accounts",
            HttpMethod.POST,
            HttpEntity("""{"platform":"CHESS_COM","username":"$username"}""", headers),
            String::class.java,
        )
    }

    private fun session(subject: String): String =
        identitySessionService
            .establishSession(
                VerifiedIdentityClaims("integration-test", subject, "$subject@example.test", true),
                devPrincipal = false,
            ).rawSecret
}
