package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.time.Instant
import java.util.UUID

/**
 * An opaque server-side session. Only the SHA-256 hash of the opaque secret is
 * ever persisted; the raw secret lives solely in the `HttpOnly` cookie. Lifecycle
 * is enforced by idle + absolute expiry and explicit revocation (#113 D2).
 */
@Entity
@Table(name = "auth_session")
class AuthSession(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    val tokenHash: String,
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    val user: AppUser,
    @Column(name = "dev_principal", nullable = false)
    val devPrincipal: Boolean = false,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = Instant.now(),
    @Column(name = "idle_expires_at", nullable = false)
    var idleExpiresAt: Instant,
    @Column(name = "absolute_expires_at", nullable = false)
    var absoluteExpiresAt: Instant,
    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
)
