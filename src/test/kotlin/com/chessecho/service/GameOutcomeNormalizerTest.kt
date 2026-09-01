package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameOutcomeNormalizerTest {
    private val normalizer = GameOutcomeNormalizer(PgnHeaderTagReader())

    @Test
    fun `normalizes every supported Chess com White-side terminal token`() {
        mapOf(
            "win" to "WIN",
            "checkmated" to "LOSS",
            "resigned" to "LOSS",
            "timeout" to "LOSS",
            "abandoned" to "LOSS",
            "lose" to "LOSS",
            "agreed" to "DRAW",
            "repetition" to "DRAW",
            "stalemate" to "DRAW",
            "insufficient" to "DRAW",
            "50move" to "DRAW",
            "timevsinsufficient" to "DRAW",
        ).forEach { (token, expected) ->
            val normalized = normalizer.normalize(game(result = "  ${token.uppercase()}  "), "WHITE", "account")

            assertEquals(expected, normalized.outcome?.name, token)
            assertNull(normalized.exclusionReason, token)
            assertNull(normalized.ineligibilityReason, token)
        }
    }

    @Test
    fun `inverts wins and losses for Black while preserving draws`() {
        assertEquals("LOSS", normalizer.normalize(game(result = "win"), "BLACK", "account").outcome?.name)
        assertEquals("WIN", normalizer.normalize(game(result = "resigned"), "BLACK", "account").outcome?.name)
        assertEquals("DRAW", normalizer.normalize(game(result = "stalemate"), "BLACK", "account").outcome?.name)
    }

    @Test
    fun `uses bounded PGN Result only when source result is absent blank or unrecognized`() {
        listOf(null, " ", "unknown").forEach { sourceResult ->
            val whiteWin =
                normalizer.normalize(
                    game(result = sourceResult, pgn = header(result = "1-0")),
                    "WHITE",
                    "account",
                )
            val blackWin =
                normalizer.normalize(
                    game(result = sourceResult, pgn = header(result = "0-1")),
                    "BLACK",
                    "account",
                )
            val draw =
                normalizer.normalize(
                    game(result = sourceResult, pgn = header(result = "1/2-1/2")),
                    "BLACK",
                    "account",
                )

            assertEquals("WIN", whiteWin.outcome?.name)
            assertEquals("WIN", blackWin.outcome?.name)
            assertEquals("DRAW", draw.outcome?.name)
        }
    }

    @Test
    fun `recognized source result is authoritative over contradictory PGN Result`() {
        val normalized =
            normalizer.normalize(
                game(result = "win", pgn = header(result = "0-1")),
                "WHITE",
                "account",
            )

        assertEquals("WIN", normalized.outcome?.name)
        assertNull(normalized.exclusionReason)
    }

    @Test
    fun `excludes star absent malformed and unsupported fallback results`() {
        listOf(
            header(result = "*"),
            header(result = null),
            header(result = "1 / 0"),
            header(result = "aborted"),
        ).forEach { pgn ->
            val normalized = normalizer.normalize(game(result = "unknown", pgn = pgn), "WHITE", "account")

            assertNull(normalized.outcome)
            assertEquals("UNNORMALIZABLE_RESULT", normalized.exclusionReason?.name)
        }
    }

    @Test
    fun `scores authoritative legacy Black occurrence when persisted names no longer match`() {
        val normalized =
            normalizer.normalize(
                game(
                    result = "win",
                    whiteUsername = "stale-white",
                    blackUsername = "stale-black",
                    pgn = header(white = "opponent", black = "account", result = "1-0"),
                ),
                "BLACK",
                "account",
            )

        assertEquals("LOSS", normalized.outcome?.name)
        assertEquals("CORROBORATED", normalized.sideCorroboration.name)
        assertNull(normalized.exclusionReason)
    }

    @Test
    fun `unavailable attribution remains scoreable from authoritative stored color`() {
        val normalized =
            normalizer.normalize(
                game(
                    result = "resigned",
                    whiteUsername = "old-white",
                    blackUsername = "old-black",
                    pgn = header(white = "other-a", black = "other-b", result = "0-1"),
                ),
                "BLACK",
                "account",
            )

        assertEquals("WIN", normalized.outcome?.name)
        assertEquals("UNAVAILABLE", normalized.sideCorroboration.name)
    }

    @Test
    fun `opposite-side and both-side corroboration conflicts do not alter outcomes`() {
        val opposite =
            normalizer.normalize(
                game(
                    result = "win",
                    whiteUsername = "account",
                    blackUsername = "other",
                    pgn = header(white = "other", black = "other", result = "1-0"),
                ),
                "BLACK",
                "account",
            )
        val both =
            normalizer.normalize(
                game(
                    result = "win",
                    whiteUsername = "account",
                    blackUsername = "account",
                    pgn = header(white = "account", black = "account", result = "1-0"),
                ),
                "BLACK",
                "account",
            )

        listOf(opposite, both).forEach {
            assertEquals("LOSS", it.outcome?.name)
            assertEquals("CONFLICT", it.sideCorroboration.name)
            assertNull(it.exclusionReason)
        }
    }

    @Test
    fun `invalid authoritative color is excluded rather than guessed from names`() {
        val normalized =
            normalizer.normalize(
                game(
                    result = "win",
                    whiteUsername = "account",
                    blackUsername = "other",
                    pgn = header(white = "account", black = "other", result = "1-0"),
                ),
                "BOTH",
                "account",
            )

        assertNull(normalized.outcome)
        assertEquals("INVALID_AUTHORITATIVE_COLOR", normalized.exclusionReason?.name)
    }

    @Test
    fun `accepts absent or Standard variant and marks a supported header with another variant ineligible`() {
        val absent = normalizer.normalize(game(result = "win", pgn = "1. e4 e5"), "WHITE", "account")
        val standard =
            normalizer.normalize(
                game(result = "win", pgn = header(variant = "sTaNdArD", result = "1-0")),
                "WHITE",
                "account",
            )
        val chess960 =
            normalizer.normalize(
                game(result = "win", pgn = header(variant = "Chess960", result = "1-0")),
                "WHITE",
                "account",
            )

        assertEquals("WIN", absent.outcome?.name)
        assertEquals("WIN", standard.outcome?.name)
        assertEquals("UNSUPPORTED_VARIANT", chess960.ineligibilityReason?.name)
        assertNull(chess960.outcome)
    }

    @Test
    fun `malformed or limit-exceeded header is an exclusion even with a recognized source result`() {
        val malformed =
            normalizer.normalize(
                game(result = "win", pgn = "[White \"account\"\n\n1. e4"),
                "WHITE",
                "account",
            )
        val limitExceeded =
            normalizer.normalize(
                game(result = "win", pgn = "[Event \"${"x".repeat(1_015)}\"]\n\n"),
                "WHITE",
                "account",
            )

        assertEquals("MALFORMED_PGN_HEADER", malformed.exclusionReason?.name)
        assertEquals("PGN_HEADER_LIMIT_EXCEEDED", limitExceeded.exclusionReason?.name)
        assertNull(malformed.outcome)
        assertNull(limitExceeded.outcome)
    }

    private fun game(
        result: String?,
        pgn: String = "1. e4 e5",
        whiteUsername: String? = null,
        blackUsername: String? = null,
    ): Game =
        Game(
            chessAccount =
                ChessAccount(
                    user = AppUser(email = "outcome@test.com"),
                    platform = "CHESS_COM",
                    username = "account",
                ),
            platformGameId = "game-${nextGameId++}",
            pgn = pgn,
            timeControl = "blitz",
            result = result,
            whiteUsername = whiteUsername,
            blackUsername = blackUsername,
        )

    private fun header(
        white: String? = "account",
        black: String? = "opponent",
        result: String? = null,
        variant: String? = null,
    ): String =
        buildList {
            white?.let { add("[White \"$it\"]") }
            black?.let { add("[Black \"$it\"]") }
            result?.let { add("[Result \"$it\"]") }
            variant?.let { add("[Variant \"$it\"]") }
        }.joinToString("\n", postfix = "\n\n1. e4")

    companion object {
        private var nextGameId = 1
    }
}
