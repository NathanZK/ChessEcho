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
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
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
    private val accountRepository = mock<ChessAccountRepository>()
    private val occurrenceRepository = mock<PositionOccurrenceRepository>()
    private val schedulingEventRepository = mock<PuzzleSchedulingEventRepository>()
    private val analysisRepository = mock<EngineAnalysisRepository>()
    private val service =
        ProgressService(
            accountRepository,
            occurrenceRepository,
            schedulingEventRepository,
            analysisRepository,
            GameOutcomeNormalizer(PgnHeaderTagReader()),
        )
    private val user = AppUser()
    private val account = ChessAccount(user = user, platform = "CHESS_COM", username = "player")
    private val position = Position(hash = "progress", fen = "8/8/8/8/8/8/8/8 w - -")
    private val principal = AuthenticatedPrincipal(user.id, devPrincipal = false)
    private val firstPlayedAt = Instant.parse("2026-09-01T12:00:00Z")

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
    fun `baseline and first interval aggregate independently by encounter count`() {
        val checkpointAt = firstPlayedAt.plusSeconds(2_000)
        val baseline =
            (0 until 20).map { index ->
                occurrence(
                    PlayerColor.WHITE,
                    if (index < 9) "win" else "resigned",
                    index = index,
                    playedAt = firstPlayedAt.plusSeconds(index.toLong() * 60),
                )
            }
        val trained =
            (0 until 10).map { index ->
                occurrence(
                    PlayerColor.WHITE,
                    if (index < 7) "win" else "resigned",
                    index = index + 20,
                    playedAt = checkpointAt.plusSeconds((index + 1).toLong() * 60),
                )
            }
        val solved = checkpoint(checkpointAt)
        stubOccurrences(PlayerColor.WHITE, baseline + trained)
        stubEvents(PlayerColor.WHITE, listOf(solved))

        val response = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(20, response.baseline?.sourceEncounterCount)
        assertEquals(45.0, response.baseline?.winRate)
        assertEquals(1, response.points.size)
        assertEquals(solved.id, response.points.single().checkpointId)
        assertEquals(10, response.points.single().attempts)
        assertEquals(70.0, response.points.single().winRate)
        assertEquals(ProgressIntervalState.MEASURED_OPEN, response.currentIntervalState)

        val laterWin = occurrence(PlayerColor.WHITE, "win", index = 30, playedAt = checkpointAt.plusSeconds(2_000))
        stubOccurrences(PlayerColor.WHITE, baseline + trained + laterWin)
        stubEvents(PlayerColor.WHITE, listOf(solved))
        val updated = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(1, updated.points.size)
        assertEquals(solved.id, updated.points.single().checkpointId)
        assertEquals(11, updated.points.single().attempts)
        assertEquals(800.0 / 11, updated.points.single().winRate, 0.000001)
        assertEquals(laterWin.game.playedAt, updated.points.single().occurredAt)
    }

    @Test
    fun `multiple checkpoints create independent observations and compare against latest measured`() {
        val firstCheckpointAt = firstPlayedAt.plusSeconds(1_000)
        val secondCheckpointAt = firstPlayedAt.plusSeconds(2_000)
        val firstCheckpoint = checkpoint(firstCheckpointAt)
        val secondCheckpoint = checkpoint(secondCheckpointAt)
        val baseline =
            listOf(
                occurrence(PlayerColor.WHITE, "win", 0, playedAt = firstPlayedAt),
                occurrence(PlayerColor.WHITE, "resigned", 1, playedAt = firstPlayedAt.plusSeconds(60)),
            )
        val closedInterval =
            (0 until 5).map { index ->
                occurrence(
                    PlayerColor.WHITE,
                    if (index < 4) "win" else "resigned",
                    index = index + 2,
                    playedAt = firstCheckpointAt.plusSeconds(index.toLong() + 1),
                )
            }
        val openInterval =
            (0 until 5).map { index ->
                occurrence(
                    PlayerColor.WHITE,
                    if (index == 0) "win" else "resigned",
                    index = index + 7,
                    playedAt = secondCheckpointAt.plusSeconds(index.toLong()),
                )
            }
        stubOccurrences(PlayerColor.WHITE, baseline + closedInterval + openInterval)
        stubEvents(PlayerColor.WHITE, listOf(secondCheckpoint, firstCheckpoint))
        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = 0,
                bestMove = "best",
                bestMoveEvalCp = 0,
            )
        val mistakes = setOf(0, 2, 3, 4, 5, 7)
        (0..11).forEach { index ->
            analysis.moveEvaluations.add(
                MoveEvaluation(
                    engineAnalysis = analysis,
                    move = "move-$index",
                    evalCp = 0,
                    evalLossFromBest = if (index in mistakes) 0.8 else 0.0,
                ),
            )
        }
        whenever(analysisRepository.findByPositionIdWithMoveEvaluations(position.id)).thenReturn(analysis)

        val response = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(listOf(firstCheckpoint.id, secondCheckpoint.id), response.points.map { it.checkpointId })
        assertEquals(listOf(5, 5), response.points.map { it.attempts })
        assertEquals(listOf(80.0, 20.0), response.points.map { it.winRate })
        assertEquals(listOf(80.0, 20.0), response.points.map { it.mistakeRate })
        assertEquals(listOf(false, true), response.points.map { it.open })
        assertEquals(
            listOf(closedInterval.last().game.playedAt, openInterval.last().game.playedAt),
            response.points.map { it.occurredAt },
        )
        assertEquals(-60.0, response.mistakeRateChange)
        assertEquals(-60.0, response.winRateChange)
        assertEquals(
            "You are making fewer mistakes at this position, but your win rate has decreased.",
            response.assessment,
        )
    }

    @Test
    fun `tied solved checkpoints assign boundary encounters only to the last checkpoint`() {
        val tiedAt = firstPlayedAt.plusSeconds(100)
        val earlier = checkpoint(tiedAt, UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val later = checkpoint(tiedAt, UUID.fromString("00000000-0000-0000-0000-000000000002"))
        stubOccurrences(
            PlayerColor.WHITE,
            listOf(
                occurrence(PlayerColor.WHITE, "win", 0, playedAt = firstPlayedAt),
                occurrence(PlayerColor.WHITE, "win", 1, playedAt = tiedAt),
                occurrence(PlayerColor.WHITE, "resigned", 2, playedAt = tiedAt.plusSeconds(1)),
            ),
        )
        stubEvents(PlayerColor.WHITE, listOf(later, earlier))

        val response = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(1, response.points.size)
        assertEquals(later.id, response.points.single().checkpointId)
        assertEquals(2, response.points.single().attempts)
        assertEquals(ProgressIntervalState.MEASURED_OPEN, response.currentIntervalState)
    }

    @Test
    fun `consecutive solved events leave an empty latest interval explicit`() {
        val firstCheckpoint = checkpoint(firstPlayedAt.plusSeconds(100))
        val secondCheckpoint = checkpoint(firstPlayedAt.plusSeconds(200))
        stubOccurrences(PlayerColor.WHITE, listOf(occurrence(PlayerColor.WHITE, "win")))
        stubEvents(PlayerColor.WHITE, listOf(firstCheckpoint, secondCheckpoint))

        val response = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(emptyList(), response.points)
        assertEquals(ProgressIntervalState.OPEN_AWAITING_EVIDENCE, response.currentIntervalState)
        assertEquals(1, response.baseline?.sourceEncounterCount)
        assertEquals(null, response.winRateChange)
        assertEquals("More played encounters are needed to assess progress.", response.assessment)
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
    fun `late imports update their historical baseline and closed interval without moving points backward`() {
        val firstCheckpoint = checkpoint(firstPlayedAt.plusSeconds(100))
        val secondCheckpoint = checkpoint(firstPlayedAt.plusSeconds(300))
        val initialBaseline = occurrence(PlayerColor.WHITE, "win", 0, playedAt = firstPlayedAt)
        val initialClosed = occurrence(PlayerColor.WHITE, "resigned", 1, playedAt = firstPlayedAt.plusSeconds(250))
        val firstOccurrences = listOf(initialBaseline, initialClosed)
        stubOccurrences(PlayerColor.WHITE, firstOccurrences)
        stubEvents(PlayerColor.WHITE, listOf(firstCheckpoint, secondCheckpoint))
        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = 0,
                bestMove = "best",
                bestMoveEvalCp = 0,
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "move-0", evalCp = 0, evalLossFromBest = 0.8),
        )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "move-1", evalCp = 0, evalLossFromBest = 0.0),
        )
        whenever(analysisRepository.findByPositionIdWithMoveEvaluations(position.id)).thenReturn(analysis)

        val initialResponse = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(ProgressIntervalState.OPEN_AWAITING_EVIDENCE, initialResponse.currentIntervalState)
        assertEquals(listOf(firstCheckpoint.id), initialResponse.points.map { it.checkpointId })
        assertEquals(false, initialResponse.points.single().open)
        assertEquals(initialClosed.game.playedAt, initialResponse.points.single().occurredAt)
        assertEquals(1, initialResponse.points.single().attempts)
        assertEquals(-100.0, initialResponse.mistakeRateChange)
        assertEquals(-100.0, initialResponse.winRateChange)
        assertEquals(
            "You are making fewer mistakes at this position, but your win rate has decreased.",
            initialResponse.assessment,
        )

        val earlierBaseline = occurrence(PlayerColor.WHITE, "resigned", 2, playedAt = firstPlayedAt.plusSeconds(50))
        val earlierClosed = occurrence(PlayerColor.WHITE, "win", 3, playedAt = firstPlayedAt.plusSeconds(150))
        val laterClosed = occurrence(PlayerColor.WHITE, "win", 4, playedAt = firstPlayedAt.plusSeconds(260))
        val extendedOccurrences = firstOccurrences + earlierBaseline + earlierClosed + laterClosed
        stubOccurrences(PlayerColor.WHITE, extendedOccurrences)
        stubEvents(PlayerColor.WHITE, listOf(firstCheckpoint, secondCheckpoint))

        val lateImportResponse = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(2, lateImportResponse.baseline?.sourceEncounterCount)
        assertEquals(earlierBaseline.game.playedAt, lateImportResponse.baseline?.occurredAt)
        assertEquals(3, lateImportResponse.points.single().attempts)
        assertEquals(laterClosed.game.playedAt, lateImportResponse.points.single().occurredAt)
        assertEquals(firstCheckpoint.id, lateImportResponse.points.single().checkpointId)

        val openOccurrence = occurrence(PlayerColor.WHITE, "win", 5, playedAt = firstPlayedAt.plusSeconds(350))
        stubOccurrences(PlayerColor.WHITE, extendedOccurrences + openOccurrence)
        stubEvents(PlayerColor.WHITE, listOf(firstCheckpoint, secondCheckpoint))
        val completedOpenIntervalResponse = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)
        val repeatedResponse = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(ProgressIntervalState.MEASURED_OPEN, completedOpenIntervalResponse.currentIntervalState)
        assertEquals(listOf(false, true), completedOpenIntervalResponse.points.map { it.open })
        assertEquals(secondCheckpoint.id, completedOpenIntervalResponse.points.last().checkpointId)
        assertEquals(completedOpenIntervalResponse.points.last(), repeatedResponse.points.last())

        val allOccurrences = extendedOccurrences + openOccurrence
        stubOccurrences(
            PlayerColor.WHITE,
            allOccurrences.sortedBy { it.game.playedAt },
        )
        stubEvents(PlayerColor.WHITE, listOf(firstCheckpoint, secondCheckpoint))
        assertEquals(completedOpenIntervalResponse, service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8))
    }

    @Test
    fun `older late import changes interval aggregate without moving observation timestamp backward`() {
        val solved = checkpoint(firstPlayedAt)
        val latestExisting = occurrence(PlayerColor.WHITE, "win", index = 1, playedAt = firstPlayedAt.plusSeconds(200))
        stubOccurrences(PlayerColor.WHITE, listOf(latestExisting))
        stubEvents(PlayerColor.WHITE, listOf(solved))

        val initialResponse = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(1, initialResponse.points.single().attempts)
        assertEquals(100.0, initialResponse.points.single().winRate)
        assertEquals(latestExisting.game.playedAt, initialResponse.points.single().occurredAt)

        val olderLateImport = occurrence(PlayerColor.WHITE, "resigned", index = 2, playedAt = firstPlayedAt.plusSeconds(100))
        stubOccurrences(PlayerColor.WHITE, listOf(latestExisting, olderLateImport))
        stubEvents(PlayerColor.WHITE, listOf(solved))

        val updatedResponse = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(2, updatedResponse.points.single().attempts)
        assertEquals(50.0, updatedResponse.points.single().winRate)
        assertEquals(latestExisting.game.playedAt, updatedResponse.points.single().occurredAt)
    }

    @Test
    fun `measured interval without a baseline has unavailable comparison and insufficient assessment`() {
        val solved = checkpoint(firstPlayedAt)
        stubOccurrences(
            PlayerColor.WHITE,
            listOf(occurrence(PlayerColor.WHITE, "win", playedAt = firstPlayedAt.plusSeconds(1))),
        )
        stubEvents(PlayerColor.WHITE, listOf(solved))

        val response = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(null, response.baseline)
        assertEquals(null, response.winRateChange)
        assertEquals(null, response.mistakeRateChange)
        assertEquals("More played encounters are needed to assess progress.", response.assessment)
    }

    @Test
    fun `zero baseline mistake rate keeps null change and stable assessment`() {
        val solved = checkpoint(firstPlayedAt.plusSeconds(100))
        stubOccurrences(
            PlayerColor.WHITE,
            listOf(
                occurrence(PlayerColor.WHITE, "win", 0, playedAt = firstPlayedAt),
                occurrence(PlayerColor.WHITE, "win", 1, playedAt = firstPlayedAt.plusSeconds(101)),
            ),
        )
        stubEvents(PlayerColor.WHITE, listOf(solved))

        val response = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(0.0, response.baseline?.mistakeRate)
        assertEquals(null, response.mistakeRateChange)
        assertEquals("Your mistake rate and win rate stayed the same.", response.assessment)
    }

    @Test
    fun `positive mistake change uses more mistakes assessment`() {
        val solved = checkpoint(firstPlayedAt.plusSeconds(100))
        stubOccurrences(
            PlayerColor.WHITE,
            listOf(
                occurrence(PlayerColor.WHITE, "win", 0, playedAt = firstPlayedAt),
                occurrence(PlayerColor.WHITE, "win", 1, playedAt = firstPlayedAt.plusSeconds(60)),
                occurrence(PlayerColor.WHITE, "win", 2, playedAt = firstPlayedAt.plusSeconds(101)),
            ),
        )
        stubEvents(PlayerColor.WHITE, listOf(solved))
        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = 0,
                bestMove = "best",
                bestMoveEvalCp = 0,
            )
        listOf("move-0" to 0.8, "move-1" to 0.0, "move-2" to 0.8).forEach { (move, loss) ->
            analysis.moveEvaluations.add(
                MoveEvaluation(engineAnalysis = analysis, move = move, evalCp = 0, evalLossFromBest = loss),
            )
        }
        whenever(analysisRepository.findByPositionIdWithMoveEvaluations(position.id)).thenReturn(analysis)

        val response = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(50.0, response.baseline?.mistakeRate)
        assertEquals(100.0, response.points.single().mistakeRate)
        assertEquals(100.0, response.mistakeRateChange)
        assertEquals(
            "You are making more mistakes at this position, and your win rate stayed the same.",
            response.assessment,
        )
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

    @Test
    fun `only solved scheduling events create checkpoints`() {
        val checkpointAt = firstPlayedAt.plusSeconds(100)
        val solved = checkpoint(checkpointAt, id = UUID.fromString("00000000-0000-0000-0000-000000000009"))
        val otherEvents =
            SchedulingEventType.entries
                .filter { it != SchedulingEventType.SOLVED }
                .mapIndexed { index, eventType ->
                    checkpoint(checkpointAt.minusSeconds(index.toLong() + 1), eventType = eventType)
                }
        stubOccurrences(
            PlayerColor.WHITE,
            listOf(
                occurrence(PlayerColor.WHITE, "win", 0, playedAt = firstPlayedAt),
                occurrence(PlayerColor.WHITE, "win", 1, playedAt = checkpointAt),
            ),
        )
        stubEvents(PlayerColor.WHITE, otherEvents + solved)

        val response = service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)

        assertEquals(listOf(solved.id), response.points.map { it.checkpointId })
        assertEquals(1, response.points.single().attempts)
        verify(schedulingEventRepository).findHistory(user.id, account.id, listOf(position.id), PlayerColor.WHITE.name)
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
        verify(accountRepository).findAllByUserIdOrderByCreatedAtAsc(user.id)
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
    fun `no owned account keeps existing account not found behavior`() {
        whenever(accountRepository.findAllByUserIdOrderByCreatedAtAsc(user.id)).thenReturn(emptyList())

        assertFailsWith<AccountNotFoundException> {
            service.getProgress(position.id, PlayerColor.WHITE, principal, minEvalLoss = 0.8)
        }
        verify(accountRepository).findAllByUserIdOrderByCreatedAtAsc(user.id)
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
        whenever(accountRepository.findAllByUserIdOrderByCreatedAtAsc(user.id)).thenReturn(listOf(account))
        whenever(occurrenceRepository.findProgressOccurrences(account.id, position.id, color.name)).thenReturn(occurrences)
        stubEvents(color, emptyList())
    }

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
