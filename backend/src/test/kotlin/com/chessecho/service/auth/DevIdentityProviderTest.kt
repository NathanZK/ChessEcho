package com.chessecho.service.auth

import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * Issue #113 (AC9, #79 D7) — the provider-adapter boundary consumes already
 * validated (issuer, subject) claims. The development principal is a concrete
 * adapter that supplies fixed provider-neutral claims and no email metadata,
 * proving the boundary does not depend on any production provider.
 *
 * Written test-first against the planned IdentityProviderAdapter/DevIdentityProvider
 * surface, so it is expected to be red until production exists.
 */
class DevIdentityProviderTest {
    private val provider: IdentityProviderAdapter = DevIdentityProvider()

    @Test
    fun `supplies fixed provider-neutral dev claims`() {
        val request: HttpServletRequest = MockHttpServletRequest()

        val claims = provider.claims(request)

        assertEquals("dev-local", claims.issuer)
        assertEquals("local-dev", claims.subject)
    }

    @Test
    fun `carries no email metadata for the dev principal`() {
        val claims = provider.claims(MockHttpServletRequest())

        assertNull(claims.emailSnapshot)
        assertTrue(claims.emailVerified == null || claims.emailVerified == false)
    }
}
