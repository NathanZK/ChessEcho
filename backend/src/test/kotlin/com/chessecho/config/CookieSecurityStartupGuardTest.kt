package com.chessecho.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.ApplicationArguments
import org.springframework.core.env.Environment

class CookieSecurityStartupGuardTest {
    private val args = mock<ApplicationArguments>()

    private fun environmentWith(vararg profiles: String): Environment {
        val environment = mock<Environment>()
        whenever(environment.activeProfiles).thenReturn(arrayOf(*profiles))
        return environment
    }

    private fun guard(
        environment: Environment,
        cookieSecure: Boolean,
    ): CookieSecurityStartupGuard =
        CookieSecurityStartupGuard(
            environment,
            SessionCookieProperties(cookie = SessionCookieProperties.Cookie(secure = cookieSecure)),
        )

    @Test
    fun `default profile with cookie secure true permits boot`() {
        val guard = guard(environmentWith(), cookieSecure = true)

        assertDoesNotThrow { guard.run(args) }
    }

    @Test
    fun `production profile with cookie secure true permits boot`() {
        val guard = guard(environmentWith("production"), cookieSecure = true)

        assertDoesNotThrow { guard.run(args) }
    }

    @Test
    fun `dev profile with cookie secure false permits boot`() {
        val guard = guard(environmentWith("dev"), cookieSecure = false)

        assertDoesNotThrow { guard.run(args) }
    }

    @Test
    fun `local profile with cookie secure false permits boot`() {
        val guard = guard(environmentWith("local"), cookieSecure = false)

        assertDoesNotThrow { guard.run(args) }
    }

    @Test
    fun `test profile with cookie secure false permits boot`() {
        val guard = guard(environmentWith("test"), cookieSecure = false)

        assertDoesNotThrow { guard.run(args) }
    }

    @Test
    fun `default profile with cookie secure false aborts boot with actionable message`() {
        val guard = guard(environmentWith(), cookieSecure = false)

        val exception = assertThrows(IllegalStateException::class.java) { guard.run(args) }
        assertEquals(
            "chessecho.auth.cookie.secure=false is only permitted under development/test profiles " +
                "[dev, local, test]; active profiles=[]. Refusing to start.",
            exception.message,
        )
    }

    @Test
    fun `non-allowlisted profile with cookie secure false aborts boot with actionable message`() {
        val guard = guard(environmentWith("production"), cookieSecure = false)

        val exception = assertThrows(IllegalStateException::class.java) { guard.run(args) }
        assertEquals(
            "chessecho.auth.cookie.secure=false is only permitted under development/test profiles " +
                "[dev, local, test]; active profiles=[production]. Refusing to start.",
            exception.message,
        )
    }
}
