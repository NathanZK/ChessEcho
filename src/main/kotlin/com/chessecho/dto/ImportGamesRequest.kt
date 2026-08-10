package com.chessecho.dto

import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.TimeControl
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern

data class ImportGamesRequest(
    @field:NotBlank(message = "username must not be blank")
    val username: String = "",
    @field:NotNull(message = "platform must not be null")
    val platform: Platform = Platform.CHESS_COM,
    @field:NotEmpty(message = "at least one timeControl is required")
    val timeControls: List<TimeControl> = emptyList(),
    @field:NotNull(message = "playerColor must not be null")
    val playerColor: PlayerColor = PlayerColor.BOTH,
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
