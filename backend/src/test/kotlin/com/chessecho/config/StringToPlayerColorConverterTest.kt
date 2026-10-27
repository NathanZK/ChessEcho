package com.chessecho.config

import com.chessecho.domain.PlayerColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class StringToPlayerColorConverterTest {
    private val converter = StringToPlayerColorConverter()

    @Test
    fun `converts valid colors`() {
        assertEquals(PlayerColor.WHITE, converter.convert("WHITE"))
        assertEquals(PlayerColor.BLACK, converter.convert("BLACK"))
        assertEquals(PlayerColor.BOTH, converter.convert("BOTH"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["white", "White", "wHiTe"])
    fun `converts white case insensitively`(input: String) {
        assertEquals(PlayerColor.WHITE, converter.convert(input))
    }

    @ParameterizedTest
    @ValueSource(strings = ["black", "Black", "bLaCk"])
    fun `converts black case insensitively`(input: String) {
        assertEquals(PlayerColor.BLACK, converter.convert(input))
    }

    @ParameterizedTest
    @ValueSource(strings = ["both", "Both", "bOtH"])
    fun `converts both case insensitively`(input: String) {
        assertEquals(PlayerColor.BOTH, converter.convert(input))
    }

    @ParameterizedTest
    @ValueSource(strings = ["  WHITE  ", "\twhite\n", " black ", "  BOTH  "])
    fun `trims surrounding whitespace`(input: String) {
        val expected =
            when {
                input.uppercase().contains("WHITE") -> PlayerColor.WHITE
                input.uppercase().contains("BLACK") -> PlayerColor.BLACK
                else -> PlayerColor.BOTH
            }
        assertEquals(expected, converter.convert(input))
    }

    @ParameterizedTest
    @ValueSource(strings = ["RED", "UNKNOWN", "invalid", "", "   "])
    fun `throws IllegalArgumentException with chained cause for invalid playerColor`(input: String) {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                converter.convert(input)
            }
        assertEquals("Invalid playerColor '$input'. Must be WHITE, BLACK, or BOTH.", ex.message)
        assertNotNull(ex.cause)
        assertInstanceOf(IllegalArgumentException::class.java, ex.cause)
    }
}
