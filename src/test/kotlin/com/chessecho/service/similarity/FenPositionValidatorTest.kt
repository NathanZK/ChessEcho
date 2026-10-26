package com.chessecho.service.similarity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FenPositionValidatorTest {
    private val validator = FenPositionValidator()

    @Test
    fun `validateOrthodoxFen returns valid FEN unchanged`() {
        val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val result = validator.validateOrthodoxFen(startFen)

        assertEquals(startFen, result)
    }

    @Test
    fun `validateOrthodoxFen throws IllegalArgumentException on unparseable string`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                validator.validateOrthodoxFen("this is not a fen")
            }

        assertTrue(ex.message?.contains("Invalid FEN position") == true)
    }

    @Test
    fun `validateOrthodoxFen throws IllegalArgumentException on malformed rank count`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                validator.validateOrthodoxFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP w KQkq - 0 1") // only 7 ranks
            }

        assertTrue(ex.message?.contains("Invalid FEN position") == true)
    }

    @Test
    fun `validateOrthodoxFen throws IllegalArgumentException on invalid active side`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                validator.validateOrthodoxFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR x KQkq - 0 1")
            }

        assertTrue(ex.message?.contains("Invalid FEN position") == true)
    }

    @Test
    fun `validateOrthodoxFen throws IllegalArgumentException on illegal piece characters`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                validator.validateOrthodoxFen("zzzzzzzz/8/8/8/8/8/8/8 w - - 0 1")
            }
        assertTrue(ex.message?.contains("Invalid FEN position") == true)
    }
}
