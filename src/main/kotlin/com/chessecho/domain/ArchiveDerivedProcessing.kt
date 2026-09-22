package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.time.Instant
import java.util.UUID

enum class ArchiveDerivedStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
}

@Entity
@Table(name = "archive_derived_processing")
class ArchiveDerivedProcessing(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "imported_archive_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    val importedArchive: ImportedArchive,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ArchiveDerivedStatus = ArchiveDerivedStatus.PENDING,
    @Column(nullable = false)
    var attemptCount: Int = 0,
    @Column(columnDefinition = "TEXT")
    var lastError: String? = null,
    @Column(name = "worker_token")
    var workerToken: UUID? = null,
    var startedAt: Instant? = null,
    @Column(name = "lease_expires_at")
    var leaseExpiresAt: Instant? = null,
    var completedAt: Instant? = null,
    @Column(nullable = false)
    var updatedAt: Instant = Instant.now(),
)
