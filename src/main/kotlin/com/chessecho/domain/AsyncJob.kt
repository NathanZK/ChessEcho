package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

/**
 * A queued import is a durable command, not a projection of a later request.
 * Account and import options are captured once and are never replaced by the
 * caller while the worker is running.
 */
@Entity
@Table(name = "async_job")
class AsyncJob(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chess_account_id")
    val chessAccount: ChessAccount? = null,
    @Column(nullable = false)
    val username: String,
    @Column(nullable = false)
    val platform: String,
    @Column(nullable = false)
    var status: String = "QUEUED",
    @Column(name = "games_imported", nullable = false)
    var gamesImported: Int = 0,
    @Column(name = "games_skipped", nullable = false)
    var gamesSkipped: Int = 0,
    @Column(name = "games_processed", nullable = false)
    var gamesProcessed: Int = 0,
    @Column(name = "analysis_status", nullable = false)
    var analysisStatus: String = "NOT_STARTED",
    @Column(name = "error_message")
    var errorMessage: String? = null,
    @Column(name = "from_date")
    var fromDate: String? = null,
    @Column(name = "to_date")
    var toDate: String? = null,
    @Column(name = "time_controls_csv")
    var timeControlsCsv: String? = null,
    @Column(name = "player_color")
    var playerColor: String? = null,
    @Column(name = "configuration_state", nullable = false)
    var configurationState: String = CONFIGURATION_UNRESOLVED,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @Transient
    private var persistedConfiguration: ConfigurationSnapshot? = null

    @PrePersist
    fun validateBeforeInsert() {
        validateConfiguration()
        persistedConfiguration = currentConfiguration()
    }

    @PreUpdate
    fun validateBeforeUpdate() {
        val previous = persistedConfiguration
        if (previous != null && previous != currentConfiguration()) {
            throw IllegalStateException("Async job configuration is immutable after insertion")
        }
        if (!(status == "FAILED" && errorMessage == "INVALID_READY_JOB_CONFIGURATION")) {
            validateConfiguration()
        }
        persistedConfiguration = currentConfiguration()
    }

    @PostLoad
    @PostPersist
    fun capturePersistedConfiguration() {
        persistedConfiguration = currentConfiguration()
    }

    /**
     * READY is the only state a worker may execute. UNRESOLVED is retained as a
     * fail-closed state for malformed/legacy rows and cannot authorize reads.
     */
    fun validateConfiguration() {
        require(configurationState == CONFIGURATION_READY || configurationState == CONFIGURATION_UNRESOLVED) {
            "configurationState must be READY or UNRESOLVED"
        }
        require(status in ALLOWED_STATUSES) { "status must be one of $ALLOWED_STATUSES" }
        require(platform == Platform.CHESS_COM.name) { "platform must be CHESS_COM" }
        validateDate(fromDate, "fromDate")
        validateDate(toDate, "toDate")
        if (fromDate != null && toDate != null) {
            require(fromDate!! <= toDate!!) { "fromDate must not be after toDate" }
        }
        if (configurationState == CONFIGURATION_READY) {
            require(chessAccount != null) { "READY jobs require a chess account" }
            require(username == username.trim() && username.isNotBlank()) { "READY jobs require a canonical username" }
            require(playerColor in ALLOWED_PLAYER_COLORS) { "READY jobs require a valid player color" }
            require(!timeControlsCsv.isNullOrBlank()) { "READY jobs require time controls" }
            val values = timeControlsCsv!!.split(',')
            require(
                values.distinct().size == values.size &&
                    values == values.sorted() &&
                    values.all { it in ALLOWED_TIME_CONTROLS },
            ) {
                "READY jobs require canonical time controls"
            }
        }
    }

    fun isReady(): Boolean = configurationState == CONFIGURATION_READY

    fun isUnresolved(): Boolean = configurationState == CONFIGURATION_UNRESOLVED

    private fun validateDate(
        value: String?,
        field: String,
    ) {
        if (value == null) return
        require(value.matches(DATE_PATTERN)) { "$field must be in YYYY-MM format" }
        YearMonth.parse(value)
    }

    private fun currentConfiguration(): ConfigurationSnapshot =
        ConfigurationSnapshot(
            chessAccountId = chessAccount?.id,
            username = username,
            platform = platform,
            fromDate = fromDate,
            toDate = toDate,
            timeControlsCsv = timeControlsCsv,
            playerColor = playerColor,
            configurationState = configurationState,
        )

    private data class ConfigurationSnapshot(
        val chessAccountId: UUID?,
        val username: String,
        val platform: String,
        val fromDate: String?,
        val toDate: String?,
        val timeControlsCsv: String?,
        val playerColor: String?,
        val configurationState: String,
    )

    companion object {
        const val CONFIGURATION_READY = "READY"
        const val CONFIGURATION_UNRESOLVED = "UNRESOLVED"
        val ALLOWED_STATUSES = setOf("QUEUED", "PROCESSING", "COMPLETED", "FAILED")
        val ALLOWED_PLAYER_COLORS = setOf("WHITE", "BLACK", "BOTH")
        val ALLOWED_TIME_CONTROLS = setOf("BLITZ", "RAPID", "BULLET", "CLASSICAL")
        private val DATE_PATTERN = Regex("\\d{4}-(0[1-9]|1[0-2])")
    }
}
