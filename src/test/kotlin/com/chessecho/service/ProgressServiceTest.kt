package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import com.chessecho.dto.ProgressIntervalState
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProgressServiceTest {
    private val accountOwnershipService = mock<AccountOwnershipService>()
    private val occurrenceRepository = mock<PositionOccurrenceRepository>()
    private val schedulingEventRepository = mock<PuzzleSchedulingEventRepository>()
    private val analysisRepository = mock<EngineAnalysisRepository>()
    private val service =
        ProgressService(
            accountOwnershipService,
            occurrenceRepository,
            schedulingEventRepository,
            analysisRepository,
            GameOutcomeNormalizer(PgnHeaderTagReader()),
        )
    private val user = AppUser()
    private val account = ChessAccount(platform = "CHESS_COM", username = "player")
    private val position = Position(hash = "progress", fen = "8/8/8/8/8/8/8/8 w - -")
    private val principal = AuthenticatedPrincipal(user.id, devPrincipal = false)
    private val firstPlayedAt = Instant.parse("2026-09-01T12:00:00Z")

    @BeforeEach
    fun connectSelectedAccount() {
        whenever(accountOwnershipService.requireConnectedAccountForProgress(account.id, principal)).thenReturn(account)
    }

    @Test
    fun `selected threshold classifies 23 of 26 dated encounters in baseline and interval`() {
        val checkpointAt = firstPlayedAt.plusSeconds(2_000)
        val encounters =
            (0 until 52).map { index ->
                occurrence(
                    PlayerColor.WHITE,
                    "win",
                    index,
                    playedAt = if (index < 26) firstPlayedAt.plusSeconds(index.toLong()) else checkpointAt.plusSeconds(index.toLong()),
                )
            }
        stubOccurrences(PlayerColor.WHITE, encounters)
        stubEvents(PlayerColor.WHITE, listOf(checkpoint(checkpointAt)))
        val analysis = stubAnalysis()
        encounters.forEachIndexed { index, encounter ->
            analysis.moveEvaluations.add(
                MoveEvaluation(
                    engineAnalysis = analysis,
                    move = encounter.movePlayed,
                    evalCp = 0,
                    evalLossFromBest = if (index % 26 < 23) 0.34 else 0.29,
                ),
            )
        }

        val response = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.3)

        assertEquals(26, response.baseline?.sourceEncounterCount)
        assertEquals(88.461538, response.baseline!!.mistakeRate, 0.000001)
        assertEquals(26, response.points.single().attempts)
        assertEquals(88.461538, response.points.single().mistakeRate, 0.000001)
    }

    @ParameterizedTest
    @CsvSource("0.3, 0.3, 100.0", "0.3, 0.299, 0.0", "0.8, 0.34, 0.0", "0.01, 0.01, 100.0")
    fun `selected threshold includes equality and excludes lower losses`(
        threshold: Double,
        loss: Double,
        expectedRate: Double,
    ) {
        stubOccurrences(PlayerColor.WHITE, listOf(occurrence(PlayerColor.WHITE, "win")))
        val analysis = stubAnalysis()
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "move-0", evalCp = -100, evalLossFromBest = loss),
        )

        val baseline = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = threshold).baseline!!

        assertEquals(1, baseline.sourceEncounterCount)
        assertEquals(expectedRate, baseline.mistakeRate)
    }

    @ParameterizedTest
    @CsvSource("50, 20, 100.0", "50, 21, 0.0", "50, 60, 0.0")
    fun `missing direct loss uses Weaknesses best move fallback`(
        bestCp: Int,
        resultCp: Int,
        expectedRate: Double,
    ) {
        stubOccurrences(PlayerColor.WHITE, listOf(occurrence(PlayerColor.WHITE, "win")))
        val analysis = stubAnalysis(bestCp)
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "move-0", evalCp = resultCp, evalLossFromBest = null),
        )

        val baseline = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.3).baseline!!

        assertEquals(1, baseline.sourceEncounterCount)
        assertEquals(expectedRate, baseline.mistakeRate)
    }

    @Test
    fun `unavailable evaluations remain attempts while undated mistakes are excluded`() {
        val dated = (0..4).map { occurrence(PlayerColor.WHITE, "win", it) }
        val undated = occurrence(PlayerColor.WHITE, "win", 5, playedAt = null)
        stubOccurrences(PlayerColor.WHITE, dated + undated)
        val analysis = stubAnalysis(null)
        listOf(
            Triple("move-0", null, 0.34),
            Triple("move-1", null, null),
            Triple("move-2", -100, null),
            Triple("move-4", null, 0.0),
            Triple("move-5", null, 1.0),
        ).forEach { (move, evalCp, loss) ->
            analysis.moveEvaluations.add(
                MoveEvaluation(engineAnalysis = analysis, move = move, evalCp = evalCp, evalLossFromBest = loss),
            )
        }

        val response = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.3)

        assertEquals(5, response.baseline?.sourceEncounterCount)
        assertEquals(20.0, response.baseline?.mistakeRate)
        assertEquals(1, response.excludedUndatedEncounters)
    }

    @Test
    fun `missing analysis retains all dated encounters as non mistakes`() {
        stubOccurrences(PlayerColor.WHITE, listOf(occurrence(PlayerColor.WHITE, "win")))

        val baseline = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.3).baseline!!

        assertEquals(1, baseline.sourceEncounterCount)
        assertEquals(0.0, baseline.mistakeRate)
    }

    @ParameterizedTest
    @CsvSource(value = ["0, missing", "missing, -100", "missing, missing"], nullValues = ["missing"])
    fun `fallback with either evaluation input missing does not count a mistake`(
        bestCp: Int?,
        resultCp: Int?,
    ) {
        stubOccurrences(PlayerColor.WHITE, listOf(occurrence(PlayerColor.WHITE, "win")))
        val analysis = stubAnalysis(bestCp)
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "move-0", evalCp = resultCp, evalLossFromBest = null),
        )

        val baseline = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.3).baseline!!

        assertEquals(1, baseline.sourceEncounterCount)
        assertEquals(0.0, baseline.mistakeRate)
    }

    @Test
    fun `untrained dated history does not create interval observations`() {
        stubOccurrences(
            PlayerColor.WHITE,
            listOf(
                occurrence(PlayerColor.WHITE, "win"),
                occurrence(PlayerColor.WHITE, "resigned", index = 1),
            ),
        )

        val response = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(emptyList(), response.points)
        assertEquals(ProgressIntervalState.NO_CHECKPOINT, response.currentIntervalState)
        assertEquals(2, response.baseline?.sourceEncounterCount)
        assertEquals(0, response.excludedUndatedEncounters)
    }

    @Test
    fun `undated encounters are explicitly excluded instead of using creation time`() {
        val solved = checkpoint(firstPlayedAt)
        stubOccurrences(
            PlayerColor.WHITE,
            listOf(
                occurrence(PlayerColor.WHITE, "win", playedAt = null),
                occurrence(PlayerColor.WHITE, "win", index = 1, playedAt = null),
            ),
        )
        stubEvents(PlayerColor.WHITE, listOf(solved))

        val response = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(null, response.baseline)
        assertEquals(emptyList(), response.points)
        assertEquals(2, response.excludedUndatedEncounters)
        assertEquals(ProgressIntervalState.OPEN_AWAITING_EVIDENCE, response.currentIntervalState)
    }

    @Test
    fun `all undated history without checkpoints has no baseline and no interval observations`() {
        stubOccurrences(
            PlayerColor.WHITE,
            listOf(
                occurrence(PlayerColor.WHITE, "win", 0, playedAt = null),
                occurrence(PlayerColor.WHITE, "resigned", 1, playedAt = null),
            ),
        )

        val response = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(null, response.baseline)
        assertEquals(emptyList(), response.points)
        assertEquals(2, response.excludedUndatedEncounters)
        assertEquals(ProgressIntervalState.NO_CHECKPOINT, response.currentIntervalState)
    }

    @Test
    fun `repeated positions at distinct plies remain separate source encounters`() {
        val game =
            Game(
                chessAccount = account,
                platformGameId = "repeated-position",
                pgn = """[Result "*"]""",
                result = "win",
                playedAt = firstPlayedAt,
                whiteUsername = account.username,
                blackUsername = "opponent",
            )
        val repeatedOccurrences =
            listOf(2, 18).mapIndexed { index, ply ->
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = account,
                    plyNumber = ply,
                    movePlayed = "move-$index",
                    playerColor = PlayerColor.WHITE.name,
                )
            }
        stubOccurrences(PlayerColor.WHITE, repeatedOccurrences)

        val response = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(2, response.baseline?.sourceEncounterCount)
        assertEquals(100.0, response.baseline?.winRate)
        assertEquals(emptyList(), response.points)
    }

    @Test
    fun `assessment joins opposite rate directions with and`() {
        val response =
            assessedResponse(
                baseline = listOf(true to true, false to false),
                latest = listOf(false to true, false to true),
            )

        assertEquals(
            "You are making fewer mistakes at this position, and your win rate has increased.",
            response.assessment,
        )
    }

    @Test
    fun `assessment joins mistake increase and win rate decrease with and`() {
        val response =
            assessedResponse(
                baseline = listOf(false to true, false to true),
                latest = listOf(true to false, true to false),
            )

        assertEquals(
            "You are making more mistakes at this position, and your win rate has decreased.",
            response.assessment,
        )
    }

    @Test
    fun `assessment joins same-direction rate increases with but`() {
        val response =
            assessedResponse(
                baseline = listOf(true to false, false to true),
                latest = listOf(true to true, true to true),
            )

        assertEquals(
            "You are making more mistakes at this position, but your win rate has increased.",
            response.assessment,
        )
    }

    @Test
    fun `assessment joins same-direction rate decreases with but`() {
        val response =
            assessedResponse(
                baseline = listOf(true to true, true to true),
                latest = listOf(false to false, false to false),
            )

        assertEquals(
            "You are making fewer mistakes at this position, but your win rate has decreased.",
            response.assessment,
        )
    }

    @Test
    fun `assessment reports one unchanged rate with and`() {
        val response =
            assessedResponse(
                baseline = listOf(true to true, false to false),
                latest = listOf(true to true, false to true),
            )

        assertEquals(
            "Your mistake rate stayed the same, and your win rate has increased.",
            response.assessment,
        )
    }

    @Test
    fun `assessment reports both unchanged rates`() {
        val response =
            assessedResponse(
                baseline = listOf(true to true, false to false),
                latest = listOf(true to true, false to false),
            )

        assertEquals("Your mistake rate and win rate stayed the same.", response.assessment)
    }

    @ParameterizedTest
    @CsvSource(
        "WHITE, win, 100",
        "BLACK, resigned, 100",
        "WHITE, resigned, 0",
        "BLACK, win, 0",
        "WHITE, agreed, 0",
        "BLACK, agreed, 0",
    )
    fun `baseline counts normalized wins only`(
        color: PlayerColor,
        sourceResult: String,
        expectedRate: Double,
    ) {
        stubOccurrences(color, listOf(occurrence(color, sourceResult)))
        val response = service.getProgress(position.id, color, principal, minEvalLoss = 0.8)

        assertEquals(emptyList(), response.points)
        assertEquals(1, response.baseline?.sourceEncounterCount)
        assertEquals(firstPlayedAt, response.baseline?.occurredAt)
        assertEquals(expectedRate, response.baseline?.winRate)
        assertEquals("More played encounters are needed to assess progress.", response.assessment)
    }

    @ParameterizedTest
    @EnumSource(value = PlayerColor::class, names = ["WHITE", "BLACK"])
    fun `untrained encounters are represented by one separate baseline`(color: PlayerColor) {
        val results = if (color == PlayerColor.WHITE) listOf("win", "resigned", "agreed") else listOf("resigned", "win", "agreed")
        val occurrences = results.mapIndexed { index, result -> occurrence(color, result, index = index) }
        stubOccurrences(color, occurrences)
        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 0, bestMove = "best", bestMoveEvalCp = 0)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "move-0", evalCp = -100, evalLossFromBest = 0.8))
        whenever(analysisRepository.findByPositionIdWithMoveEvaluations(position.id)).thenReturn(analysis)
        val response = service.getProgress(position.id, color, principal, minEvalLoss = 0.8)

        assertEquals(emptyList(), response.points)
        assertEquals(3, response.baseline?.sourceEncounterCount)
        assertEquals(occurrences.last().game.playedAt, response.baseline?.occurredAt)
        assertEquals(100.0 / 3, response.baseline?.winRate ?: -1.0, 0.000001)
        assertEquals(100.0 / 3, response.baseline?.mistakeRate ?: -1.0, 0.000001)
        assertEquals(null, response.winRateChange)
        assertEquals(null, response.mistakeRateChange)
        assertEquals("More played encounters are needed to assess progress.", response.assessment)
    }

    @Test
    fun `unrecognized source result falls back to PGN Result`() {
        stubOccurrences(
            PlayerColor.BLACK,
            listOf(occurrence(PlayerColor.BLACK, "unknown", pgn = """[Result "0-1"]""")),
        )

        val baseline = service.getProgress(position.id, PlayerColor.BLACK, principal, minEvalLoss = 0.8).baseline

        assertEquals(1, baseline?.sourceEncounterCount)
        assertEquals(100.0, baseline?.winRate)
    }

    @Test
    fun `unnormalizable encounters remain in the denominator`() {
        stubOccurrences(
            PlayerColor.WHITE,
            listOf(
                occurrence(PlayerColor.WHITE, "win"),
                occurrence(PlayerColor.WHITE, "unknown", index = 1, pgn = """[Result "*"]"""),
            ),
        )

        val baseline = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8).baseline

        assertEquals(2, baseline?.sourceEncounterCount)
        assertEquals(50.0, baseline?.winRate)
    }

    @Test
    fun `unconnected account returns account not found`() {
        val missingAccountId = UUID.randomUUID()
        whenever(accountOwnershipService.requireConnectedAccountForProgress(missingAccountId, principal))
            .thenThrow(AccountNotFoundException())

        assertFailsWith<AccountNotFoundException> {
            service.getProgress(position.id, PlayerColor.WHITE, missingAccountId, principal, minEvalLoss = 0.8)
        }
        verify(accountOwnershipService).requireConnectedAccountForProgress(missingAccountId, principal)
    }

    private fun stubAnalysis(bestMoveEvalCp: Int? = 0): EngineAnalysis {
        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = 0,
                bestMove = "best",
                bestMoveEvalCp = bestMoveEvalCp,
            )
        whenever(analysisRepository.findByPositionIdWithMoveEvaluations(position.id)).thenReturn(analysis)
        return analysis
    }

    private fun assessedResponse(
        baseline: List<Pair<Boolean, Boolean>>,
        latest: List<Pair<Boolean, Boolean>>,
    ): com.chessecho.dto.ProgressResponse {
        val checkpointAt = firstPlayedAt.plusSeconds(baseline.size * 60L)
        val facts = baseline + latest
        val occurrences =
            facts.mapIndexed { index, (isMistake, isWin) ->
                val playedAt =
                    if (index < baseline.size) {
                        firstPlayedAt.plusSeconds(index * 60L)
                    } else {
                        checkpointAt.plusSeconds((index - baseline.size + 1) * 60L)
                    }
                occurrence(
                    PlayerColor.WHITE,
                    if (isWin) "win" else "resigned",
                    index = index,
                    playedAt = playedAt,
                )
            }
        stubOccurrences(PlayerColor.WHITE, occurrences)
        stubEvents(PlayerColor.WHITE, listOf(checkpoint(checkpointAt)))
        val analysis = stubAnalysis()
        facts.forEachIndexed { index, (isMistake, _) ->
            analysis.moveEvaluations.add(
                MoveEvaluation(
                    engineAnalysis = analysis,
                    move = "move-$index",
                    evalCp = 0,
                    evalLossFromBest = if (isMistake) 0.8 else 0.0,
                ),
            )
        }
        return service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.3)
    }

    private fun stubOccurrences(
        color: PlayerColor,
        occurrences: List<PositionOccurrence>,
    ) {
        whenever(occurrenceRepository.findProgressOccurrences(account.id, position.id, color.name)).thenReturn(occurrences)
        stubEvents(color, emptyList())
    }

    private fun ProgressService.getProgress(
        positionId: UUID,
        playerColor: PlayerColor,
        principal: AuthenticatedPrincipal,
        minEvalLoss: Double,
    ) = getProgress(positionId, playerColor, account.id, principal, minEvalLoss)

    private fun stubEvents(
        color: PlayerColor,
        events: List<PuzzleSchedulingEvent>,
    ) {
        whenever(
            schedulingEventRepository.findHistory(
                user.id,
                account.id,
                listOf(position.id),
                color.name,
            ),
        ).thenReturn(events)
    }

    private fun checkpoint(
        occurredAt: Instant,
        id: UUID = UUID.randomUUID(),
        eventType: SchedulingEventType = SchedulingEventType.SOLVED,
    ): PuzzleSchedulingEvent =
        PuzzleSchedulingEvent(
            id = id,
            appUser = user,
            chessAccount = account,
            position = position,
            playerColor = PlayerColor.WHITE.name,
            eventType = eventType,
            occurredAt = occurredAt,
        )

    @Test
    fun `evaluates forced-mate positions counting checkmate blunders as mistakes and prolongation as non-mistakes`() {
        stubOccurrences(
            PlayerColor.WHITE,
            listOf(
                occurrence(PlayerColor.WHITE, "loss", index = 0),
                occurrence(PlayerColor.WHITE, "win", index = 1),
            ),
        )
        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalMate = 1,
                bestMove = "Re8#",
                bestMoveEvalMate = 1,
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "move-0", evalMate = -1, evalLossFromBest = 19.5),
        )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "move-1", evalMate = 3, evalLossFromBest = 0.20),
        )
        whenever(analysisRepository.findByPositionIdWithMoveEvaluations(position.id)).thenReturn(analysis)

        val baseline = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8).baseline!!

        assertEquals(2, baseline.sourceEncounterCount)
        assertEquals(50.0, baseline.mistakeRate)
    }

    private fun occurrence(
        color: PlayerColor,
        sourceResult: String,
        index: Int = 0,
        pgn: String = """[Result "*"]""",
        playedAt: Instant? = firstPlayedAt.plusSeconds(index * 60L),
    ): PositionOccurrence =
        PositionOccurrence(
            game =
                Game(
                    chessAccount = account,
                    platformGameId = "game-$index",
                    pgn = pgn,
                    result = sourceResult,
                    playedAt = playedAt,
                    whiteUsername = if (color == PlayerColor.WHITE) account.username else "opponent",
                    blackUsername = if (color == PlayerColor.BLACK) account.username else "opponent",
                ),
            position = position,
            chessAccount = account,
            plyNumber = 1,
            movePlayed = "move-$index",
            playerColor = color.name,
        )
}
