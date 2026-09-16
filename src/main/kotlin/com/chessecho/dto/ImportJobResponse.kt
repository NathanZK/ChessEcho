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
    val configurationState: String? = null,
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
    val configurationState: String? = null,
)
