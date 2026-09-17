package com.chessecho.dto

import jakarta.validation.constraints.NotBlank

data class LoginRequest(
    @field:NotBlank(message = "email must not be blank")
    val email: String = "",
    @field:NotBlank(message = "password must not be blank")
    val password: String = "",
)
