package com.chessecho.service.auth

import jakarta.servlet.http.HttpServletRequest

/**
 * The provider-adapter boundary. An implementation turns an inbound request into
 * already-validated, provider-neutral [VerifiedIdentityClaims]. No production
 * provider is selected or wired in this slice; [DevIdentityProvider] is the only
 * concrete adapter and it exists solely inside the development allowlist.
 */
interface IdentityProviderAdapter {
    fun claims(request: HttpServletRequest): VerifiedIdentityClaims
}
