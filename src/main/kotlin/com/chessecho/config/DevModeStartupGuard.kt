package com.chessecho.config

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Always-registered fail-closed guard for the development-only principal. If
 * `chessecho.auth.dev-mode.enabled=true` while none of the allowlisted development
 * profiles `{dev, local}` is active (including the default/production profile), it
 * aborts boot. Because it is unconditional, it also fires under the default
 * profile — the `@Profile` gate on the dev beans and this guard together make the
 * dev path a true fail-closed allowlist (#79 D7).
 */
@Component
class DevModeStartupGuard(
    private val environment: Environment,
    private val properties: SessionCookieProperties,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments?) {
        if (!properties.devMode.enabled) {
            return
        }
        val active = environment.activeProfiles.toSet()
        val allowlisted = ALLOWLISTED_PROFILES.any { it in active }
        if (!allowlisted) {
            throw IllegalStateException(
                "chessecho.auth.dev-mode.enabled=true is only permitted under a development profile " +
                    "$ALLOWLISTED_PROFILES; active profiles=$active. Refusing to start.",
            )
        }
    }

    companion object {
        private val ALLOWLISTED_PROFILES = setOf("dev", "local")
    }
}
