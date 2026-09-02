package com.chessecho.web

import com.chessecho.service.auth.AuthenticatedPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.ServletWebRequest
import java.util.UUID

/**
 * Issue #113 (AC6/AC10, #79 D2/D4) — the argument resolver injects the request
 * principal set by the session filter and fails closed by throwing
 * UnauthenticatedException (mapped to 401) when no principal is present.
 *
 * Written test-first against the planned AuthenticatedPrincipalArgumentResolver
 * surface, so it is expected to be red until production exists.
 */
class AuthenticatedPrincipalArgumentResolverTest {
    private val resolver = AuthenticatedPrincipalArgumentResolver()

    @Suppress("UNUSED_PARAMETER")
    private fun handler(principal: AuthenticatedPrincipal) = Unit

    private fun principalParameter(): MethodParameter {
        val method = this::class.java.getDeclaredMethod("handler", AuthenticatedPrincipal::class.java)
        return MethodParameter(method, 0)
    }

    @Test
    fun `supports the AuthenticatedPrincipal parameter`() {
        assertTrue(resolver.supportsParameter(principalParameter()))
    }

    @Test
    fun `resolves the principal placed on the request by the filter`() {
        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = true)
        val request = MockHttpServletRequest()
        request.setAttribute(SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, principal)

        val resolved = resolver.resolveArgument(principalParameter(), null, ServletWebRequest(request), null)

        assertEquals(principal, resolved)
    }

    @Test
    fun `fails closed with UnauthenticatedException when no principal is present`() {
        val request = MockHttpServletRequest()

        assertThrows(UnauthenticatedException::class.java) {
            resolver.resolveArgument(principalParameter(), null, ServletWebRequest(request), null)
        }
    }

    @Test
    fun `does not support unrelated parameter types`() {
        val method = String::class.java.getDeclaredMethod("concat", String::class.java)
        assertFalse(resolver.supportsParameter(MethodParameter(method, 0)))
    }
}
