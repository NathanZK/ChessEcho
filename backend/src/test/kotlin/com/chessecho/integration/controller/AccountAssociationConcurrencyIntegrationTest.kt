package com.chessecho.integration.controller

import com.chessecho.domain.ChessAccount
import com.chessecho.dto.AccountAssociationRequest
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.service.AccountConnectionLimitReachedException
import com.chessecho.service.AccountOwnershipService
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AccountAssociationConcurrencyIntegrationTest {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var ownership: AccountOwnershipService

    @Autowired
    private lateinit var accounts: ChessAccountRepository

    @Autowired
    private lateinit var transaction: TransactionTemplate

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var identitySessionService: IdentitySessionService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `two principals racing a case insensitive identity yield one account and two links`() {
        val username = "race-${UUID.randomUUID()}"
        val first = session("race-first-${UUID.randomUUID()}")
        val second = session("race-second-${UUID.randomUUID()}")

        val responses =
            concurrently(
                Callable { claim(first, username) },
                Callable { claim(second, username.uppercase()) },
            )

        assertEquals(1, responses.count { it.statusCode == HttpStatus.CREATED })
        assertEquals(1, responses.count { it.statusCode == HttpStatus.OK })
        val account = accounts.findByPlatformAndUsernameIgnoreCase("CHESS_COM", username)!!
        assertEquals(1, accounts.findByPlatformAndUsernameAll("CHESS_COM", username).size)
        assertEquals(
            2,
            jdbc.queryForObject("SELECT count(*) FROM account_connection WHERE chess_account_id = ?", Int::class.java, account.id),
        )
        responses.forEach { response ->
            val body = objectMapper.readTree(response.body)
            assertEquals(3, body.size())
            assertEquals(account.id.toString(), body["id"].asText())
            assertEquals(account.platform, body["platform"].asText())
            assertEquals(account.username, body["username"].asText())
        }
    }

    @Test
    fun `two principals racing an existing account both connect`() {
        val username = "imported-${UUID.randomUUID()}"
        accounts.saveAndFlush(ChessAccount(platform = "CHESS_COM", username = username))

        val first = session("claim-first-${UUID.randomUUID()}")
        val second = session("claim-second-${UUID.randomUUID()}")
        val responses =
            concurrently(
                Callable { claim(first, username) },
                Callable { claim(second, username) },
            )

        assertEquals(2, responses.count { it.statusCode == HttpStatus.OK })
    }

    @Test
    fun `same user repeat is idempotent and another user can connect`() {
        val username = "idempotent-${UUID.randomUUID()}"
        val owner = session("idempotent-owner-${UUID.randomUUID()}")
        val other = session("idempotent-other-${UUID.randomUUID()}")

        assertEquals(HttpStatus.CREATED, claim(owner, username).statusCode)
        val principal = identitySessionService.resolveSession(owner)!!
        val originalLink = jdbc.queryForMap("SELECT * FROM account_connection WHERE app_user_id = ?", principal.appUserId)
        assertEquals(HttpStatus.OK, claim(owner, username).statusCode)
        assertEquals(originalLink, jdbc.queryForMap("SELECT * FROM account_connection WHERE app_user_id = ?", principal.appUserId))
        assertEquals(HttpStatus.OK, claim(other, username).statusCode)
    }

    @Test
    fun `a second account is rejected without creating an orphan and the transaction remains usable`() {
        val secret = session("limit-${UUID.randomUUID()}")
        val username = "limit-${UUID.randomUUID()}"
        assertEquals(HttpStatus.CREATED, claim(secret, username).statusCode)
        val principal = identitySessionService.resolveSession(secret)!!
        val rejectedName = "rejected-${UUID.randomUUID()}"
        transaction.executeWithoutResult {
            assertFailsWith<AccountConnectionLimitReachedException> {
                ownership.associate(principal, AccountAssociationRequest("CHESS_COM", rejectedName))
            }
            assertEquals(1, ownership.listOwnedAccounts(principal).size)
        }
        assertEquals(null, accounts.findByPlatformAndUsernameIgnoreCase("CHESS_COM", rejectedName))
        val response = claim(secret, rejectedName)
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals("ACCOUNT_CONNECTION_LIMIT_REACHED", objectMapper.readTree(response.body)["error"].asText())
    }

    @Test
    fun `account and connection roll back together and committed retry is idempotent`() {
        val secret = session("rollback-${UUID.randomUUID()}")
        val principal = identitySessionService.resolveSession(secret)!!
        val username = "rollback-${UUID.randomUUID()}"
        transaction.executeWithoutResult { status ->
            ownership.associate(principal, AccountAssociationRequest("CHESS_COM", username))
            status.setRollbackOnly()
        }
        assertEquals(null, accounts.findByPlatformAndUsernameIgnoreCase("CHESS_COM", username))
        assertEquals(emptyList(), ownership.listOwnedAccounts(principal))
        assertEquals(HttpStatus.CREATED, claim(secret, username).statusCode)
        assertEquals(HttpStatus.OK, claim(secret, username).statusCode)
    }

    @Test
    fun `same user racing different accounts commits only one connection`() {
        val secret = session("single-${UUID.randomUUID()}")
        val responses =
            concurrently(
                Callable { claim(secret, "first-${UUID.randomUUID()}") },
                Callable { claim(secret, "second-${UUID.randomUUID()}") },
            )
        assertEquals(1, responses.count { it.statusCode == HttpStatus.CREATED })
        assertEquals(1, responses.count { it.statusCode == HttpStatus.CONFLICT })
        assertEquals(1, ownership.listOwnedAccounts(identitySessionService.resolveSession(secret)!!).size)
    }

    @Test
    fun `same user racing the same identity creates only one link`() {
        val secret = session("same-${UUID.randomUUID()}")
        val username = "same-${UUID.randomUUID()}"
        val responses =
            concurrently(
                Callable { claim(secret, username) },
                Callable { claim(secret, username.uppercase()) },
            )

        assertEquals(1, responses.count { it.statusCode == HttpStatus.CREATED })
        assertEquals(1, responses.count { it.statusCode == HttpStatus.OK })
        val principal = identitySessionService.resolveSession(secret)!!
        assertEquals(
            1,
            jdbc.queryForObject("SELECT count(*) FROM account_connection WHERE app_user_id = ?", Int::class.java, principal.appUserId),
        )
    }

    @Test
    fun `identity insert conflict waits for the winner and leaves the losing transaction usable`() {
        val first = identitySessionService.resolveSession(session("insert-first-${UUID.randomUUID()}"))!!
        val second = identitySessionService.resolveSession(session("insert-second-${UUID.randomUUID()}"))!!
        val username = "Insert-${UUID.randomUUID()}"
        val pool = Executors.newSingleThreadExecutor()
        try {
            val (original, future) =
                transaction.execute {
                    val original = ownership.associate(first, AccountAssociationRequest("  chess_com  ", " $username "))
                    val future =
                        pool.submit(
                            Callable {
                                transaction.execute {
                                    val result = ownership.associate(second, AccountAssociationRequest("CHESS_COM", username.uppercase()))
                                    assertEquals(1, ownership.listOwnedAccounts(second).size)
                                    result
                                }!!
                            },
                        )
                    awaitLock("INSERT INTO chess_account")
                    original to future
                }!!
            val reused = future.get(30, TimeUnit.SECONDS)
            assertTrue(original.created)
            assertEquals(false, reused.created)
            assertEquals(original.account, reused.account)
            assertEquals("CHESS_COM", reused.account.platform)
            assertEquals(username, reused.account.username)
            assertEquals(1, accounts.findByPlatformAndUsernameAll("CHESS_COM", username).size)
            assertEquals(
                2,
                jdbc.queryForObject(
                    "SELECT count(*) FROM account_connection WHERE chess_account_id = ?",
                    Int::class.java,
                    original.account.id,
                ),
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `replacement connect waits for disconnect commit then succeeds`() {
        val secret = session("disconnect-first-${UUID.randomUUID()}")
        val principal = identitySessionService.resolveSession(secret)!!
        val original = ownership.associate(principal, AccountAssociationRequest("CHESS_COM", "original-${UUID.randomUUID()}")).account
        val replacementName = "replacement-${UUID.randomUUID()}"
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future =
                transaction.execute {
                    ownership.disconnect(original.id, principal)
                    pool.submit(Callable { claim(secret, replacementName) }).also { awaitLock("app_user") }
                }!!
            assertEquals(HttpStatus.CREATED, future.get(30, TimeUnit.SECONDS).statusCode)
            assertEquals(replacementName, ownership.listOwnedAccounts(principal).single().username)
            assertTrue(accounts.existsById(original.id))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `replacement connect before disconnect commit is rejected without poisoning the transaction`() {
        val secret = session("connect-first-${UUID.randomUUID()}")
        val principal = identitySessionService.resolveSession(secret)!!
        val username = "original-${UUID.randomUUID()}"
        val original = ownership.associate(principal, AccountAssociationRequest("CHESS_COM", username)).account
        val replacementName = "replacement-${UUID.randomUUID()}"
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future =
                transaction.execute {
                    ownership.associate(principal, AccountAssociationRequest("CHESS_COM", username))
                    pool.submit(Callable { disconnect(secret, original.id) }).also {
                        awaitLock("app_user")
                        assertFailsWith<AccountConnectionLimitReachedException> {
                            ownership.associate(principal, AccountAssociationRequest("CHESS_COM", replacementName))
                        }
                        assertEquals(original.id, ownership.listOwnedAccounts(principal).single().id)
                    }
                }!!
            assertEquals(HttpStatus.NO_CONTENT, future.get(30, TimeUnit.SECONDS).statusCode)
            assertEquals(emptyList(), ownership.listOwnedAccounts(principal))
            assertEquals(null, accounts.findByPlatformAndUsernameIgnoreCase("CHESS_COM", replacementName))
            assertEquals(HttpStatus.NOT_FOUND, disconnect(secret, original.id).statusCode)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun awaitLock(queryFragment: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()")
            val waiting =
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() " +
                        "AND wait_event_type = 'Lock' AND query LIKE ?",
                    Int::class.java,
                    "%$queryFragment%",
                )!!
            if (waiting > 0) return
            Thread.sleep(20)
        }
        throw AssertionError("Concurrent account operation did not wait on $queryFragment")
    }

    private fun disconnect(
        secret: String,
        accountId: UUID,
    ): ResponseEntity<String> {
        val headers = HttpHeaders()
        headers.add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=$secret; XSRF-TOKEN=claim-csrf")
        headers.add("X-XSRF-TOKEN", "claim-csrf")
        return restTemplate.exchange(
            "/api/accounts/$accountId/connection",
            HttpMethod.DELETE,
            HttpEntity<Unit>(headers),
            String::class.java,
        )
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

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer<Nothing> = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
