package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.time.Instant
import java.util.UUID

/**
 * The first-party password factor for an [AppUser]. Email remains authoritative
 * on AppUser; this table deliberately stores only the password verifier.
 */
@Entity
@Table(
    name = "local_credential",
    uniqueConstraints = [UniqueConstraint(name = "uk_local_credential_app_user", columnNames = ["app_user_id"])],
)
class LocalCredential(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    @OneToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    val user: AppUser,
    @Column(name = "password_hash", nullable = false, length = 255)
    val passwordHash: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
