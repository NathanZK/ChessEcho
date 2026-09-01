package com.chessecho.service

import com.chessecho.config.PracticalEvidenceProperties
import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PracticalEvidenceServiceTest {
    private val asOf = Instant.parse("2026-09-01T12:00:00Z")

    @Test
    fun `rechecks account position color and exact SAN for separate scopes`() {
        val account = account("alice")
        val otherAccount = account("other")
        val position = position("scope")
        val otherPosition = position("other")
        val scopedGame = game(account, "scoped", "win")
        val secondSanGame = game(account, "second-san", "resigned")
        val rows =
            listOf(
                occurrence(account, position, scopedGame, "WHITE", "Qh5"),
                occurrence(account, position, secondSanGame, "WHITE", "Nf3"),
                occurrence(otherAccount, position, game(otherAccount, "other-account", "win"), "WHITE", "Qh5"),
                occurrence(account, otherPosition, game(account, "other-position", "win"), "WHITE", "Qh5"),
                occurrence(account, position, game(account, "other-color", "win"), "BLACK", "Qh5"),
            )
        val positionScope = PracticalEvidenceScope(account.id, position.id, "WHITE")
        val qh5Scope = PracticalEvidenceScope(account.id, position.id, "WHITE", decisionSan = "Qh5")
        val nf3Scope = PracticalEvidenceScope(account.id, position.id, "WHITE", decisionSan = "Nf3")

        val summaries =
            service().summarize(
                chessAccountId = account.id,
                accountUsername = account.username,
                occurrences = rows,
                scopes = setOf(positionScope, qh5Scope, nf3Scope),
                asOf = asOf,
            )

        assertEquals(2, summaries.getValue(positionScope).candidateGames)
        assertEquals(1, summaries.getValue(qh5Scope).candidateGames)
        assertEquals(1, summaries.getValue(nf3Scope).candidateGames)
        assertEquals(1, summaries.getValue(qh5Scope).wins)
        assertEquals(1, summaries.getValue(nf3Scope).losses)
    }

    @Test
    fun `deduplicates repeated position and SAN occurrences by Game id`() {
        val account = account("alice")
        val position = position("dedupe")
        val repeatedGame = game(account, "repeat", "win")
        val distinctGame = game(account, "distinct", "agreed")
        val rows =
            listOf(
                occurrence(account, position, repeatedGame, "WHITE", "Qh5", ply = 9),
                occurrence(account, position, repeatedGame, "WHITE", "Qh5", ply = 17),
                occurrence(account, position, repeatedGame, "WHITE", "Nf3", ply = 23),
                occurrence(account, position, distinctGame, "WHITE", "Qh5", ply = 11),
            )
        val positionScope = PracticalEvidenceScope(account.id, position.id, "WHITE")
        val decisionScope = PracticalEvidenceScope(account.id, position.id, "WHITE", decisionSan = "Qh5")

        val summaries =
            service().summarize(
                account.id,
                account.username,
                rows,
                setOf(positionScope, decisionScope),
                asOf,
            )

        val positionSummary = summaries.getValue(positionScope)
        val decisionSummary = summaries.getValue(decisionScope)
        assertEquals(2, positionSummary.candidateGames)
        assertEquals(2, positionSummary.eligibleGames)
        assertEquals(1, positionSummary.wins)
        assertEquals(1, positionSummary.draws)
        assertEquals(2, decisionSummary.candidateGames)
        assertEquals(2, decisionSummary.eligibleGames)
    }

    @Test
    fun `position evidence includes scoped SANs outside one decision scope`() {
        val account = account("alice")
        val position = position("all-position-sans")
        val selectedSanGame = game(account, "selected-san", "win")
        val otherSanGame = game(account, "other-san", "resigned")
        val rows =
            listOf(
                occurrence(account, position, selectedSanGame, "WHITE", "Qh5"),
                occurrence(account, position, otherSanGame, "WHITE", "other-SAN"),
            )
        val positionScope = PracticalEvidenceScope(account.id, position.id, "WHITE")
        val decisionScope = PracticalEvidenceScope(account.id, position.id, "WHITE", "Qh5")

        val summaries =
            service().summarize(
                account.id,
                account.username,
                rows,
                setOf(positionScope, decisionScope),
                asOf,
            )

        assertEquals(2, summaries.getValue(positionScope).candidateGames)
        assertEquals(2, summaries.getValue(positionScope).eligibleGames)
        assertEquals(1, summaries.getValue(positionScope).wins)
        assertEquals(1, summaries.getValue(positionScope).losses)
        assertEquals(1, summaries.getValue(decisionScope).eligibleGames)
    }

    @Test
    fun `keeps eligible ineligible and excluded counts separate and preserves partition`() {
        val account = account("alice")
        val position = position("partition")
        val rows =
            listOf(
                occurrence(account, position, game(account, "win", "win"), "WHITE", "Qh5"),
                occurrence(account, position, game(account, "draw", "agreed"), "WHITE", "Qh5"),
                occurrence(account, position, game(account, "loss", "resigned"), "WHITE", "Qh5"),
                occurrence(
                    account,
                    position,
                    game(account, "variant", "win", pgn = header(variant = "Chess960", result = "1-0")),
                    "WHITE",
                    "Qh5",
                ),
                occurrence(account, position, game(account, "unknown", "unknown", pgn = header()), "WHITE", "Qh5"),
                occurrence(
                    account,
                    position,
                    game(
                        account,
                        "conflict",
                        "win",
                        whiteUsername = "alice",
                        blackUsername = "alice",
                        pgn = header(white = "alice", black = "alice", result = "1-0"),
                    ),
                    "WHITE",
                    "Qh5",
                ),
            )
        val scope = PracticalEvidenceScope(account.id, position.id, "WHITE")

        val summary =
            service().summarize(account.id, account.username, rows, setOf(scope), asOf).getValue(scope)

        assertEquals(6, summary.candidateGames)
        assertEquals(4, summary.eligibleGames)
        assertEquals(1, summary.ineligibleGames)
        assertEquals(1, summary.excludedGames)
        assertEquals(summary.candidateGames, summary.eligibleGames + summary.ineligibleGames + summary.excludedGames)
        assertEquals(2, summary.wins)
        assertEquals(1, summary.draws)
        assertEquals(1, summary.losses)
        assertEquals(0.625, summary.scoreRate)
        assertEquals(1, summary.sideCorroborationConflictGames)
    }

    @Test
    fun `null observation window includes all history while configured window uses one asOf`() {
        val account = account("alice")
        val position = position("window")
        val recent =
            game(
                account,
                "recent",
                "win",
                playedAt = asOf.minusSeconds(10 * 86_400L),
            )
        val old =
            game(
                account,
                "old",
                "win",
                playedAt = asOf.minusSeconds(31 * 86_400L),
            )
        val atBoundary =
            game(
                account,
                "at-boundary",
                "win",
                playedAt = asOf.minusSeconds(30 * 86_400L),
            )
        val rows =
            listOf(
                occurrence(account, position, recent, "WHITE", "Qh5"),
                occurrence(account, position, atBoundary, "WHITE", "Qh5"),
                occurrence(account, position, old, "WHITE", "Qh5"),
            )
        val scope = PracticalEvidenceScope(account.id, position.id, "WHITE")

        val allHistory =
            service().summarize(account.id, account.username, rows, setOf(scope), asOf).getValue(scope)
        val thirtyDays =
            service(observationWindowDays = 30)
                .summarize(account.id, account.username, rows, setOf(scope), asOf)
                .getValue(scope)

        assertEquals(3, allHistory.eligibleGames)
        assertEquals(0, allHistory.ineligibleGames)
        assertEquals(2, thirtyDays.eligibleGames)
        assertEquals(1, thirtyDays.ineligibleGames)
        assertEquals(0, thirtyDays.excludedGames)
    }

    @Test
    fun `legacy null time control remains in the all imported time-control cohort`() {
        val account = account("alice")
        val position = position("legacy-time-control")
        val legacyGame = game(account, "legacy", "win", timeControl = null)
        val scope = PracticalEvidenceScope(account.id, position.id, "WHITE")

        val summary =
            service().summarize(
                account.id,
                account.username,
                listOf(occurrence(account, position, legacyGame, "WHITE", "Qh5")),
                setOf(scope),
                asOf,
            ).getValue(scope)

        assertEquals(1, summary.eligibleGames)
        assertEquals(0, summary.ineligibleGames)
    }

    @Test
    fun `zero eligible games has null score rate and a complete partition`() {
        val account = account("alice")
        val position = position("zero")
        val scope = PracticalEvidenceScope(account.id, position.id, "WHITE")
        val unknown = game(account, "unknown-only", "unknown", pgn = header())

        val summary =
            service().summarize(
                account.id,
                account.username,
                listOf(occurrence(account, position, unknown, "WHITE", "Qh5")),
                setOf(scope),
                asOf,
            ).getValue(scope)

        assertEquals(1, summary.candidateGames)
        assertEquals(0, summary.eligibleGames)
        assertEquals(0, summary.ineligibleGames)
        assertEquals(1, summary.excludedGames)
        assertNull(summary.scoreRate)
    }

    @Test
    fun `memoizes normalization per Game id and authoritative color within one request`() {
        val account = account("alice")
        val position = position("memo")
        val repeatedGame = game(account, "memo-game", "win")
        val normalizer = spy(GameOutcomeNormalizer(PgnHeaderTagReader()))
        val service = PracticalEvidenceService(normalizer, properties())
        val positionScope = PracticalEvidenceScope(account.id, position.id, "WHITE")
        val decisionScope = PracticalEvidenceScope(account.id, position.id, "WHITE", "Qh5")
        val rows =
            listOf(
                occurrence(account, position, repeatedGame, "WHITE", "Qh5", ply = 1),
                occurrence(account, position, repeatedGame, "WHITE", "Qh5", ply = 9),
            )

        service.summarize(
            account.id,
            account.username,
            rows,
            setOf(positionScope, decisionScope),
            asOf,
        )

        verify(normalizer, times(1)).normalize(eq(repeatedGame), eq("WHITE"), eq(account.username))
    }

    private fun service(observationWindowDays: Long? = null): PracticalEvidenceService =
        PracticalEvidenceService(
            GameOutcomeNormalizer(PgnHeaderTagReader()),
            properties(observationWindowDays),
        )

    private fun properties(observationWindowDays: Long? = null): PracticalEvidenceProperties =
        PracticalEvidenceProperties(
            rankingEnabled = false,
            observationWindowDays = observationWindowDays,
            policyVersion = "test-uncalibrated",
        )

    private fun account(username: String): ChessAccount =
        ChessAccount(
            user = AppUser(email = "$username-${nextId++}@test.com"),
            platform = "CHESS_COM",
            username = username,
        )

    private fun position(suffix: String): Position = Position(hash = "hash-$suffix-${nextId++}", fen = "fen-$suffix")

    private fun game(
        account: ChessAccount,
        id: String,
        result: String?,
        pgn: String = header(result = "1-0"),
        timeControl: String? = "blitz",
        playedAt: Instant? = asOf.minusSeconds(86_400),
        whiteUsername: String? = account.username,
        blackUsername: String? = "opponent",
    ): Game =
        Game(
            chessAccount = account,
            platformGameId = id,
            pgn = pgn,
            timeControl = timeControl,
            playedAt = playedAt,
            result = result,
            whiteUsername = whiteUsername,
            blackUsername = blackUsername,
        )

    private fun occurrence(
        account: ChessAccount,
        position: Position,
        game: Game,
        color: String,
        san: String,
        ply: Int = 1,
    ): PositionOccurrence =
        PositionOccurrence(
            game = game,
            position = position,
            chessAccount = account,
            plyNumber = ply,
            movePlayed = san,
            playerColor = color,
        )

    private fun header(
        white: String? = "alice",
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
        private var nextId = 1
    }
}
