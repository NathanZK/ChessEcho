package com.chessprobe.isolation

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

class BlockedOutboundNetworkException(
    message: String,
) : SecurityException(message)

/**
 * Fail-fast outbound HTTP network guard for test execution.
 * Intercepts requests that use standard JDK ProxySelector delegation (including Spring RestClient),
 * blocking external traffic while permitting localhost and 127.0.0.0/8 IPv4 / ::1 IPv6 loopback addresses.
 * Note: ProxySelector does not intercept direct raw sockets, native subprocesses, or clients with custom proxy configurations.
 */
object TestNetworkGuard {
    private var installed = false
    private val defaultSelector: ProxySelector = ProxySelector.getDefault()

    @Synchronized
    fun install() {
        if (installed) return
        ProxySelector.setDefault(
            object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    val host = uri.host ?: ""
                    if (isPermittedHost(host)) {
                        return defaultSelector.select(uri)
                    }
                    throw BlockedOutboundNetworkException(
                        "Blocked outbound network access to non-local host '$host' (URI: $uri) in test suite",
                    )
                }

                override fun connectFailed(
                    uri: URI,
                    sa: SocketAddress,
                    ioe: IOException,
                ) {
                    defaultSelector.connectFailed(uri, sa, ioe)
                }
            },
        )
        installed = true
    }

    fun isPermittedHost(host: String): Boolean {
        if (host.isBlank()) return true
        val lower = host.lowercase().removePrefix("[").removeSuffix("]")
        return lower == "localhost" ||
            lower == "::1" ||
            isIpv4Loopback(lower)
    }

    private fun isIpv4Loopback(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        if (first != 127) return false
        return parts.drop(1).all { part ->
            val num = part.toIntOrNull() ?: return false
            num in 0..255 && (part == "0" || !part.startsWith("0"))
        }
    }
}
