package com.chessecho.service.auth

import com.chessecho.config.SessionCookieProperties
import com.chessecho.domain.AppUser
import com.chessecho.domain.AuthIdentity
import com.chessecho.domain.AuthSession
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.AuthIdentityRepository
import com.chessecho.repository.AuthSessionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Issue #113 (AC1/AC2/AC3, #79 D1/D2) — provider-neutral identity/session core.
 * Verifies that a session persists only the SHA-256 hash of an opaque secret,
 * that resolution enforces revocation and idle/absolute expiry (and slides the
 * idle window), that rotation atomically revokes the old row and issues a new
 * one, and that revocation is idempotent and blocks further resolution.
 *
 * Written test-first against the planned IdentitySessionService surface, so it
 * is expected to be red until that production code exists.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdentitySessionServiceTest {
    @Mock
    private lateinit var appUserRepository: AppUserRepository

    @Mock
    private lateinit var authIdentityRepository: AuthIdentityRepository

    @Mock
    private lateinit var authSessionRepository: AuthSessionRepository

    private lateinit var service: IdentitySessionService

    private val properties = SessionCookieProperties()

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @BeforeEach
    fun setUp() {
        service = IdentitySessionService(appUserRepository, authIdentityRepository, authSessionRepository, properties)
        whenever(appUserRepository.save(any<AppUser>())).thenAnswer { it.getArgument(0) }
        whenever(authIdentityRepository.save(any<AuthIdentity>())).thenAnswer { it.getArgument(0) }
        whenever(authSessionRepository.save(any<AuthSession>())).thenAnswer { it.getArgument(0) }
    }

    private fun existingIdentity(): Pair<AppUser, AuthIdentity> {
        val user = AppUser(email = "existing@example.com")
        val identity = AuthIdentity(user = user, issuer = "dev-local", subject = "local-dev")
        whenever(authIdentityRepository.findByIssuerAndSubject("dev-local", "local-dev")).thenReturn(identity)
        return user to identity
    }

    @Test
    fun `establishSession persists only the hash of the opaque secret`() {
        val (user, _) = existingIdentity()

        val established =
            service.establishSession(
                VerifiedIdentityClaims(issuer = "dev-local", subject = "local-dev"),
                devPrincipal = true,
            )

        val captor = argumentCaptor<AuthSession>()
        verify(authSessionRepository).save(captor.capture())
        val saved = captor.firstValue

        assertNotEquals(established.rawSecret, saved.tokenHash)
        assertEquals(sha256Hex(established.rawSecret), saved.tokenHash)
        assertEquals(user.id, established.principal.appUserId)
        assertTrue(established.principal.devPrincipal)
        assertTrue(established.idleExpiresAt.isAfter(Instant.now()))
        assertTrue(established.absoluteExpiresAt.isAfter(established.idleExpiresAt))
    }

    @Test
    fun `establishSession provisions a new identity and owner when none exists`() {
        whenever(authIdentityRepository.findByIssuerAndSubject("google", "sub-new")).thenReturn(null)

        service.establishSession(
            VerifiedIdentityClaims(issuer = "google", subject = "sub-new", emailSnapshot = "new@example.com"),
            devPrincipal = false,
        )

        verify(appUserRepository).save(any<AppUser>())
        val identityCaptor = argumentCaptor<AuthIdentity>()
        verify(authIdentityRepository).save(identityCaptor.capture())
        val identity = identityCaptor.firstValue
        assertEquals("google", identity.issuer)
        assertEquals("sub-new", identity.subject)
        assertEquals("new@example.com", identity.emailSnapshot)
    }

    @Test
    fun `resolveSession accepts a live session and slides the idle window`() {
        val (user, _) = existingIdentity()
        val raw = "raw-secret-live"
        val originalIdle = Instant.now().plus(5, ChronoUnit.MINUTES)
        val liveSession =
            AuthSession(
                tokenHash = sha256Hex(raw),
                user = user,
                idleExpiresAt = originalIdle,
                absoluteExpiresAt = Instant.now().plus(1, ChronoUnit.DAYS),
            )
        whenever(authSessionRepository.findByTokenHash(sha256Hex(raw))).thenReturn(liveSession)

        val principal = service.resolveSession(raw)

        assertNotNull(principal)
        assertEquals(user.id, principal!!.appUserId)
        val captor = argumentCaptor<AuthSession>()
        verify(authSessionRepository).save(captor.capture())
        assertTrue(captor.firstValue.idleExpiresAt.isAfter(originalIdle))
    }

    @Test
    fun `resolveSession rejects a revoked session without sliding`() {
        val (user, _) = existingIdentity()
        val raw = "raw-secret-revoked"
        val revoked =
            AuthSession(
                tokenHash = sha256Hex(raw),
                user = user,
                idleExpiresAt = Instant.now().plus(5, ChronoUnit.MINUTES),
                absoluteExpiresAt = Instant.now().plus(1, ChronoUnit.DAYS),
                revokedAt = Instant.now().minus(1, ChronoUnit.MINUTES),
            )
        whenever(authSessionRepository.findByTokenHash(sha256Hex(raw))).thenReturn(revoked)

        assertNull(service.resolveSession(raw))
        verify(authSessionRepository, never()).save(any<AuthSession>())
    }

    @Test
    fun `resolveSession rejects an idle-expired session`() {
        val (user, _) = existingIdentity()
        val raw = "raw-secret-idle"
        val idleExpired =
            AuthSession(
                tokenHash = sha256Hex(raw),
                user = user,
                idleExpiresAt = Instant.now().minus(1, ChronoUnit.SECONDS),
                absoluteExpiresAt = Instant.now().plus(1, ChronoUnit.DAYS),
            )
        whenever(authSessionRepository.findByTokenHash(sha256Hex(raw))).thenReturn(idleExpired)

        assertNull(service.resolveSession(raw))
    }

    @Test
    fun `resolveSession rejects an absolutely-expired session`() {
        val (user, _) = existingIdentity()
        val raw = "raw-secret-absolute"
        val absoluteExpired =
            AuthSession(
                tokenHash = sha256Hex(raw),
                user = user,
                idleExpiresAt = Instant.now().plus(5, ChronoUnit.MINUTES),
                absoluteExpiresAt = Instant.now().minus(1, ChronoUnit.SECONDS),
            )
        whenever(authSessionRepository.findByTokenHash(sha256Hex(raw))).thenReturn(absoluteExpired)

        assertNull(service.resolveSession(raw))
    }

    @Test
    fun `resolveSession returns null for an unknown secret`() {
        whenever(authSessionRepository.findByTokenHash(any())).thenReturn(null)

        assertNull(service.resolveSession("unknown-secret"))
    }

    @Test
    fun `rotateSession revokes the old row and issues a new session`() {
        val (user, _) = existingIdentity()
        val oldRaw = "old-secret"
        val oldSession =
            AuthSession(
                tokenHash = sha256Hex(oldRaw),
                user = user,
                idleExpiresAt = Instant.now().plus(5, ChronoUnit.MINUTES),
                absoluteExpiresAt = Instant.now().plus(1, ChronoUnit.DAYS),
            )
        whenever(authSessionRepository.findByTokenHash(sha256Hex(oldRaw))).thenReturn(oldSession)

        val rotated = service.rotateSession(oldRaw)

        assertNotEquals(oldRaw, rotated.rawSecret)
        val captor = argumentCaptor<AuthSession>()
        verify(authSessionRepository, times(2)).save(captor.capture())
        val revokedOld = captor.allValues.first { it.tokenHash == sha256Hex(oldRaw) }
        val issuedNew = captor.allValues.first { it.tokenHash == sha256Hex(rotated.rawSecret) }
        assertNotNull(revokedOld.revokedAt)
        assertNull(issuedNew.revokedAt)
        assertEquals(user.id, issuedNew.user.id)
    }

    @Test
    fun `revokeSession sets the revoked timestamp and is idempotent`() {
        val (user, _) = existingIdentity()
        val raw = "revoke-secret"
        val session =
            AuthSession(
                tokenHash = sha256Hex(raw),
                user = user,
                idleExpiresAt = Instant.now().plus(5, ChronoUnit.MINUTES),
                absoluteExpiresAt = Instant.now().plus(1, ChronoUnit.DAYS),
            )
        whenever(authSessionRepository.findByTokenHash(sha256Hex(raw))).thenReturn(session)

        service.revokeSession(raw)
        val captor = argumentCaptor<AuthSession>()
        verify(authSessionRepository).save(captor.capture())
        assertNotNull(captor.firstValue.revokedAt)

        // A second logout for the same secret must not throw or clear the timestamp.
        service.revokeSession(raw)
        assertNotNull(session.revokedAt)
    }

    @Test
    fun `revokeSession is a no-op for an unknown secret`() {
        whenever(authSessionRepository.findByTokenHash(any())).thenReturn(null)

        service.revokeSession("unknown-secret")

        verify(authSessionRepository, never()).save(any<AuthSession>())
    }
}
