package com.chessecho.service.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChessComUrlBuilderTest {
    private val builder = ChessComUrlBuilder()

    @Test
    fun `buildExactPlyUrl appends move parameter to valid live game URL`() {
        val url = "https://www.chess.com/game/live/172923974924"
        val result = builder.buildExactPlyUrl(url, plyNumber = 14)

        assertEquals("https://www.chess.com/game/live/172923974924?move=14", result)
    }

    @Test
    fun `buildExactPlyUrl replaces existing query parameters with move parameter`() {
        val url = "https://chess.com/game/daily/123456?username=test&move=0"
        val result = builder.buildExactPlyUrl(url, plyNumber = 20)

        assertEquals("https://chess.com/game/daily/123456?move=20", result)
    }

    @Test
    fun `buildExactPlyUrl handles negative ply by falling back to 0`() {
        val url = "https://www.chess.com/game/live/123456"
        val result = builder.buildExactPlyUrl(url, plyNumber = -5)

        assertEquals("https://www.chess.com/game/live/123456?move=0", result)
    }

    @Test
    fun `buildExactPlyUrl resolves numeric game ID with time control`() {
        val liveResult = builder.buildExactPlyUrl("123456", plyNumber = 8, timeControl = "RAPID")
        assertEquals("https://www.chess.com/game/live/123456?move=8", liveResult)

        val dailyResult = builder.buildExactPlyUrl("987654", plyNumber = 5, timeControl = "DAILY")
        assertEquals("https://www.chess.com/game/daily/987654?move=5", dailyResult)
    }

    @Test
    fun `buildExactPlyUrl returns null for untrusted hosts or malformed URLs`() {
        assertNull(builder.buildExactPlyUrl("https://phishing-chess.com/game/live/123", plyNumber = 10))
        assertNull(builder.buildExactPlyUrl("javascript:alert(1)", plyNumber = 10))
        assertNull(builder.buildExactPlyUrl("ftp://chess.com/game/live/123", plyNumber = 10))
        assertNull(builder.buildExactPlyUrl("not a valid identifier or url !!!", plyNumber = 10))
        assertNull(builder.buildExactPlyUrl(null, plyNumber = 10))
    }
}
