package com.chessecho.service

import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WeaknessEvaluationLossTest {
    private val position = Position(hash = "dummy", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    private val analysis = EngineAnalysis(position = position, depth = 16, bestMove = "e4")

    @Test
    fun `returns persisted evalLossFromBest directly when present`() {
        val moveEval =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e4",
                evalCp = 100,
                evalLossFromBest = 1.25,
            )
        assertEquals(1.25, moveEval.weaknessEvaluationLoss(bestMoveEvalCp = 200, bestMoveEvalMate = null))
    }

    @Test
    fun `falls back to calculateEvalLoss with centipawns when evalLossFromBest is null`() {
        val moveEval =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e4",
                evalCp = 20,
                evalLossFromBest = null,
            )
        assertEquals(0.80, moveEval.weaknessEvaluationLoss(bestMoveEvalCp = 100, bestMoveEvalMate = null))
    }

    @Test
    fun `produces zero loss when bestMoveEvalCp equals evalCp`() {
        val moveEval =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e4",
                evalCp = 80,
                evalLossFromBest = null,
            )
        assertEquals(0.0, moveEval.weaknessEvaluationLoss(bestMoveEvalCp = 80, bestMoveEvalMate = null))
    }

    @Test
    fun `clamps loss to 0_0 when move eval exceeds best move eval`() {
        val moveEval =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e4",
                evalCp = 120,
                evalLossFromBest = null,
            )
        assertEquals(0.0, moveEval.weaknessEvaluationLoss(bestMoveEvalCp = 50, bestMoveEvalMate = null))
    }

    @Test
    fun `falls back to calculateEvalLoss with mate scores when evalLossFromBest is null`() {
        // Winning mate vs losing mate blunder
        val blunderEval =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "f3",
                evalMate = -1,
                evalLossFromBest = null,
            )
        assertEquals(19.5, blunderEval.weaknessEvaluationLoss(bestMoveEvalCp = null, bestMoveEvalMate = 1))

        // Winning mate prolongation
        val prolongEval =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "Qd3",
                evalMate = 2,
                evalLossFromBest = null,
            )
        assertEquals(0.10, prolongEval.weaknessEvaluationLoss(bestMoveEvalCp = null, bestMoveEvalMate = 1))

        // Losing mate with negative centipawns: inconclusive, returns null
        val inconclusiveEval =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "a6",
                evalCp = -500,
                evalLossFromBest = null,
            )
        assertNull(inconclusiveEval.weaknessEvaluationLoss(bestMoveEvalCp = null, bestMoveEvalMate = -5))
    }

    @Test
    fun `returns null when evaluation is missing or inconclusive`() {
        val emptyEval =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e4",
                evalCp = null,
                evalMate = null,
                evalLossFromBest = null,
            )
        assertNull(emptyEval.weaknessEvaluationLoss(bestMoveEvalCp = null, bestMoveEvalMate = null))
        assertNull(emptyEval.weaknessEvaluationLoss(bestMoveEvalCp = 100, bestMoveEvalMate = null))

        val withEvalCp =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e4",
                evalCp = 50,
                evalLossFromBest = null,
            )
        assertNull(withEvalCp.weaknessEvaluationLoss(bestMoveEvalCp = null, bestMoveEvalMate = null))
    }
}
