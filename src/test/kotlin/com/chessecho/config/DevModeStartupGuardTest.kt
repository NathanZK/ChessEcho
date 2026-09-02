package com.chessecho.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.ApplicationArguments
import org.springframework.core.env.Environment

/**
 * Issue #113 (AC9, #79 D7) — the always-registered DevModeStartupGuard is a
 * fail-closed allowlist. This drives the exact ApplicationRunner method the
 * container invokes at boot (`run`) with a stubbed Environment so the assertion
 * cannot pass vacuously: it must abort boot when dev mode is enabled outside the
 * {dev, local} allowlist (including the default/production profile) and permit
 * boot inside the allowlist or when dev mode is disabled.
 *
 * Deliberately NOT using ApplicationContextRunner: that helper stops at
 * context.refresh() and never calls callRunners(), so an ApplicationRunner's
 * run() would never fire and such an assertion would be vacuous (see plan §9.6b).
 *
 * Written test-first against the planned DevModeStartupGuard surface, so it is
 * expected to be red until production exists.
 */
class DevModeStartupGuardTest {
    private val args = mock<ApplicationArguments>()

    private fun environmentWith(vararg profiles: String): Environment {
        val environment = mock<Environment>()
        whenever(environment.activeProfiles).thenReturn(arrayOf(*profiles))
        return environment
    }

    private fun guard(
        environment: Environment,
        devModeEnabled: Boolean,
    ): DevModeStartupGuard =
        DevModeStartupGuard(
            environment,
            SessionCookieProperties(devMode = SessionCookieProperties.DevMode(enabled = devModeEnabled)),
        )

    @Test
    fun `default profile with dev mode enabled aborts boot`() {
        val guard = guard(environmentWith(), devModeEnabled = true)

        assertThrows(IllegalStateException::class.java) { guard.run(args) }
    }

    @Test
    fun `dev profile with dev mode enabled permits boot`() {
        val guard = guard(environmentWith("dev"), devModeEnabled = true)

        assertDoesNotThrow { guard.run(args) }
    }

    @Test
    fun `local profile with dev mode enabled permits boot`() {
        val guard = guard(environmentWith("local"), devModeEnabled = true)

        assertDoesNotThrow { guard.run(args) }
    }

    @Test
    fun `default profile with dev mode disabled permits boot`() {
        val guard = guard(environmentWith(), devModeEnabled = false)

        assertDoesNotThrow { guard.run(args) }
    }
}
