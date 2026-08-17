package com.chessecho.service

import com.chessecho.config.ChessPubApiProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Suppress("UNCHECKED_CAST")
class ChessComClientTest {
    private lateinit var restClientBuilder: RestClient.Builder
    private lateinit var restClient: RestClient
    private lateinit var requestHeadersUriSpec: RestClient.RequestHeadersUriSpec<*>
    private lateinit var mockHeadersSpec: RestClient.RequestHeadersSpec<*>
    private lateinit var mockResponseSpec: RestClient.ResponseSpec

    private lateinit var properties: ChessPubApiProperties
    private lateinit var client: ChessComClient

    @BeforeEach
    @Suppress("UNCHECKED_CAST")
    fun setup() {
        properties =
            ChessPubApiProperties(
                userAgentUsername = "test-user",
                contact = "test@example.com",
                delayMs = 10L,
                maxRetries = 3,
                initialBackoffMs = 20L,
                maxBackoffMs = 100L,
            )

        restClientBuilder = mock()
        restClient = mock()
        requestHeadersUriSpec = mock()
        mockHeadersSpec = mock()
        mockResponseSpec = mock()

        whenever(restClientBuilder.defaultHeader(any(), any())).thenReturn(restClientBuilder)
        whenever(restClientBuilder.build()).thenReturn(restClient)

        whenever(restClient.get()).thenReturn(requestHeadersUriSpec)
        whenever(requestHeadersUriSpec.uri(any<String>())).thenReturn(mockHeadersSpec)
        whenever(mockHeadersSpec.retrieve()).thenReturn(mockResponseSpec)

        client = ChessComClient(restClientBuilder, properties)
        client.sleeper = { } // Instant sleeper for fast tests
    }

    @Test
    fun `1 User-Agent is present on Chess-com requests`() {
        whenever(mockResponseSpec.body(Map::class.java)).thenReturn(mapOf("archives" to emptyList<String>()))
        client.fetchArchiveUrls("hikaru")

        val captorKey = argumentCaptor<String>()
        val captorValue = argumentCaptor<String>()
        verify(restClientBuilder).defaultHeader(captorKey.capture(), captorValue.capture())

        assertEquals(HttpHeaders.USER_AGENT, captorKey.firstValue)
        assertEquals("ChessEcho/1.0 (username: test-user; contact: test@example.com)", captorValue.firstValue)
    }

    @Test
    fun `User-Agent throws IllegalStateException when properties are blank`() {
        val emptyProps = ChessPubApiProperties(userAgentUsername = "", contact = "")
        val localBuilder: RestClient.Builder = mock()
        whenever(localBuilder.defaultHeader(any(), any())).thenReturn(localBuilder)
        whenever(localBuilder.build()).thenReturn(restClient)

        val client = ChessComClient(localBuilder, emptyProps)

        val ex =
            assertThrows<IllegalStateException> {
                client.fetchArchiveUrls("hikaru")
            }

        assertTrue(ex.message!!.contains("CHESS_PUBAPI_USERNAME"))
        assertTrue(ex.message!!.contains("CHESS_PUBAPI_CONTACT"))
    }

    @Test
    fun `2 Two concurrent import jobs cannot make simultaneous Chess-com requests`() {
        val inFlightRequests = AtomicInteger(0)
        val maxSimultaneous = AtomicInteger(0)
        val latch = CountDownLatch(2)

        whenever(mockResponseSpec.body(Map::class.java)).thenAnswer {
            val current = inFlightRequests.incrementAndGet()
            maxSimultaneous.updateAndGet { max -> maxOf(max, current) }
            Thread.sleep(50) // hold execution briefly inside request
            inFlightRequests.decrementAndGet()
            mapOf("archives" to listOf("url1"))
        }

        val executor = Executors.newFixedThreadPool(2)
        client.sleeper = { Thread.sleep(it) }

        executor.submit {
            client.fetchArchiveUrls("user1")
            latch.countDown()
        }
        executor.submit {
            client.fetchArchiveUrls("user2")
            latch.countDown()
        }

        val finished = latch.await(2, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(finished, "Both requests should finish")
        assertEquals(1, maxSimultaneous.get(), "At most 1 request should be in flight at any time")
    }

    @Test
    fun `3 Delay occurs between requests according to configuration`() {
        val sleepDurations = mutableListOf<Long>()
        client.sleeper = { sleepDurations.add(it) }

        whenever(mockResponseSpec.body(Map::class.java)).thenReturn(mapOf("archives" to emptyList<String>()))

        client.fetchArchiveUrls("hikaru")

        assertTrue(sleepDurations.contains(10L), "Configured delay-ms should be executed by sleeper")
    }

    @Test
    fun `4 429 triggers retry and exponential backoff`() {
        val sleepDurations = mutableListOf<Long>()
        client.sleeper = { sleepDurations.add(it) }

        whenever(mockResponseSpec.body(Map::class.java))
            .thenThrow(
                HttpClientErrorException.create(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too Many Requests",
                    HttpHeaders.EMPTY,
                    ByteArray(0),
                    null,
                ),
            )
            .thenThrow(
                HttpClientErrorException.create(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too Many Requests",
                    HttpHeaders.EMPTY,
                    ByteArray(0),
                    null,
                ),
            )
            .thenReturn(mapOf("archives" to listOf("url1")))

        val result = client.fetchArchiveUrls("hikaru")

        assertEquals(listOf("url1"), result)
        assertEquals(3, sleepDurations.size) // 2 backoff retries + 1 pacing delay
        assertEquals(20L, sleepDurations[0]) // initial backoff
        assertEquals(40L, sleepDurations[1]) // exponential 20 * 2^1
        assertEquals(10L, sleepDurations[2]) // pacing delay
    }

    @Test
    fun `5 Retry-After is respected when present`() {
        val sleepDurations = mutableListOf<Long>()
        client.sleeper = { sleepDurations.add(it) }

        val headers = HttpHeaders()
        headers.set(HttpHeaders.RETRY_AFTER, "5") // 5 seconds

        whenever(mockResponseSpec.body(Map::class.java))
            .thenThrow(
                HttpClientErrorException.create(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too Many Requests",
                    headers,
                    ByteArray(0),
                    null,
                ),
            )
            .thenReturn(mapOf("archives" to listOf("url1")))

        val result = client.fetchArchiveUrls("hikaru")

        assertEquals(listOf("url1"), result)
        assertEquals(5000L, sleepDurations[0], "Retry-After header of 5s should result in 5000ms delay")
    }

    @Test
    fun `6 Permanent HTTP errors are not unnecessarily retried`() {
        whenever(mockResponseSpec.body(Map::class.java))
            .thenThrow(
                HttpClientErrorException.create(
                    HttpStatus.FORBIDDEN,
                    "Forbidden",
                    HttpHeaders.EMPTY,
                    ByteArray(0),
                    null,
                ),
            )

        assertThrows<HttpClientErrorException.Forbidden> {
            client.fetchArchiveUrls("hikaru")
        }

        verify(mockResponseSpec, times(1)).body(Map::class.java) // Exactly 1 call, no retries
    }

    @Test
    fun `6 404 Not Found returns null without retrying`() {
        whenever(mockResponseSpec.body(Map::class.java))
            .thenThrow(
                HttpClientErrorException.create(
                    HttpStatus.NOT_FOUND,
                    "Not Found",
                    HttpHeaders.EMPTY,
                    ByteArray(0),
                    null,
                ),
            )

        val result = client.fetchMonthlyGames("https://api.chess.com/pub/player/hikaru/games/2024/01")

        assertNull(result)
        verify(mockResponseSpec, times(1)).body(Map::class.java)
    }

    @Test
    fun `Transient 503 Server Error retries and succeeds`() {
        val sleepDurations = mutableListOf<Long>()
        client.sleeper = { sleepDurations.add(it) }

        whenever(mockResponseSpec.body(Map::class.java))
            .thenThrow(
                HttpServerErrorException.create(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Service Unavailable",
                    HttpHeaders.EMPTY,
                    ByteArray(0),
                    null,
                ),
            )
            .thenReturn(mapOf("archives" to listOf("url1")))

        val result = client.fetchArchiveUrls("hikaru")

        assertEquals(listOf("url1"), result)
        assertEquals(2, sleepDurations.size) // 1 retry + 1 pacing delay
        assertEquals(20L, sleepDurations[0])
        assertEquals(10L, sleepDurations[1])
    }
}
