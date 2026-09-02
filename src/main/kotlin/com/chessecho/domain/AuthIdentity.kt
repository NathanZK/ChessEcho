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
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.time.Instant
import java.util.UUID

/**
 * A provider-neutral external identity, uniquely resolved by `(issuer, subject)`
 * and linked to the immutable internal [AppUser]. An email snapshot is optional
 * metadata only: it never keys the identity and never triggers a merge (#113 D1).
 */
@Entity
@Table(
    name = "auth_identity",
    uniqueConstraints = [UniqueConstraint(name = "uk_auth_identity_issuer_subject", columnNames = ["issuer", "subject"])],
)
class AuthIdentity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    val user: AppUser,
    @Column(name = "issuer", nullable = false)
    val issuer: String,
    @Column(name = "subject", nullable = false)
    val subject: String,
    @Column(name = "email_snapshot")
    var emailSnapshot: String? = null,
    @Column(name = "email_verified")
    var emailVerified: Boolean? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = Instant.now(),
)
