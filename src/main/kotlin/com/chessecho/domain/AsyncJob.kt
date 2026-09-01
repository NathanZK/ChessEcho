package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "async_job")
class AsyncJob(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    val username: String,
    @Column(nullable = false)
    val platform: String,
    @Column(nullable = false)
    var status: String = "QUEUED",
    @Column(name = "games_imported")
    var gamesImported: Int = 0,
    @Column(name = "games_skipped")
    var gamesSkipped: Int = 0,
    @Column(name = "games_processed")
    var gamesProcessed: Int = 0,
    @Column(name = "games_filtered_out")
    var gamesFilteredOut: Int = 0,
    @Column(name = "analysis_status", nullable = false)
    var analysisStatus: String = "NOT_STARTED",
    @Column(name = "error_message")
    var errorMessage: String? = null,
    @Column(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now(),
)
