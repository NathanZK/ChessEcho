package com.chessecho.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern

data class ImportGamesRequest(
    @field:NotBlank(message = "username must not be blank")
    val username: String = "",
    @field:NotBlank(message = "platform must not be blank")
    val platform: String = "",
    @field:NotEmpty(message = "at least one timeControl is required")
    val timeControls: List<
        @Pattern(
            regexp = "rapid|blitz|bullet|classical",
            message = "timeControl must be one of: rapid, blitz, bullet, classical",
        )
        String,
        > = emptyList(),
    @field:Pattern(
        regexp = "white|black|both",
        message = "playerColor must be one of: white, black, both",
    )
    @field:NotBlank(message = "playerColor must not be blank")
    val playerColor: String = "",
    @field:Pattern(
        regexp = "\\d{4}-\\d{2}",
        message = "fromDate must be in YYYY-MM format",
    )
    val fromDate: String? = null,
    @field:Pattern(
        regexp = "\\d{4}-\\d{2}",
        message = "toDate must be in YYYY-MM format",
    )
    val toDate: String? = null,
)
