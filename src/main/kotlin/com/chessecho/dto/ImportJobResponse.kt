package com.chessecho.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ImportJobResponse(
    val jobId: UUID,
    val status: String,
    val accountId: UUID? = null,
    val platform: String? = null,
    val username: String? = null,
    val fromDate: String? = null,
    val toDate: String? = null,
    val timeControls: List<String>? = null,
    val playerColor: String? = null,
    val multiPv: Int? = null,
    val configurationState: String? = null,
    /**
     * Derived position/occurrence processing status for the archives this job
     * requested, or absent when the request covers no durable archive unit yet
     * (for example a current-month-only import, whose derived outcome is carried
     * by `status`). See `GameImportController.derivedStatusFor`.
     */
    val derivedStatus: String? = null,
    val maxEligibleGames: Int? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JobStatusResponse(
    val jobId: UUID,
    val status: String,
    val gamesImported: Int,
    val gamesSkipped: Int,
    val gamesProcessed: Int,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val errorMessage: String?,
    val analysisStatus: String,
    val accountId: UUID? = null,
    val platform: String? = null,
    val username: String? = null,
    val fromDate: String? = null,
    val toDate: String? = null,
    val timeControls: List<String>? = null,
    val playerColor: String? = null,
    val multiPv: Int? = null,
    val configurationState: String? = null,
    /**
     * Derived position/occurrence processing status for the archives this job
     * requested, or absent when the request covers no durable archive unit yet
     * (for example a current-month-only import, whose derived outcome is carried
     * by `status`). See `GameImportController.derivedStatusFor`.
     */
    val derivedStatus: String? = null,
    /**
     * The optional per-import eligible-game cap and how much of it this job has
     * spent so far. Both are omitted when the job has no cap, so that jobs
     * created before/without this feature keep their exact existing response
     * shape.
     */
    val maxEligibleGames: Int? = null,
    val eligibleGamesSelected: Int? = null,
)
