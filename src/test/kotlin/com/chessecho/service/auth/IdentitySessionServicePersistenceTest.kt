package com.chessecho.service.auth

import com.chessecho.config.SessionCookieProperties
import com.chessecho.repository.AuthSessionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.security.MessageDigest
import java.time.Instant

/**
 * Issue #113 (AC3, #79 D2) — end-to-end identity/session persistence against H2.
 * Proves that only the token hash is stored, that a live secret resolves, and
 * that rotation is an atomic revoke-old + insert-new swap leaving exactly one
 * live session for the user (no committed window with two or zero live rows).
 *
 * Written test-first: it wires the planned IdentitySessionService into a
 * @DataJpaTest slice and is expected to be red until production exists.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(IdentitySessionServicePersistenceTest.TestBeans::class, IdentitySessionService::class)
class IdentitySessionServicePersistenceTest {
    @TestConfiguration
    class TestBeans {
        @Bean
        fun sessionCookieProperties(): SessionCookieProperties = SessionCookieProperties()
    }

    @Autowired
    private lateinit var service: IdentitySessionService

    @Autowired
    private lateinit var authSessionRepository: AuthSessionRepository

    private val devClaims = VerifiedIdentityClaims(issuer = "dev-local", subject = "local-dev")

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun liveSessionsForUser(userId: java.util.UUID): Int {
        val now = Instant.now()
        return authSessionRepository.findAll().count {
            it.user.id == userId &&
                it.revokedAt == null &&
                it.idleExpiresAt.isAfter(now) &&
                it.absoluteExpiresAt.isAfter(now)
        }
    }

    @Test
    fun `establishSession stores only the hash and resolves the live secret`() {
        val established = service.establishSession(devClaims, devPrincipal = true)

        val stored = authSessionRepository.findByTokenHash(sha256Hex(established.rawSecret))
        assertNotNull(stored)
        assertNotEquals(established.rawSecret, stored!!.tokenHash)

        val principal = service.resolveSession(established.rawSecret)
        assertNotNull(principal)
        assertEquals(established.principal.appUserId, principal!!.appUserId)
    }

    @Test
    fun `rotateSession leaves exactly one live session and invalidates the old secret`() {
        val established = service.establishSession(devClaims, devPrincipal = true)
        val userId = established.principal.appUserId

        val rotated = service.rotateSession(established.rawSecret)

        assertNotEquals(established.rawSecret, rotated.rawSecret)
        assertNull(service.resolveSession(established.rawSecret))
        assertNotNull(service.resolveSession(rotated.rawSecret))
        assertEquals(1, liveSessionsForUser(userId))
    }

    @Test
    fun `revokeSession blocks subsequent resolution`() {
        val established = service.establishSession(devClaims, devPrincipal = true)

        service.revokeSession(established.rawSecret)

        assertNull(service.resolveSession(established.rawSecret))
    }
}
