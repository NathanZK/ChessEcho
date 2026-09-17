package com.chessecho.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegistrationRequest(
    @field:NotBlank(message = "email must not be blank")
    val email: String = "",
    @field:NotBlank(message = "password must not be blank")
    @field:Size(min = 8, max = 200, message = "password must be between 8 and 200 characters")
    val password: String = "",
)
