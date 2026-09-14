package com.chessecho.config

import com.chessecho.domain.TimeControl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Issue #198 — the converter delegates to TimeControl.fromExternal, which accepts
 * STANDARD as an alias for CLASSICAL, but its invalid-input message historically
 * omitted STANDARD from the supported list. These tests pin both the accepted
 * alias and the exact user-facing message, and assert that the set of accepted and
 * rejected values is unchanged.
 */
class StringToTimeControlConverterTest {
    private val converter = StringToTimeControlConverter()

    @Test
    fun `convert accepts the STANDARD alias as CLASSICAL`() {
        assertEquals(TimeControl.CLASSICAL, converter.convert("standard"))
    }

    @Test
    fun `convert normalizes case and surrounding whitespace for the STANDARD alias`() {
        assertEquals(TimeControl.CLASSICAL, converter.convert("  StAnDaRd  "))
    }

    @Test
    fun `convert maps the directly supported time controls`() {
        assertEquals(TimeControl.RAPID, converter.convert("rapid"))
        assertEquals(TimeControl.BLITZ, converter.convert("blitz"))
        assertEquals(TimeControl.BULLET, converter.convert("bullet"))
        assertEquals(TimeControl.CLASSICAL, converter.convert("classical"))
    }

    @Test
    fun `convert rejects unsupported values`() {
        assertThrows(IllegalArgumentException::class.java) { converter.convert("daily") }
        assertThrows(IllegalArgumentException::class.java) { converter.convert("correspondence") }
        assertThrows(IllegalArgumentException::class.java) { converter.convert("") }
        assertThrows(IllegalArgumentException::class.java) { converter.convert("unknown") }
    }

    @Test
    fun `convert reports STANDARD in the unsupported value message`() {
        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                converter.convert("unsupported")
            }

        assertEquals(
            "Invalid timeControl 'unsupported'. " +
                "Supported timeControls: RAPID, BLITZ, BULLET, CLASSICAL, STANDARD.",
            exception.message,
        )
    }
}
