package com.chessecho.config

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Always-registered fail-closed guard for cookie transport security.
 *
 * If `chessecho.auth.cookie.secure=false` while none of the allowlisted development
 * or test profiles `{dev, local, test}` is active (including the default/production
 * profile), it aborts boot. Because it is unconditional, it also fires under the
 * default profile to prevent production deployments from silently failing open to
 * cleartext cookie transmission (#615).
 */
@Component
class CookieSecurityStartupGuard(
    private val environment: Environment,
    private val properties: SessionCookieProperties,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments?) {
        if (properties.cookie.secure) {
            return
        }
        val active = environment.activeProfiles.toSet()
        val allowlisted = ALLOWLISTED_PROFILES.any { it in active }
        if (!allowlisted) {
            throw IllegalStateException(
                "chessecho.auth.cookie.secure=false is only permitted under development/test profiles " +
                    "$ALLOWLISTED_PROFILES; active profiles=$active. Refusing to start.",
            )
        }
    }

    companion object {
        private val ALLOWLISTED_PROFILES = listOf("dev", "local", "test")
    }
}
