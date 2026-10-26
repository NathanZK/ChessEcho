package com.chessecho.service.similarity

import org.springframework.stereotype.Component
import java.net.URI

@Component
class ChessComUrlBuilder {
    fun buildExactPlyUrl(
        platformGameId: String?,
        plyNumber: Int,
        timeControl: String? = null,
    ): String? {
        if (platformGameId.isNullOrBlank()) return null
        val safePly = maxOf(0, plyNumber)

        return try {
            val uri = URI(platformGameId.trim())
            if (uri.isAbsolute) {
                formatAbsoluteUrl(uri, safePly)
            } else {
                formatRelativeId(platformGameId.trim(), timeControl, safePly)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatAbsoluteUrl(
        uri: URI,
        safePly: Int,
    ): String? {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host?.lowercase() ?: return null
        if (host != "chess.com" && host != "www.chess.com") return null

        val path = uri.path ?: return null
        val isValidPath =
            path.startsWith("/game/live/") ||
                path.startsWith("/game/daily/") ||
                path.startsWith("/game/computer/")
        if (!isValidPath) return null

        return "$scheme://$host$path?move=$safePly"
    }

    private fun formatRelativeId(
        rawId: String,
        timeControl: String?,
        safePly: Int,
    ): String? {
        if (!NUMERIC_ID_REGEX.matches(rawId)) return null
        val type = if (timeControl?.uppercase() == "DAILY") "daily" else "live"
        return "https://www.chess.com/game/$type/$rawId?move=$safePly"
    }

    companion object {
        private val NUMERIC_ID_REGEX = Regex("^[0-9]+$")
    }
}
