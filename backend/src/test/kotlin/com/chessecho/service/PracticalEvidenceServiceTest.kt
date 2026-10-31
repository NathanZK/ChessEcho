package com.chessecho.service

import com.chessecho.config.PracticalEvidenceProperties
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
            service()
                .summarize(
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
            service()
                .summarize(
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

    @Test
    fun `scores multi-draw scenarios with exact scoreRate weighting`() {
        val account = account("alice")
        val position = position("draws")

        // 1 win, 2 draws, 1 loss -> eligible = 4 -> (1 + 0.5 * 2) / 4 = 0.5
        val gWin = game(account, "g-win", "win")
        val gDraw1 = game(account, "g-d1", "agreed")
        val gDraw2 = game(account, "g-d2", "repetition")
        val gLoss = game(account, "g-loss", "checkmated")

        val rows4 =
            listOf(
                occurrence(account, position, gWin, "WHITE", "e4"),
                occurrence(account, position, gDraw1, "WHITE", "e4"),
                occurrence(account, position, gDraw2, "WHITE", "e4"),
                occurrence(account, position, gLoss, "WHITE", "e4"),
            )
        val scope4 = PracticalEvidenceScope(account.id, position.id, "WHITE")

        val summary4 = service().summarize(account.id, account.username, rows4, setOf(scope4), asOf).getValue(scope4)
        assertEquals(4, summary4.eligibleGames)
        assertEquals(1, summary4.wins)
        assertEquals(2, summary4.draws)
        assertEquals(1, summary4.losses)
        assertEquals(0.5, summary4.scoreRate)

        // 0 wins, 3 draws, 1 loss -> eligible = 4 -> (0 + 0.5 * 3) / 4 = 0.375
        val gDraw3 = game(account, "g-d3", "stalemate")
        val rows3Draws =
            listOf(
                occurrence(account, position, gDraw1, "WHITE", "e4"),
                occurrence(account, position, gDraw2, "WHITE", "e4"),
                occurrence(account, position, gDraw3, "WHITE", "e4"),
                occurrence(account, position, gLoss, "WHITE", "e4"),
            )
        val summary3Draws = service().summarize(account.id, account.username, rows3Draws, setOf(scope4), asOf).getValue(scope4)
        assertEquals(4, summary3Draws.eligibleGames)
        assertEquals(0, summary3Draws.wins)
        assertEquals(3, summary3Draws.draws)
        assertEquals(1, summary3Draws.losses)
        assertEquals(0.375, summary3Draws.scoreRate)
    }

    @Test
    fun `ScopeOutcomeTally toSummary computes score rate correctly`() {
        val scope = PracticalEvidenceScope(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "WHITE")
        val tally =
            ScopeOutcomeTally(
                wins = 2,
                draws = 1,
                losses = 1,
                ineligibleGames = 0,
                excludedGames = 0,
                sideCorroborationConflictGames = 0,
            )
        val summary = tally.toSummary(scope, candidateGames = 4)
        assertEquals(4, summary.eligibleGames)
        assertEquals(0.625, summary.scoreRate!!, 0.001)
    }

    @Test
    fun `ScopeOutcomeTally toSummary returns null score rate when eligible games is zero`() {
        val scope = PracticalEvidenceScope(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "WHITE")
        val tally =
            ScopeOutcomeTally(
                wins = 0,
                draws = 0,
                losses = 0,
                ineligibleGames = 2,
                excludedGames = 1,
                sideCorroborationConflictGames = 0,
            )
        val summary = tally.toSummary(scope, candidateGames = 3)
        assertEquals(0, summary.eligibleGames)
        assertNull(summary.scoreRate)
    }

    @Test
    fun `summarizeByTimeControl partitions by time control with deduplication and perspective inversion`() {
        val account = account("alice")
        val position = position("tc-summary")

        // Bullet game: White win
        val bulletGame = game(account, "bullet-1", "win", header(result = "1-0"), timeControl = "bullet")

        // Blitz game 1: White win with 2 occurrences at different plies (same game)
        val blitzGame1 = game(account, "blitz-1", "win", header(result = "1-0"), timeControl = "blitz")

        // Blitz game 2: White draw
        val blitzGame2 = game(account, "blitz-2", "agreed", header(result = "1/2-1/2"), timeControl = "blitz")

        // Daily game: Should be excluded from standard partitions
        val dailyGame = game(account, "daily-1", "win", header(result = "1-0"), timeControl = "daily")

        val occurrences =
            listOf(
                occurrence(account, position, bulletGame, "WHITE", "e4", ply = 1),
                occurrence(account, position, blitzGame1, "WHITE", "e4", ply = 3),
                // Duplicate game:
                occurrence(account, position, blitzGame1, "WHITE", "Nf3", ply = 7),
                occurrence(account, position, blitzGame2, "WHITE", "e4", ply = 5),
                occurrence(account, position, dailyGame, "WHITE", "e4", ply = 1),
            )

        val summaries =
            service().summarizeByTimeControl(
                chessAccountId = account.id,
                accountUsername = account.username,
                occurrences = occurrences,
                playerColor = "WHITE",
                asOf = asOf,
            )

        // Bullet: 1 game, 1 win, score rate 1.0
        val bullet = summaries["BULLET"]
        assertEquals(1, bullet?.eligibleGames)
        assertEquals(1, bullet?.wins)
        assertEquals(0, bullet?.draws)
        assertEquals(0, bullet?.losses)
        assertEquals(1.0, bullet?.scoreRate)

        // Blitz: 2 distinct games (blitzGame1 deduplicated, blitzGame2 draw)
        val blitz = summaries["BLITZ"]
        assertEquals(2, blitz?.eligibleGames)
        assertEquals(1, blitz?.wins)
        assertEquals(1, blitz?.draws)
        assertEquals(0, blitz?.losses)
        assertEquals(0.75, blitz?.scoreRate)

        // Daily: excluded from partitions
        assertNull(summaries["DAILY"])
    }

    @Test
    fun `summarizeByTimeControl inverts perspective for black player`() {
        val account = account("bob")
        val position = position("tc-black")

        // Game 1: 1-0 result (White win -> Black loss)
        val game1 =
            game(
                account,
                "rapid-1",
                "lose",
                header(white = "opponent", black = "bob", result = "1-0"),
                timeControl = "rapid",
                whiteUsername = "opponent",
                blackUsername = "bob",
            )

        // Game 2: 0-1 result (Black win -> Black win)
        val game2 =
            game(
                account,
                "rapid-2",
                "win",
                header(white = "opponent", black = "bob", result = "0-1"),
                timeControl = "rapid",
                whiteUsername = "opponent",
                blackUsername = "bob",
            )

        // Game 3: 1/2-1/2 result (Draw)
        val game3 =
            game(
                account,
                "rapid-3",
                "agreed",
                header(white = "opponent", black = "bob", result = "1/2-1/2"),
                timeControl = "rapid",
                whiteUsername = "opponent",
                blackUsername = "bob",
            )

        val occurrences =
            listOf(
                occurrence(account, position, game1, "BLACK", "e5"),
                occurrence(account, position, game2, "BLACK", "e5"),
                occurrence(account, position, game3, "BLACK", "e5"),
            )

        val summaries =
            service().summarizeByTimeControl(
                chessAccountId = account.id,
                accountUsername = account.username,
                occurrences = occurrences,
                playerColor = "BLACK",
                asOf = asOf,
            )

        val rapid = summaries["RAPID"]
        assertEquals(3, rapid?.eligibleGames)
        assertEquals(1, rapid?.wins)
        assertEquals(1, rapid?.draws)
        assertEquals(1, rapid?.losses)
        assertEquals(0.5, rapid?.scoreRate)
    }

    @Test
    fun `summarizeByTimeControl filters by account, playerColor, positionId, and observation window`() {
        val account = account("alice")
        val otherAccount = account("bob")
        val position = position("filter-tc")
        val otherPosition = position("other-tc")

        val validGame = game(account, "blitz-valid", "win", timeControl = "blitz", playedAt = asOf.minusSeconds(86_400))
        val otherAccountGame = game(otherAccount, "blitz-other-acc", "win", timeControl = "blitz", playedAt = asOf.minusSeconds(86_400))
        val otherColorGame = game(account, "blitz-black", "win", timeControl = "blitz", playedAt = asOf.minusSeconds(86_400))
        val otherPosGame = game(account, "blitz-other-pos", "win", timeControl = "blitz", playedAt = asOf.minusSeconds(86_400))
        val oldGame = game(account, "blitz-old", "win", timeControl = "blitz", playedAt = asOf.minusSeconds(40 * 86_400L))

        val occurrences =
            listOf(
                occurrence(account, position, validGame, "WHITE", "e4"),
                occurrence(otherAccount, position, otherAccountGame, "WHITE", "e4"),
                occurrence(account, position, otherColorGame, "BLACK", "e4"),
                occurrence(account, otherPosition, otherPosGame, "WHITE", "e4"),
                occurrence(account, position, oldGame, "WHITE", "e4"),
            )

        val summaries =
            service(observationWindowDays = 30).summarizeByTimeControl(
                chessAccountId = account.id,
                accountUsername = account.username,
                occurrences = occurrences,
                playerColor = "WHITE",
                asOf = asOf,
                positionId = position.id,
            )

        val blitz = summaries["BLITZ"]
        assertEquals(1, blitz?.eligibleGames)
        assertEquals(1, blitz?.wins)
        assertEquals(0, blitz?.draws)
        assertEquals(0, blitz?.losses)
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
