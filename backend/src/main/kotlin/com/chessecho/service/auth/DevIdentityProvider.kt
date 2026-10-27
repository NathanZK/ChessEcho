package com.chessecho.service.auth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * The development-only identity adapter. It supplies fixed, provider-neutral
 * claims (`issuer="dev-local"`, `subject="local-dev"`) with no email metadata,
 * proving the boundary does not depend on any production provider.
 *
 * It is a fail-closed allowlist bean: it exists only under the `{dev, local}`
 * profile allowlist AND when `chessecho.auth.dev-mode.enabled=true` (default off),
 * so the default/production profile can never expose it.
 */
@Component
@Profile("dev", "local")
@ConditionalOnProperty(prefix = "chessecho.auth.dev-mode", name = ["enabled"], havingValue = "true")
class DevIdentityProvider : IdentityProviderAdapter {
    override fun claims(request: HttpServletRequest): VerifiedIdentityClaims =
        VerifiedIdentityClaims(
            issuer = DEV_ISSUER,
            subject = DEV_SUBJECT,
            emailSnapshot = null,
            emailVerified = null,
        )

    companion object {
        const val DEV_ISSUER = "dev-local"
        const val DEV_SUBJECT = "local-dev"
    }
}
