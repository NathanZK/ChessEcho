package com.chessprobe.isolation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.client.RestClient
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutboundNetworkGuardTest {
    @Test
    fun `unintended external request fails deterministically`() {
        TestNetworkGuard.install()
        val restClient = RestClient.builder().build()

        val ex =
            assertThrows<Exception> {
                restClient
                    .get()
                    .uri("https://api.chess.com/pub/player/hikaru")
                    .retrieve()
                    .toBodilessEntity()
            }

        val hasBlockedException =
            generateSequence<Throwable>(ex) { it.cause }
                .any { it is BlockedOutboundNetworkException }
        assertTrue(hasBlockedException, "Expected BlockedOutboundNetworkException in cause chain but got: $ex")
    }

    @Test
    fun `permitted loopback addresses pass guard selection`() {
        // Localhost and IPv6 loopback
        assertTrue(TestNetworkGuard.isPermittedHost("localhost"))
        assertTrue(TestNetworkGuard.isPermittedHost("::1"))
        assertTrue(TestNetworkGuard.isPermittedHost("[::1]"))

        // Standard 127.0.0.1 and valid 127.0.0.0/8 subnet addresses
        assertTrue(TestNetworkGuard.isPermittedHost("127.0.0.1"))
        assertTrue(TestNetworkGuard.isPermittedHost("127.0.0.2"))
        assertTrue(TestNetworkGuard.isPermittedHost("127.1.2.3"))
        assertTrue(TestNetworkGuard.isPermittedHost("127.255.255.254"))
    }

    @Test
    fun `external hosts and deceptive prefix domains are blocked`() {
        // Deceptive prefix hostnames starting with 127
        assertFalse(TestNetworkGuard.isPermittedHost("127.example.com"))
        assertFalse(TestNetworkGuard.isPermittedHost("127-cdn.net"))
        assertFalse(TestNetworkGuard.isPermittedHost("127.0.0.com"))
        assertFalse(TestNetworkGuard.isPermittedHost("127.0.0.1.nip.io"))

        // Out-of-range or malformed octets
        assertFalse(TestNetworkGuard.isPermittedHost("127.0.0.256"))
        assertFalse(TestNetworkGuard.isPermittedHost("127.0.0.01"))
        assertFalse(TestNetworkGuard.isPermittedHost("128.0.0.1"))

        // Removed host.docker.internal and public internet domains
        assertFalse(TestNetworkGuard.isPermittedHost("host.docker.internal"))
        assertFalse(TestNetworkGuard.isPermittedHost("api.chess.com"))
        assertFalse(TestNetworkGuard.isPermittedHost("example.com"))
    }
}
