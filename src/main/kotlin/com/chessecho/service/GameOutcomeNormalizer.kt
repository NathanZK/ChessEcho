package com.chessecho.service

import com.chessecho.domain.Game
import org.springframework.stereotype.Component

enum class PracticalOutcome {
    WIN,
    DRAW,
    LOSS,
}

enum class PracticalEvidenceExclusionReason {
    INVALID_AUTHORITATIVE_COLOR,
    MALFORMED_PGN_HEADER,
    PGN_HEADER_LIMIT_EXCEEDED,
    UNNORMALIZABLE_RESULT,
}

enum class PracticalEvidenceIneligibilityReason {
    UNSUPPORTED_VARIANT,
}

enum class SideCorroboration {
    CORROBORATED,
    UNAVAILABLE,
    CONFLICT,
}

data class NormalizedGameOutcome(
    val outcome: PracticalOutcome? = null,
    val exclusionReason: PracticalEvidenceExclusionReason? = null,
    val ineligibilityReason: PracticalEvidenceIneligibilityReason? = null,
    val sideCorroboration: SideCorroboration = SideCorroboration.UNAVAILABLE,
)

@Component
class GameOutcomeNormalizer(
    private val pgnHeaderTagReader: PgnHeaderTagReader,
) {
    fun normalize(
        game: Game,
        authoritativeColor: String,
        accountUsername: String,
    ): NormalizedGameOutcome {
        if (authoritativeColor != WHITE && authoritativeColor != BLACK) {
            return NormalizedGameOutcome(
                exclusionReason = PracticalEvidenceExclusionReason.INVALID_AUTHORITATIVE_COLOR,
            )
        }

        val tags = pgnHeaderTagReader.read(game.pgn)
        if (tags.status == PgnHeaderStatus.MALFORMED) {
            return NormalizedGameOutcome(
                exclusionReason = PracticalEvidenceExclusionReason.MALFORMED_PGN_HEADER,
            )
        }
        if (tags.status == PgnHeaderStatus.LIMIT_EXCEEDED) {
            return NormalizedGameOutcome(
                exclusionReason = PracticalEvidenceExclusionReason.PGN_HEADER_LIMIT_EXCEEDED,
            )
        }

        val corroboration =
            sideCorroboration(
                authoritativeColor = authoritativeColor,
                accountUsername = accountUsername,
                persistedWhite = game.whiteUsername,
                persistedBlack = game.blackUsername,
                pgnWhite = tags.white,
                pgnBlack = tags.black,
            )

        if (tags.variant != null && !tags.variant.equals(STANDARD_VARIANT, ignoreCase = true)) {
            return NormalizedGameOutcome(
                ineligibilityReason = PracticalEvidenceIneligibilityReason.UNSUPPORTED_VARIANT,
                sideCorroboration = corroboration,
            )
        }

        val whiteOutcome =
            sourceOutcome(game.result)
                ?: pgnOutcome(tags.result)
                ?: return NormalizedGameOutcome(
                    exclusionReason = PracticalEvidenceExclusionReason.UNNORMALIZABLE_RESULT,
                    sideCorroboration = corroboration,
                )

        return NormalizedGameOutcome(
            outcome =
                if (authoritativeColor == WHITE) {
                    whiteOutcome
                } else {
                    whiteOutcome.inverted()
                },
            sideCorroboration = corroboration,
        )
    }

    private fun sourceOutcome(result: String?): PracticalOutcome? =
        when (result?.trim()?.lowercase()) {
            "win" -> PracticalOutcome.WIN
            "checkmated", "resigned", "timeout", "abandoned", "lose" -> PracticalOutcome.LOSS
            "agreed", "repetition", "stalemate", "insufficient", "50move", "timevsinsufficient" ->
                PracticalOutcome.DRAW

            else -> null
        }

    private fun pgnOutcome(result: String?): PracticalOutcome? =
        when (result) {
            "1-0" -> PracticalOutcome.WIN
            "0-1" -> PracticalOutcome.LOSS
            "1/2-1/2" -> PracticalOutcome.DRAW
            else -> null
        }

    private fun sideCorroboration(
        authoritativeColor: String,
        accountUsername: String,
        persistedWhite: String?,
        persistedBlack: String?,
        pgnWhite: String?,
        pgnBlack: String?,
    ): SideCorroboration {
        val matchesWhite =
            persistedWhite.matchesUsername(accountUsername) ||
                pgnWhite.matchesUsername(accountUsername)
        val matchesBlack =
            persistedBlack.matchesUsername(accountUsername) ||
                pgnBlack.matchesUsername(accountUsername)

        return when {
            !matchesWhite && !matchesBlack -> SideCorroboration.UNAVAILABLE
            matchesWhite.xor(matchesBlack) &&
                (
                    (matchesWhite && authoritativeColor == WHITE) ||
                        (matchesBlack && authoritativeColor == BLACK)
                ) -> SideCorroboration.CORROBORATED

            else -> SideCorroboration.CONFLICT
        }
    }

    private fun String?.matchesUsername(accountUsername: String): Boolean = this?.equals(accountUsername, ignoreCase = true) == true

    private fun PracticalOutcome.inverted(): PracticalOutcome =
        when (this) {
            PracticalOutcome.WIN -> PracticalOutcome.LOSS
            PracticalOutcome.LOSS -> PracticalOutcome.WIN
            PracticalOutcome.DRAW -> PracticalOutcome.DRAW
        }

    companion object {
        private const val WHITE = "WHITE"
        private const val BLACK = "BLACK"
        private const val STANDARD_VARIANT = "Standard"
    }
}
