package com.chessecho.repository

import com.chessecho.domain.AppUser
import com.chessecho.domain.AuthSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Issue #113 (AC3, #79 D2) — the AuthSession row is keyed by an opaque token
 * hash, the hash column is unique, and the cleanup path removes revoked or
 * absolutely-expired rows while retaining live ones.
 *
 * Written test-first: AuthSession/AuthSessionRepository are planned but not yet
 * implemented, so this class is expected to be red until production exists.
 */
@DataJpaTest
@ActiveProfiles("test")
class AuthSessionRepositoryTest {
    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var authSessionRepository: AuthSessionRepository

    private val now: Instant = Instant.now()

    private fun user(email: String): AppUser = appUserRepository.save(AppUser(email = email))

    private fun session(
        owner: AppUser,
        tokenHash: String,
        idleExpiresAt: Instant = now.plus(30, ChronoUnit.MINUTES),
        absoluteExpiresAt: Instant = now.plus(30, ChronoUnit.DAYS),
        revokedAt: Instant? = null,
    ): AuthSession =
        AuthSession(
            tokenHash = tokenHash,
            user = owner,
            idleExpiresAt = idleExpiresAt,
            absoluteExpiresAt = absoluteExpiresAt,
            revokedAt = revokedAt,
        )

    @Test
    fun `findByTokenHash returns the matching session`() {
        val owner = user("live@example.com")
        authSessionRepository.save(session(owner, tokenHash = "hash-live"))

        val found = authSessionRepository.findByTokenHash("hash-live")

        assertNotNull(found)
        assertEquals(owner.id, found!!.user.id)
        assertNull(authSessionRepository.findByTokenHash("does-not-exist"))
    }

    @Test
    fun `token hash column is unique`() {
        val owner = user("unique@example.com")
        authSessionRepository.saveAndFlush(session(owner, tokenHash = "duplicate-hash"))

        assertThrows(DataIntegrityViolationException::class.java) {
            authSessionRepository.saveAndFlush(session(owner, tokenHash = "duplicate-hash"))
        }
    }

    @Test
    fun `cleanup removes revoked and absolutely-expired rows but retains live rows`() {
        val owner = user("cleanup@example.com")
        authSessionRepository.saveAndFlush(session(owner, tokenHash = "keep-live"))
        authSessionRepository.saveAndFlush(
            session(owner, tokenHash = "revoked", revokedAt = now.minus(1, ChronoUnit.MINUTES)),
        )
        authSessionRepository.saveAndFlush(
            session(owner, tokenHash = "absolute-expired", absoluteExpiresAt = now.minus(1, ChronoUnit.DAYS)),
        )

        authSessionRepository.deleteByRevokedAtIsNotNullOrAbsoluteExpiresAtBefore(now)

        assertNotNull(authSessionRepository.findByTokenHash("keep-live"))
        assertNull(authSessionRepository.findByTokenHash("revoked"))
        assertNull(authSessionRepository.findByTokenHash("absolute-expired"))
    }
}
