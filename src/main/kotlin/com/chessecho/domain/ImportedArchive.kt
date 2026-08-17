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
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "imported_archive")
class ImportedArchive(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chess_account_id", nullable = false)
    val chessAccount: ChessAccount,
    @Column(name = "archive_url", nullable = false)
    val archiveUrl: String,
    @Column(name = "year_month", nullable = false)
    val yearMonth: String,
    @Column(name = "game_count", nullable = false)
    val gameCount: Int = 0,
    @Column(name = "imported_at", nullable = false)
    val importedAt: Instant = Instant.now(),
)
