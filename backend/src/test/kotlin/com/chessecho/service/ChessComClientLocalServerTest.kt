package com.chessecho.service

import com.chessecho.config.ChessPubApiProperties
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class ChessComClientLocalServerTest {
    private lateinit var server: HttpServer
    private var serverPort: Int = 0
    private val capturedUserAgent = AtomicReference<String?>()

    @BeforeEach
    fun startLocalServer() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/test-endpoint") { exchange ->
            capturedUserAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
            val responseBody = """{"status": "ok"}""".toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.size.toLong())
            exchange.responseBody.use { it.write(responseBody) }
        }
        server.start()
        serverPort = server.address.port
    }

    @AfterEach
    fun stopLocalServer() {
        server.stop(0)
    }

    @Test
    fun `verify real outgoing User-Agent header over wire on local HTTP server`() {
        val properties =
            ChessPubApiProperties(
                userAgentUsername = "test-user",
                contact = "test@example.com",
                delayMs = 0L,
            )

        // Uses the exact production RestClient.builder() and ChessComClient
        val restClientBuilder = RestClient.builder()
        val chessComClient = ChessComClient(restClientBuilder, properties)

        val url = "http://localhost:$serverPort/test-endpoint"
        val response = chessComClient.getJson(url, Map::class.java)

        assertNotNull(response)
        val userAgent = capturedUserAgent.get()

        assertNotNull(userAgent, "User-Agent header was not captured by the local HTTP server")
        assertEquals(
            "ChessEcho/1.0 (username: test-user; contact: test@example.com)",
            userAgent,
        )
    }
}
