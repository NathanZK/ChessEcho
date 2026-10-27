package com.chessecho.service.auth

/**
 * Already-validated external identity claims consumed at the provider-adapter
 * boundary. The upstream adapter is responsible for verifying the token/claims;
 * this value type carries only the provider-neutral fields the session core needs.
 */
data class VerifiedIdentityClaims(
    val issuer: String,
    val subject: String,
    val emailSnapshot: String? = null,
    val emailVerified: Boolean? = null,
)
