package com.chessecho.dto

import java.util.UUID

data class ImportJobResponse(
    val jobId: UUID,
    val status: String,
)

data class JobStatusResponse(
    val jobId: UUID,
    val status: String,
    val gamesImported: Int,
    val gamesSkipped: Int,
    val errorMessage: String?,
)
