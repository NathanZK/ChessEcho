package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.WeaknessAggregation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class WeaknessCalculationServiceTest {
    @Mock
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Mock
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Mock
    private lateinit var engineAnalysisRepository: EngineAnalysisRepository

    @InjectMocks
    private lateinit var weaknessCalculationService: WeaknessCalculationService

    @Test
    fun `test dynamic minEvalLoss changes mistakeCount and classifies moves accurately`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash", fen = "fen")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        // Occurrences: move A (loss 0.2), move B (loss 0.3), move C (loss 0.8)
        val occA =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "A",
                playerColor = "WHITE",
            )
        val occB =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "B",
                playerColor = "WHITE",
            )
        val occC =
            PositionOccurrence(
                game = mockGame(),
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "C",
                playerColor = "WHITE",
            )

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(listOf(occA, occB, occC))

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "A", evalCp = 80, evalLossFromBest = 0.2))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "B", evalCp = 70, evalLossFromBest = 0.3))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "C", evalCp = 20, evalLossFromBest = 0.8))

        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val aggregation03 =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                timesReached = 3,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 2,
                averageLoss = 0.55,
                rawTotalLoss = 1.1,
            )

        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = account.id,
                playerColor = "WHITE",
                minEvalLoss = 0.3,
                minTimesReached = 5,
                minMistakeCount = 1,
            ),
        ).thenReturn(listOf(aggregation03))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.3,
                minMistakeCount = 1,
            )

        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]
        assertEquals(2, w.mistakeCount)
        assertEquals(3, w.timesReached)
        assertEquals(1, w.acceptableMoves.size)
        assertEquals("A", w.acceptableMoves[0].move)
        assertEquals(2, w.movesPlayed.size)
        assertTrue(w.movesPlayed.any { it.move == "B" })
        assertTrue(w.movesPlayed.any { it.move == "C" })
    }

    @Test
    fun `test negative minEvalLoss throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            weaknessCalculationService.getWeaknesses(Platform.CHESS_COM, "nathan", PlayerColor.WHITE, minEvalLoss = -0.5)
        }
    }

    @Test
    fun `test playerColor BOTH calls queries with BOTH color parameter`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = account.id,
                playerColor = "BOTH",
                minEvalLoss = 0.8,
                minTimesReached = 5,
                minMistakeCount = 3,
            ),
        ).thenReturn(emptyList())

        val result = weaknessCalculationService.getWeaknesses(Platform.CHESS_COM, "nathan", PlayerColor.BOTH, minEvalLoss = 0.8)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `test changing minEvalLoss does not trigger Stockfish analysis`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = any(),
                playerColor = any(),
                minEvalLoss = any(),
                minTimesReached = any(),
                minMistakeCount = any(),
            ),
        ).thenReturn(emptyList())

        weaknessCalculationService.getWeaknesses(Platform.CHESS_COM, "nathan", PlayerColor.WHITE, minEvalLoss = 0.3)
        weaknessCalculationService.getWeaknesses(Platform.CHESS_COM, "nathan", PlayerColor.WHITE, minEvalLoss = 0.8)

        verify(engineAnalysisRepository, never()).save(any())
    }

    @Test
    fun `test retained historical move receives exact resulting fen`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        val sourceFen = "rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 2"
        val expectedResultingFen = "rnbqkbnr/pppp1ppp/8/4P3/8/8/PPP1PPPP/RNBQKBNR b KQkq - 0 2"
        val position = Position(hash = "legal-resulting-fen", fen = sourceFen)
        val occurrences =
            (1..3).map {
                PositionOccurrence(
                    game = mockGame("legal-$it"),
                    position = position,
                    chessAccount = account,
                    plyNumber = 2,
                    movePlayed = "dxe5",
                    playerColor = "WHITE",
                )
            }
        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 50, bestMove = "e4", bestMoveEvalCp = 50)
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "dxe5", evalCp = -150, evalLossFromBest = 2.0),
        )
        val aggregation =
            WeaknessAggregation(
                positionId = position.id,
                fen = sourceFen,
                timesReached = 5,
                bestMove = "e4",
                baselineEvalCp = 50,
                mistakeCount = 3,
                averageLoss = 2.0,
                rawTotalLoss = 6.0,
            )

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 5, 3))
            .thenReturn(listOf(aggregation))
        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val weakness =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 3,
            ).single()

        val breakdown = weakness.movesPlayed.single()
        assertEquals("dxe5", breakdown.move)
        assertEquals(3, breakdown.timesPlayed)
        assertEquals(2.0, breakdown.averageLoss)
        assertEquals(expectedResultingFen, breakdown.resultingFen)
    }

    @Test
    fun `test malformed historical positions and moves retain unchanged breakdowns without resulting fen`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        val sourceFen = "rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 2"
        val cases =
            listOf(
                Triple(Position(hash = "blank-san", fen = sourceFen), "", 3),
                Triple(Position(hash = "rejected-san", fen = sourceFen), "Qh5", 2),
                Triple(Position(hash = "invalid-fen", fen = "not a fen"), "dxe5", 1),
            )

        val occurrences =
            cases.flatMap { (position, move, count) ->
                (1..count).map {
                    PositionOccurrence(
                        game = mockGame("${position.hash}-$it"),
                        position = position,
                        chessAccount = account,
                        plyNumber = 2,
                        movePlayed = move,
                        playerColor = "WHITE",
                    )
                }
            }
        val analyses =
            cases.mapIndexed { index, (position, move, _) ->
                EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "e4", bestMoveEvalCp = 100)
                    .also {
                        it.moveEvaluations.add(
                            MoveEvaluation(
                                engineAnalysis = it,
                                move = move,
                                evalCp = 0,
                                evalLossFromBest = 1.0 + index,
                            ),
                        )
                    }
            }
        val aggregations =
            cases.mapIndexed { index, (position, _, count) ->
                WeaknessAggregation(
                    positionId = position.id,
                    fen = position.fen,
                    timesReached = count + 2,
                    bestMove = "e4",
                    baselineEvalCp = 100,
                    mistakeCount = count.toLong(),
                    averageLoss = 1.0 + index,
                    rawTotalLoss = count * (1.0 + index),
                )
            }

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 1, 1))
            .thenReturn(aggregations)
        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(occurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(analyses)

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 1,
                minTimesReached = 1,
            )
        val byPosition = weaknesses.associateBy { it.positionId }

        cases.forEachIndexed { index, (position, move, count) ->
            val weakness = requireNotNull(byPosition[position.id])
            val breakdown = weakness.movesPlayed.single()
            assertEquals(move, breakdown.move)
            assertEquals(count, breakdown.timesPlayed)
            assertEquals(1.0 + index, breakdown.averageLoss)
            assertNull(breakdown.resultingFen)
        }
    }

    @Test
    fun `test resulting fen enrichment preserves weakness order and existing top three breakdown order`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        val sourceFen = "rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 2"
        val expectedResultingFen = "rnbqkbnr/pppp1ppp/8/4P3/8/8/PPP1PPPP/RNBQKBNR b KQkq - 0 2"
        val higherPriorityPosition = Position(hash = "ordered-top-three", fen = sourceFen)
        val lowerPriorityPosition = Position(hash = "lower-priority", fen = sourceFen)
        val moveCountsAndLosses =
            listOf(
                Triple("dxe5", 5, 1.0),
                Triple("Qh5", 4, 3.0),
                Triple("Nc3", 4, 2.0),
                Triple("e3", 3, 4.0),
            )
        val higherPriorityOccurrences =
            moveCountsAndLosses.flatMap { (move, count, _) ->
                (1..count).map {
                    PositionOccurrence(
                        game = mockGame("ordered-$move-$it"),
                        position = higherPriorityPosition,
                        chessAccount = account,
                        plyNumber = 2,
                        movePlayed = move,
                        playerColor = "WHITE",
                    )
                }
            }
        val lowerPriorityOccurrences =
            listOf(
                PositionOccurrence(
                    game = mockGame("lower-priority"),
                    position = lowerPriorityPosition,
                    chessAccount = account,
                    plyNumber = 2,
                    movePlayed = "dxe5",
                    playerColor = "WHITE",
                ),
            )
        val higherPriorityAnalysis =
            EngineAnalysis(
                position = higherPriorityPosition,
                depth = 16,
                baselineEvalCp = 100,
                bestMove = "e4",
                bestMoveEvalCp = 100,
            )
        moveCountsAndLosses.forEach { (move, _, loss) ->
            higherPriorityAnalysis.moveEvaluations.add(
                MoveEvaluation(
                    engineAnalysis = higherPriorityAnalysis,
                    move = move,
                    evalCp = 0,
                    evalLossFromBest = loss,
                ),
            )
        }
        val lowerPriorityAnalysis =
            EngineAnalysis(
                position = lowerPriorityPosition,
                depth = 16,
                baselineEvalCp = 100,
                bestMove = "e4",
                bestMoveEvalCp = 100,
            ).also {
                it.moveEvaluations.add(
                    MoveEvaluation(engineAnalysis = it, move = "dxe5", evalCp = 0, evalLossFromBest = 1.0),
                )
            }
        val higherPriorityAggregation =
            WeaknessAggregation(
                positionId = higherPriorityPosition.id,
                fen = sourceFen,
                timesReached = 20,
                bestMove = "e4",
                baselineEvalCp = 100,
                mistakeCount = 16,
                averageLoss = 2.31,
                rawTotalLoss = 37.0,
            )
        val lowerPriorityAggregation =
            WeaknessAggregation(
                positionId = lowerPriorityPosition.id,
                fen = sourceFen,
                timesReached = 10,
                bestMove = "e4",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 1, 1))
            .thenReturn(listOf(lowerPriorityAggregation, higherPriorityAggregation))
        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(lowerPriorityOccurrences + higherPriorityOccurrences)
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any()))
            .thenReturn(listOf(lowerPriorityAnalysis, higherPriorityAnalysis))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 1,
                minTimesReached = 1,
            )

        assertEquals(
            listOf(higherPriorityPosition.id, lowerPriorityPosition.id),
            weaknesses.map { it.positionId },
        )
        val retained = weaknesses.first().movesPlayed
        assertEquals(listOf("dxe5", "Qh5", "Nc3"), retained.map { it.move })
        assertEquals(listOf(5, 4, 4), retained.map { it.timesPlayed })
        assertEquals(listOf(1.0, 3.0, 2.0), retained.map { it.averageLoss })
        assertEquals(expectedResultingFen, retained[0].resultingFen)
        assertNull(retained[1].resultingFen)
        assertTrue(retained[2].resultingFen != null)
        assertTrue(retained.none { it.move == "e3" })
    }

    @Test
    fun `test bestMove is never classified as a historical mistake`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash_qh4", fen = "fen_qh4")

        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        // Historical occurrences: 5 games playing Qh4 (which IS bestMove), and 3 games playing Nf6 (eval loss 1.2)
        val occQh4List =
            (1..5).map {
                PositionOccurrence(
                    game = mockGame(),
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "Qh4",
                    playerColor = "WHITE",
                )
            }
        val occNf6List =
            (1..3).map {
                PositionOccurrence(
                    game = mockGame(),
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "Nf6",
                    playerColor = "WHITE",
                )
            }

        `when`(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                eq(account.id),
                eq("WHITE"),
                any(),
            ),
        ).thenReturn(occQh4List + occNf6List)

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "Qh4", bestMoveEvalCp = 100)
        // Qh4 has 0.0 loss (or even hypothetical artifact loss), Nf6 has 1.2 loss
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "Qh4", evalCp = 100, evalLossFromBest = 0.0))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "Nf6", evalCp = -20, evalLossFromBest = 1.2))

        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val aggregation =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                timesReached = 8,
                bestMove = "Qh4",
                baselineEvalCp = 100,
                mistakeCount = 3,
                averageLoss = 1.2,
                rawTotalLoss = 3.6,
            )

        `when`(
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = account.id,
                playerColor = "WHITE",
                minEvalLoss = 0.8,
                minTimesReached = 5,
                minMistakeCount = 3,
            ),
        ).thenReturn(listOf(aggregation))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 3,
            )

        assertEquals(1, weaknesses.size)
        val w = weaknesses[0]

        // Qh4 must NOT be in movesPlayed, only Nf6 should be classified as a mistake
        assertEquals(3, w.mistakeCount)
        assertEquals(1, w.movesPlayed.size)
        assertEquals("Nf6", w.movesPlayed[0].move)
        assertEquals(3, w.movesPlayed[0].timesPlayed)
        assertEquals(1.2, w.movesPlayed[0].averageLoss)
        assertTrue(w.movesPlayed.none { it.move == "Qh4" })
    }

    @Test
    fun `test lastSeenAt derives from newest occurrence timestamp and prefers playedAt`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash1", fen = "fen1")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        val olderInstant = Instant.parse("2026-01-01T10:00:00Z")
        val newerInstant = Instant.parse("2026-08-01T10:00:00Z")

        val oldGame = mockGame("gameOld", playedAt = olderInstant)
        val newGame = mockGame("gameNew", playedAt = newerInstant)

        val occ1 =
            PositionOccurrence(
                game = oldGame,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "m1",
                playerColor = "WHITE",
            )
        val occ2 =
            PositionOccurrence(
                game = newGame,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "m1",
                playerColor = "WHITE",
            )

        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(listOf(occ1, occ2))

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "m1", evalCp = 0, evalLossFromBest = 1.0))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val agg =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                timesReached = 5,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 2,
                averageLoss = 1.0,
                rawTotalLoss = 2.0,
            )
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 5, 2)).thenReturn(listOf(agg))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 2,
            )

        assertEquals(1, weaknesses.size)
        assertEquals(newerInstant, weaknesses[0].lastSeenAt)
        // Game URLs must be returned newest-first
        assertEquals(listOf("https://www.chess.com/game/live/gameNew", "https://www.chess.com/game/live/gameOld"), weaknesses[0].gameUrls)
    }

    @Test
    fun `test lastSeenAt falls back to createdAt when playedAt is null`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        val position = Position(hash = "hash2", fen = "fen2")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        val gameNoPlayedAt = mockGame("gameNull", playedAt = null)
        val occ =
            PositionOccurrence(
                game = gameNoPlayedAt,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "m1",
                playerColor = "WHITE",
            )

        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(listOf(occ))

        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "m1", evalCp = 0, evalLossFromBest = 1.0))
        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysis))

        val agg =
            WeaknessAggregation(
                positionId = position.id,
                fen = position.fen,
                timesReached = 5,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )
        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 5, 1)).thenReturn(listOf(agg))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 1,
            )

        assertEquals(1, weaknesses.size)
        assertEquals(occ.createdAt, weaknesses[0].lastSeenAt)
    }

    @Test
    fun `test recent weaknesses rank higher than otherwise equivalent older weaknesses`() {
        val account = ChessAccount(user = AppUser(email = "test@test.com"), platform = "CHESS_COM", username = "nathan")
        val posRecent = Position(hash = "recentHash", fen = "recentFen")
        val posOld = Position(hash = "oldHash", fen = "oldFen")
        `when`(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "nathan")).thenReturn(account)

        val now = Instant.now()
        val oldDate = now.minusSeconds(180 * 86400L) // 180 days ago

        val gameRecent = mockGame("recentGame", playedAt = now)
        val gameOld = mockGame("oldGame", playedAt = oldDate)

        val occRecent =
            PositionOccurrence(
                game = gameRecent,
                position = posRecent,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "m1",
                playerColor = "WHITE",
            )
        val occOld =
            PositionOccurrence(
                game = gameOld,
                position = posOld,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "m1",
                playerColor = "WHITE",
            )

        `when`(positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(eq(account.id), eq("WHITE"), any()))
            .thenReturn(listOf(occRecent, occOld))

        val analysisRecent = EngineAnalysis(position = posRecent, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysisRecent.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysisRecent, move = "m1", evalCp = 0, evalLossFromBest = 1.0))

        val analysisOld = EngineAnalysis(position = posOld, depth = 16, baselineEvalCp = 100, bestMove = "best", bestMoveEvalCp = 100)
        analysisOld.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysisOld, move = "m1", evalCp = 0, evalLossFromBest = 1.0))

        `when`(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(any())).thenReturn(listOf(analysisRecent, analysisOld))

        val aggRecent =
            WeaknessAggregation(
                positionId = posRecent.id,
                fen = posRecent.fen,
                timesReached = 5,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )
        val aggOld =
            WeaknessAggregation(
                positionId = posOld.id,
                fen = posOld.fen,
                timesReached = 5,
                bestMove = "best",
                baselineEvalCp = 100,
                mistakeCount = 1,
                averageLoss = 1.0,
                rawTotalLoss = 1.0,
            )

        `when`(positionOccurrenceRepository.findWeaknessAggregations(account.id, "WHITE", 0.8, 5, 1)).thenReturn(listOf(aggRecent, aggOld))

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                Platform.CHESS_COM,
                "nathan",
                PlayerColor.WHITE,
                minEvalLoss = 0.8,
                minMistakeCount = 1,
            )

        assertEquals(2, weaknesses.size)
        // Recent weakness must rank ahead of the old weakness due to recency decay priority weighting
        assertEquals(posRecent.id, weaknesses[0].positionId)
        assertEquals(posOld.id, weaknesses[1].positionId)
        assertTrue(weaknesses[0].priority > weaknesses[1].priority)
    }

    private fun mockGame(
        platformGameId: String = "123",
        playedAt: Instant? = null,
    ): Game =
        Game(
            chessAccount = ChessAccount(user = AppUser(email = "t"), platform = "P", username = "U"),
            platformGameId = platformGameId,
            timeControl = "bullet",
            pgn = "pgn",
            result = "win",
            playedAt = playedAt,
        )
}
