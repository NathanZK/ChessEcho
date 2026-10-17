package com.chessecho.service

import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeaknessEvaluationLossKtTest {
    private val position = Position(hash = "pos-eval-hash", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    private val analysis =
        EngineAnalysis(
            position = position,
            depth = 16,
            baselineEvalCp = 100,
            bestMove = "e4",
            bestMoveEvalCp = 100,
        )

    @Test
    fun `case 1 precomputed loss is preserved directly without recalculation`() {
        val evaluation =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "Qh5",
                evalCp = -50,
                evalLossFromBest = 1.5,
            )

        val loss = evaluation.weaknessEvaluationLoss(bestMoveEvalCp = 100)

        assertEquals(1.5, loss)
    }

    @Test
    fun `case 2 fallback calculation computes loss when evalLossFromBest is null`() {
        val evaluation =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "Qh5",
                evalCp = 50,
                evalLossFromBest = null,
            )

        val loss = evaluation.weaknessEvaluationLoss(bestMoveEvalCp = 150)

        // (150 - 50) / 100.0 = 1.0
        assertEquals(1.0, loss)
    }

    @Test
    fun `case 3 unavailable evaluation returns null when bestMoveEvalCp or evalCp is null`() {
        val withNullEvalCp =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "Qh5",
                evalCp = null,
                evalLossFromBest = null,
            )
        val withEvalCp =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "Qh5",
                evalCp = 50,
                evalLossFromBest = null,
            )

        assertNull(withNullEvalCp.weaknessEvaluationLoss(bestMoveEvalCp = 100))
        assertNull(withEvalCp.weaknessEvaluationLoss(bestMoveEvalCp = null))
        assertNull(withNullEvalCp.weaknessEvaluationLoss(bestMoveEvalCp = null))
    }

    @Test
    fun `case 4 genuine zero loss produces 0_0 when bestMoveEvalCp equals evalCp`() {
        val evaluation =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e4",
                evalCp = 80,
                evalLossFromBest = null,
            )

        val loss = evaluation.weaknessEvaluationLoss(bestMoveEvalCp = 80)

        assertEquals(0.0, loss)
    }

    @Test
    fun `case 5 positive loss and clamping for shifted or suboptimal baseline`() {
        val clampedEvaluation =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e4",
                evalCp = 120,
                evalLossFromBest = null,
            )
        val positiveLossEvaluation =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e4",
                evalCp = 50,
                evalLossFromBest = null,
            )

        // Clamped: evalCp > bestMoveEvalCp clamps to 0.0
        assertEquals(0.0, clampedEvaluation.weaknessEvaluationLoss(bestMoveEvalCp = 50))

        // Positive loss: bestMoveEvalCp > evalCp produces positive loss
        assertEquals(0.7, positiveLossEvaluation.weaknessEvaluationLoss(bestMoveEvalCp = 120))
    }
}
