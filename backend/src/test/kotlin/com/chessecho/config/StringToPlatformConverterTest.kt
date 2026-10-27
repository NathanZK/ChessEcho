package com.chessecho.config

import com.chessecho.domain.Platform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class StringToPlatformConverterTest {
    private val converter = StringToPlatformConverter()

    @Test
    fun `converts exact CHESS_COM`() {
        assertEquals(Platform.CHESS_COM, converter.convert("CHESS_COM"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["chess_com", "Chess_Com", "cHeSs_CoM"])
    fun `converts case insensitively`(input: String) {
        assertEquals(Platform.CHESS_COM, converter.convert(input))
    }

    @ParameterizedTest
    @ValueSource(strings = ["  CHESS_COM  ", "\tCHESS_COM\n", "  chess_com  "])
    fun `trims surrounding whitespace`(input: String) {
        assertEquals(Platform.CHESS_COM, converter.convert(input))
    }

    @ParameterizedTest
    @ValueSource(strings = ["CHESSDOTCOM", "chessdotcom", "ChessDotCom", "CHESS.COM", "chess.com", "Chess.Com", "  chess.com  "])
    fun `normalizes aliases to CHESS_COM`(input: String) {
        assertEquals(Platform.CHESS_COM, converter.convert(input))
    }

    @ParameterizedTest
    @ValueSource(strings = ["LICHESS", "UNKNOWN", "invalid", "", "   "])
    fun `throws IllegalArgumentException with chained cause for invalid platform`(input: String) {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                converter.convert(input)
            }
        assertEquals("Invalid platform '$input'. Supported platforms: CHESS_COM.", ex.message)
        assertNotNull(ex.cause)
        assertInstanceOf(IllegalArgumentException::class.java, ex.cause)
    }
}
