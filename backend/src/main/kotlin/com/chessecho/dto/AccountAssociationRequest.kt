package com.chessecho.dto

import jakarta.validation.constraints.NotBlank

/**
 * Raw strings are intentional here. Normalization happens before enum
 * conversion and before the case-insensitive account key is queried.
 */
data class AccountAssociationRequest(
    @field:NotBlank(message = "platform must not be blank")
    val platform: String = "",
    @field:NotBlank(message = "username must not be blank")
    val username: String = "",
) {
    fun normalizedPlatform(): String = platform.trim().uppercase()

    fun normalizedUsername(): String = username.trim()
}
