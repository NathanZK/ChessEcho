package com.chessprobe.dto

data class ErrorResponse(
    val error: String,
    val details: List<String>,
)
