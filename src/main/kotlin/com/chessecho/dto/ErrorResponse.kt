package com.chessecho.dto

data class ErrorResponse(
    val error: String,
    val details: List<String>,
)
