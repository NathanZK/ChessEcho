package com.chessecho.service.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Opaque session-secret generation and hashing. The raw secret is a 256-bit
 * value from [SecureRandom], base64url-encoded for cookie transport; only its
 * SHA-256 hex digest is ever persisted.
 */
object SessionSecrets {
    private const val SECRET_BYTES = 32
    private val random = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun generateSecret(): String {
        val bytes = ByteArray(SECRET_BYTES)
        random.nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
