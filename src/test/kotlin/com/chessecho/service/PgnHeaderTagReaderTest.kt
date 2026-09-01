package com.chessecho.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PgnHeaderTagReaderTest {
    private val reader = PgnHeaderTagReader()

    @Test
    fun `reads retained tags with BOM CRLF blank prefix and PGN escapes`() {
        val result =
            reader.read(
                "\uFEFF\r\n\r\n" +
                    "[Event \"ignored\"]\r\n" +
                    "[White \"A\\\"lice\\\\One\"]\r\n" +
                    "[Black \"Bob\"]\r\n" +
                    "[Result \"1-0\"]\r\n" +
                    "[Variant \"Standard\"]\r\n\r\n" +
                    "1. e4 e5 1-0",
            )

        assertEquals("OK", result.status.name)
        assertEquals("A\"lice\\One", result.white)
        assertEquals("Bob", result.black)
        assertEquals("1-0", result.result)
        assertEquals("Standard", result.variant)
    }

    @Test
    fun `ignores syntactically valid non-standard tags`() {
        val result =
            reader.read(
                """
                [Event "Live Chess"]
                [Site "Chess.com"]
                [White "alice"]

                1. e4
                """.trimIndent(),
            )

        assertEquals("OK", result.status.name)
        assertEquals("alice", result.white)
        assertNull(result.black)
        assertNull(result.result)
        assertNull(result.variant)
    }

    @Test
    fun `returns absent for a bounded prefix without a tag section`() {
        assertEquals("ABSENT", reader.read("1. e4 e5").status.name)
        assertEquals("ABSENT", reader.read("\uFEFF\n\n1. d4 d5").status.name)
    }

    @Test
    fun `stops at the header separator without parsing movetext`() {
        val result =
            reader.read(
                "[White \"alice\"]\n[Result \"1-0\"]\n\n" +
                    "[this is malformed movetext\n" +
                    "x".repeat(20_000),
            )

        assertEquals("OK", result.status.name)
        assertEquals("alice", result.white)
        assertEquals("1-0", result.result)
    }

    @Test
    fun `rejects duplicate retained tags but permits duplicate ignored tags`() {
        assertEquals(
            "MALFORMED",
            reader.read("[White \"alice\"]\n[White \"other\"]\n\n").status.name,
        )
        assertEquals(
            "OK",
            reader.read("[Event \"one\"]\n[Event \"two\"]\n[White \"alice\"]\n\n").status.name,
        )
    }

    @Test
    fun `reports malformed strict tag syntax and unterminated values`() {
        val malformed =
            listOf(
                "[White alice]\n\n",
                "[White \"alice\"\n\n",
                "[White \"alice]\"\n\n",
                "[White \"alice\\\"]\n\n",
                "[White \"alice\"] trailing\n\n",
                "[White Name \"alice\"]\n\n",
            )

        malformed.forEach { pgn ->
            assertEquals("MALFORMED", reader.read(pgn).status.name, pgn)
        }
    }

    @Test
    fun `reports non-tag content inside a started header as malformed`() {
        val result = reader.read("[White \"alice\"]\nnot a tag\n\n1. e4")

        assertEquals("MALFORMED", result.status.name)
    }

    @Test
    fun `accepts a tag line at the exact 1024 character boundary`() {
        val value = "x".repeat(1_014)
        val line = "[Event \"$value\"]"
        assertEquals(1_024, line.length)

        assertEquals("OK", reader.read("$line\n\n").status.name)
    }

    @Test
    fun `reports limit exceeded when a logical line exceeds 1024 characters`() {
        val line = "[Event \"${"x".repeat(1_015)}\"]"
        assertEquals(1_025, line.length)

        assertEquals("LIMIT_EXCEEDED", reader.read("$line\n\n").status.name)
    }

    @Test
    fun `accepts exactly 32 tag pairs and rejects the thirty-third`() {
        val thirtyTwo = (1..32).joinToString("\n") { "[Tag$it \"value\"]" }
        val thirtyThree = (1..33).joinToString("\n") { "[Tag$it \"value\"]" }

        assertEquals("OK", reader.read("$thirtyTwo\n\n").status.name)
        assertEquals("LIMIT_EXCEEDED", reader.read("$thirtyThree\n\n").status.name)
    }

    @Test
    fun `accepts the 64 line boundary and rejects a separator on line 65`() {
        val atBoundary = "\n".repeat(62) + "[White \"alice\"]\n\n"
        val overBoundary = "\n".repeat(63) + "[White \"alice\"]\n\n"

        assertEquals("OK", reader.read(atBoundary).status.name)
        assertEquals("LIMIT_EXCEEDED", reader.read(overBoundary).status.name)
    }

    @Test
    fun `accepts the 16384 character prefix boundary and rejects one character more`() {
        val lines =
            (1..16).map { index ->
                tagLine("Tag$index", if (index == 16) 1_022 else 1_023)
            }
        val atBoundary = lines.joinToString("\n", postfix = "\n\n")
        val overBoundary = lines.dropLast(1).plus(tagLine("Tag16", 1_023)).joinToString("\n", postfix = "\n\n")

        assertEquals(16_384, atBoundary.length)
        assertEquals(16_385, overBoundary.length)
        assertEquals("OK", reader.read(atBoundary).status.name)
        assertEquals("LIMIT_EXCEEDED", reader.read(overBoundary).status.name)
    }

    @Test
    fun `reports limit exceeded for a header that reaches a bound without a separator`() {
        val thirtyTwo = (1..32).joinToString("\n") { "[Tag$it \"value\"]" }

        assertEquals("LIMIT_EXCEEDED", reader.read(thirtyTwo).status.name)
    }

    private fun tagLine(
        tag: String,
        length: Int,
    ): String {
        val wrapperLength = "[$tag \"\"]".length
        return "[$tag \"${"x".repeat(length - wrapperLength)}\"]"
    }
}
