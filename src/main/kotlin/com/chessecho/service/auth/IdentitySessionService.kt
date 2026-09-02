package com.chessecho.service.auth

import com.chessecho.config.SessionCookieProperties
import com.chessecho.domain.AppUser
import com.chessecho.domain.AuthIdentity
import com.chessecho.domain.AuthSession
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.AuthIdentityRepository
import com.chessecho.repository.AuthSessionRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The provider-neutral identity/session core (#113 D1/D2).
 *
 * All mutating operations are transactional. In particular [rotateSession] runs
 * the revoke of the old row and the insert of the new row in one transaction, so
 * no committed state ever exposes two live sessions or zero live sessions for a
 * user (atomic swap on commit). Only the SHA-256 hash of an opaque secret is ever
 * persisted; the raw secret leaves this service exclusively for the cookie writer.
 */
@Service
class IdentitySessionService(
    private val appUserRepository: AppUserRepository,
    private val authIdentityRepository: AuthIdentityRepository,
    private val authSessionRepository: AuthSessionRepository,
    private val properties: SessionCookieProperties,
) {
    @Transactional
    fun establishSession(
        claims: VerifiedIdentityClaims,
        devPrincipal: Boolean,
    ): EstablishedSession {
        val identity = resolveOrProvisionIdentity(claims)
        return issueSession(identity.user, devPrincipal)
    }

    @Transactional
    fun resolveSession(rawSecret: String): AuthenticatedPrincipal? {
        val hash = SessionSecrets.sha256Hex(rawSecret)
        val session = authSessionRepository.findByTokenHash(hash) ?: return null
        val now = Instant.now()
        if (session.revokedAt != null || !now.isBefore(session.idleExpiresAt) || !now.isBefore(session.absoluteExpiresAt)) {
            return null
        }
        // Slide the idle window, capped by the absolute expiry.
        val slidTo = now.plus(properties.session.idleTimeout)
        session.idleExpiresAt = if (slidTo.isBefore(session.absoluteExpiresAt)) slidTo else session.absoluteExpiresAt
        session.lastSeenAt = now
        authSessionRepository.save(session)
        return AuthenticatedPrincipal(appUserId = session.user.id, devPrincipal = session.devPrincipal)
    }

    @Transactional
    fun rotateSession(rawSecret: String): EstablishedSession {
        val hash = SessionSecrets.sha256Hex(rawSecret)
        val existing =
            authSessionRepository.findByTokenHash(hash)
                ?: throw IllegalArgumentException("No session to rotate")
        if (existing.revokedAt == null) {
            existing.revokedAt = Instant.now()
            authSessionRepository.save(existing)
        }
        return issueSession(existing.user, existing.devPrincipal)
    }

    @Transactional
    fun revokeSession(rawSecret: String) {
        val hash = SessionSecrets.sha256Hex(rawSecret)
        val session = authSessionRepository.findByTokenHash(hash) ?: return
        if (session.revokedAt == null) {
            session.revokedAt = Instant.now()
            authSessionRepository.save(session)
        }
    }

    @Transactional
    @Scheduled(fixedDelayString = "\${chessecho.auth.session.cleanup-interval-ms:3600000}")
    fun cleanupExpiredSessions() {
        authSessionRepository.deleteByRevokedAtIsNotNullOrAbsoluteExpiresAtBefore(Instant.now())
    }

    private fun resolveOrProvisionIdentity(claims: VerifiedIdentityClaims): AuthIdentity {
        val existing = authIdentityRepository.findByIssuerAndSubject(claims.issuer, claims.subject)
        if (existing != null) {
            existing.emailSnapshot = claims.emailSnapshot
            existing.emailVerified = claims.emailVerified
            existing.lastSeenAt = Instant.now()
            return authIdentityRepository.save(existing)
        }
        val user = appUserRepository.save(AppUser(email = claims.emailSnapshot))
        val identity =
            AuthIdentity(
                user = user,
                issuer = claims.issuer,
                subject = claims.subject,
                emailSnapshot = claims.emailSnapshot,
                emailVerified = claims.emailVerified,
            )
        return authIdentityRepository.save(identity)
    }

    private fun issueSession(
        user: AppUser,
        devPrincipal: Boolean,
    ): EstablishedSession {
        val now = Instant.now()
        val idleExpiresAt = now.plus(properties.session.idleTimeout)
        val absoluteExpiresAt = now.plus(properties.session.absoluteTimeout)
        val rawSecret = SessionSecrets.generateSecret()
        val session =
            AuthSession(
                tokenHash = SessionSecrets.sha256Hex(rawSecret),
                user = user,
                devPrincipal = devPrincipal,
                idleExpiresAt = idleExpiresAt,
                absoluteExpiresAt = absoluteExpiresAt,
            )
        authSessionRepository.save(session)
        return EstablishedSession(
            rawSecret = rawSecret,
            idleExpiresAt = idleExpiresAt,
            absoluteExpiresAt = absoluteExpiresAt,
            principal = AuthenticatedPrincipal(appUserId = user.id, devPrincipal = devPrincipal),
        )
    }
}
