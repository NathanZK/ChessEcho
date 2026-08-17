package com.chessecho.service

import com.chessecho.config.ChessPubApiProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min
import kotlin.math.pow

@Component
class ChessComClient(
    restClientBuilder: RestClient.Builder,
    private val properties: ChessPubApiProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Global serialization lock across all import threads
    private val lock = ReentrantLock()

    // Configurable sleeper function for unit testing time/delays
    internal var sleeper: (Long) -> Unit = { Thread.sleep(it) }

    private val userAgentString: String by lazy {
        val username = properties.userAgentUsername.trim()
        val contact = properties.contact.trim()

        val missing = mutableListOf<String>()
        if (username.isBlank()) missing.add("CHESS_PUBAPI_USERNAME (chess.pubapi.user-agent-username)")
        if (contact.isBlank()) missing.add("CHESS_PUBAPI_CONTACT (chess.pubapi.contact)")

        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "Missing required Chess.com PubAPI configuration: ${missing.joinToString(", ")}. " +
                    "Please set CHESS_PUBAPI_USERNAME and CHESS_PUBAPI_CONTACT environment variables.",
            )
        }

        "ChessEcho/1.0 (username: $username; contact: $contact)"
    }

    private val client: RestClient by lazy {
        restClientBuilder
            .defaultHeader(HttpHeaders.USER_AGENT, userAgentString)
            .build()
    }

    /**
     * Executes a GET request to api.chess.com with global serialization,
     * rate pacing, and retry/exponential backoff for 429 and transient server errors.
     */
    fun <T : Any> getJson(
        url: String,
        responseType: Class<T>,
    ): T? {
        return executeSerialized {
            executeWithRetry(url) {
                client.get()
                    .uri(url)
                    .retrieve()
                    .body(responseType)
            }
        }
    }

    /**
     * Retrieves the master list of monthly archive URLs for the given username.
     */
    fun fetchArchiveUrls(username: String): List<String> {
        val url = "https://api.chess.com/pub/player/${username.lowercase()}/games/archives"

        @Suppress("UNCHECKED_CAST")
        val response = getJson(url, Map::class.java) as? Map<String, Any> ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return response["archives"] as? List<String> ?: emptyList()
    }

    /**
     * Retrieves monthly game maps for a specific archive URL.
     */
    fun fetchMonthlyGames(archiveUrl: String): List<Map<String, Any>>? {
        @Suppress("UNCHECKED_CAST")
        val response = getJson(archiveUrl, Map::class.java) as? Map<String, Any> ?: return null
        @Suppress("UNCHECKED_CAST")
        return response["games"] as? List<Map<String, Any>>
    }

    private fun <T> executeSerialized(action: () -> T): T {
        lock.withLock {
            try {
                return action()
            } finally {
                if (properties.delayMs > 0) {
                    sleeper(properties.delayMs)
                }
            }
        }
    }

    private fun <T> executeWithRetry(
        url: String,
        requestCall: () -> T?,
    ): T? {
        var attempt = 0
        while (true) {
            try {
                return requestCall()
            } catch (ex: HttpClientErrorException.NotFound) {
                log.warn("Chess.com resource not found (404): $url")
                return null
            } catch (ex: RestClientResponseException) {
                val statusCode = ex.statusCode.value()
                val isTransient = statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504

                if (!isTransient || attempt >= properties.maxRetries) {
                    log.error(
                        "Chess.com request failed for {} with status {} (attempt {}/{})",
                        url,
                        statusCode,
                        attempt + 1,
                        properties.maxRetries + 1,
                    )
                    throw ex
                }

                attempt++
                val backoffMs = calculateBackoffMs(ex, attempt)
                log.warn(
                    "Chess.com request returned status {} for {}. Retrying in {}ms (attempt {}/{})...",
                    statusCode,
                    url,
                    backoffMs,
                    attempt,
                    properties.maxRetries,
                )
                sleeper(backoffMs)
            }
        }
    }

    private fun calculateBackoffMs(
        ex: RestClientResponseException,
        attempt: Int,
    ): Long {
        if (ex.statusCode.value() == 429) {
            val retryAfterHeader = ex.responseHeaders?.getFirst(HttpHeaders.RETRY_AFTER)
            if (!retryAfterHeader.isNullOrEmpty()) {
                parseRetryAfter(retryAfterHeader)?.let { return it }
            }
        }
        val exponential = (properties.initialBackoffMs * 2.0.pow((attempt - 1).toDouble())).toLong()
        return min(exponential, properties.maxBackoffMs)
    }

    private fun parseRetryAfter(header: String): Long? {
        header.toLongOrNull()?.let { seconds ->
            return seconds * 1000L
        }
        return try {
            val target = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(header))
            val now = Instant.now()
            val millis = Duration.between(now, target).toMillis()
            if (millis > 0) millis else properties.initialBackoffMs
        } catch (e: Exception) {
            null
        }
    }
}
