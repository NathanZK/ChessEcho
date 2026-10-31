package com.chessecho.testsupport

import com.chessecho.config.ComparatorMethod
import com.chessecho.config.ConfidenceMethod
import com.chessecho.config.PracticalEvidenceProperties
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.WeaknessAggregation
import com.chessecho.service.AdaptivePuzzleSchedulingPolicy
import com.chessecho.service.PracticalEvidenceScope
import com.chessecho.service.PracticalEvidenceService
import com.chessecho.service.PracticalEvidenceSummary
import com.chessecho.service.WeaknessCalculationService
import com.chessecho.service.WeaknessPriorityPolicy
import org.mockito.Mockito.lenient
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import java.time.Instant
import java.util.UUID

object WeaknessCalculationTestUtils {
    fun mockGame(
        platformGameId: String = "123",
        playedAt: Instant? = null,
        timeControl: String? = "bullet",
        platform: String = "P",
        username: String = "U",
    ): Game =
        Game(
            chessAccount = ChessAccount(platform = platform, username = username),
            platformGameId = platformGameId,
            timeControl = timeControl,
            pgn = "pgn",
            result = "win",
            playedAt = playedAt,
        )

    fun occurrence(
        game: Game,
        position: Position,
        account: ChessAccount,
        plyNumber: Int = 1,
        movePlayed: String,
        playerColor: String = "WHITE",
    ): PositionOccurrence =
        PositionOccurrence(
            game = game,
            position = position,
            chessAccount = account,
            plyNumber = plyNumber,
            movePlayed = movePlayed,
            playerColor = playerColor,
        )

    fun occurrence(
        account: ChessAccount,
        position: Position,
        color: String,
        san: String,
        gameId: String,
        playedAt: Instant,
    ): PositionOccurrence =
        PositionOccurrence(
            game =
                Game(
                    chessAccount = account,
                    platformGameId = gameId,
                    timeControl = "blitz",
                    pgn = "[Result \"1-0\"]\n\n1. e4",
                    result = "win",
                    playedAt = playedAt,
                ),
            position = position,
            chessAccount = account,
            plyNumber = 1,
            movePlayed = san,
            playerColor = color,
        )

    fun analysis(
        position: Position,
        evalLoss: Double = 1.0,
    ): EngineAnalysis =
        EngineAnalysis(
            position = position,
            depth = 16,
            baselineEvalCp = 100,
            bestMove = "best",
            bestMoveEvalCp = 100,
        ).also {
            it.moveEvaluations.add(
                MoveEvaluation(
                    engineAnalysis = it,
                    move = "bad",
                    evalCp = 0,
                    evalLossFromBest = evalLoss,
                ),
            )
        }

    fun practicalSummary(
        scope: PracticalEvidenceScope,
        wins: Int,
        losses: Int,
    ): PracticalEvidenceSummary =
        PracticalEvidenceSummary(
            scope = scope,
            candidateGames = wins + losses,
            eligibleGames = wins + losses,
            ineligibleGames = 0,
            excludedGames = 0,
            wins = wins,
            draws = 0,
            losses = losses,
            sideCorroborationConflictGames = 0,
            scoreRate = wins.toDouble() / (wins + losses),
        )

    fun aggregation(
        position: Position,
        color: String,
    ): WeaknessAggregation =
        WeaknessAggregation(
            positionId = position.id,
            fen = position.fen,
            playerColor = color,
            timesReached = 3,
            bestMove = "best",
            baselineEvalCp = 100,
            mistakeCount = 3,
            averageLoss = 1.0,
            rawTotalLoss = 3.0,
        )

    fun createAgg(
        posId: UUID,
        fen: String,
        color: String = "WHITE",
        reached: Int = 5,
        bestMove: String = "best",
        baselineCp: Int? = 100,
        mistakes: Long = 1,
        avgLoss: Double = 1.0,
        totalLoss: Double = avgLoss * mistakes,
    ): WeaknessAggregation =
        WeaknessAggregation(
            positionId = posId,
            fen = fen,
            playerColor = color,
            timesReached = reached,
            bestMove = bestMove,
            baselineEvalCp = baselineCp,
            mistakeCount = mistakes,
            averageLoss = avgLoss,
            rawTotalLoss = totalLoss,
        )

    fun createAnalysisWithLoss(
        pos: Position,
        move: String,
        loss: Double,
    ): EngineAnalysis {
        val a = EngineAnalysis(position = pos, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        a.moveEvaluations.add(MoveEvaluation(engineAnalysis = a, move = move, evalCp = 0, evalLossFromBest = loss))
        return a
    }

    fun createAnalysis(
        position: Position,
        move: String = "e4",
        evalCp: Int = 0,
        evalLoss: Double = 1.0,
        depth: Int = 16,
        bestMove: String = "best",
        baselineEvalCp: Int = 100,
        bestMoveEvalCp: Int = 100,
    ): EngineAnalysis {
        val a =
            EngineAnalysis(
                position = position,
                depth = depth,
                baselineEvalCp = baselineEvalCp,
                bestMove = bestMove,
                bestMoveEvalCp = bestMoveEvalCp,
            )
        a.moveEvaluations.add(MoveEvaluation(engineAnalysis = a, move = move, evalCp = evalCp, evalLossFromBest = evalLoss))
        return a
    }

    fun buildPracticalOccurrences(
        account: ChessAccount,
        firstPos: Position,
        secondPos: Position,
        playedAt: Instant,
    ): List<PositionOccurrence> =
        buildList {
            repeat(3) { index ->
                add(occurrence(account, firstPos, "WHITE", "bad", "first-white-$index", playedAt))
                add(occurrence(account, firstPos, "BLACK", "bad", "first-black-$index", playedAt))
                add(occurrence(account, secondPos, "WHITE", "bad", "second-white-$index", playedAt))
            }
            add(occurrence(account, firstPos, "WHITE", "unanalysed", "complete-position-evidence", playedAt))
        }

    fun buildArithmeticOccurrences(
        account: ChessAccount,
        position: Position,
    ): List<PositionOccurrence> {
        val game = mockGame("g-today")
        return listOf(
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "BAD1",
                playerColor = "WHITE",
            ),
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 2,
                movePlayed = "BAD2",
                playerColor = "WHITE",
            ),
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 3,
                movePlayed = "GOOD",
                playerColor = "WHITE",
            ),
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 4,
                movePlayed = "GOOD",
                playerColor = "WHITE",
            ),
        )
    }

    fun buildArithmeticAnalysis(position: Position): EngineAnalysis {
        val a = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        a.moveEvaluations.add(MoveEvaluation(engineAnalysis = a, move = "BAD1", evalCp = -20, evalLossFromBest = 1.2))
        a.moveEvaluations.add(MoveEvaluation(engineAnalysis = a, move = "BAD2", evalCp = 20, evalLossFromBest = 0.8))
        a.moveEvaluations.add(MoveEvaluation(engineAnalysis = a, move = "GOOD", evalCp = 90, evalLossFromBest = 0.1))
        return a
    }

    fun buildRankingPolicy(): WeaknessPriorityPolicy =
        WeaknessPriorityPolicy(
            PracticalEvidenceProperties(
                rankingEnabled = true,
                sampleFloor = 5,
                comparatorMethod = ComparatorMethod.FIXED_SCORE_RATE,
                comparatorScoreRate = 0.5,
                confidenceMethod = ConfidenceMethod.BERNOULLI_WILSON_SCORE_POINTS_HALF_DRAW_CONSERVATIVE,
                wilsonZScore = 1.0,
                meaningfulDifference = 0.1,
                maxPriorityAdjustment = 0.25,
                observationWindowDays = 365,
                policyVersion = "service-order-test-v1",
            ),
        )

    fun setupDefaultServiceMocks(
        service: WeaknessCalculationService,
        practicalEvidenceService: PracticalEvidenceService,
        weaknessPriorityPolicy: WeaknessPriorityPolicy,
        puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository,
        adaptivePuzzleSchedulingPolicy: AdaptivePuzzleSchedulingPolicy,
    ) {
        lenient().`when`(
            practicalEvidenceService.summarize(any(), any(), any(), any(), any()),
        ).thenReturn(emptyMap())

        val fallbackPolicy = WeaknessPriorityPolicy(PracticalEvidenceProperties())
        lenient().`when`(
            weaknessPriorityPolicy.evaluate(any(), any(), anyOrNull()),
        ).thenAnswer { invocation ->
            fallbackPolicy.evaluate(
                invocation.getArgument(0),
                invocation.getArgument(1),
                invocation.getArgument(2),
            )
        }

        lenient().`when`(
            puzzleSchedulingEventRepository.findHistory(any(), any(), any(), any()),
        ).thenReturn(emptyList())

        lenient().`when`(
            adaptivePuzzleSchedulingPolicy.schedule(any(), any(), any(), any()),
        ).thenAnswer { invocation -> invocation.getArgument<Double>(0) }
    }

    fun setupPracticalRanking(
        practicalEvidenceService: PracticalEvidenceService,
        weaknessPriorityPolicy: WeaknessPriorityPolicy,
        account: ChessAccount,
        poorPos: Position,
        succPos: Position,
    ) {
        val poorScope = PracticalEvidenceScope(account.id, poorPos.id, "WHITE")
        val succScope = PracticalEvidenceScope(account.id, succPos.id, "WHITE")
        lenient().`when`(practicalEvidenceService.summarize(eq(account.id), eq(account.username), any(), any(), any()))
            .thenReturn(mapOf(poorScope to practicalSummary(poorScope, 0, 5), succScope to practicalSummary(succScope, 5, 0)))

        val rankingPolicy = buildRankingPolicy()
        lenient().`when`(weaknessPriorityPolicy.evaluate(any(), any(), anyOrNull())).thenAnswer { inv ->
            rankingPolicy.evaluate(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2))
        }
    }
}
