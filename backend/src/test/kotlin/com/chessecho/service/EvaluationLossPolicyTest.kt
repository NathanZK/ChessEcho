package com.chessecho.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EvaluationLossPolicyTest {
    @Test
    fun `calculateEvalLoss with centipawn integers handles basic cases`() {
        assertEquals(0.75, EvaluationLossPolicy.calculateEvalLoss(100, 25))
        assertEquals(0.0, EvaluationLossPolicy.calculateEvalLoss(25, 100))
        assertNull(EvaluationLossPolicy.calculateEvalLoss(null, 25))
        assertNull(EvaluationLossPolicy.calculateEvalLoss(100, null))
    }

    @Test
    fun `calculateEvalLoss handles Scenario 1 Centipawn vs Centipawn`() {
        // Equal evaluation
        assertEquals(0.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(100, null), EvalScore(100, null)))
        // Exact mistake boundary (0.80 pawns)
        assertEquals(0.80, EvaluationLossPolicy.calculateEvalLoss(EvalScore(100, null), EvalScore(20, null)))
        // Better move played
        assertEquals(0.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(100, null), EvalScore(200, null)))
        // Cap at 20.0
        assertEquals(20.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(500, null), EvalScore(-2000, null)))
    }

    @Test
    fun `calculateEvalLoss handles Scenario 2 Winning Mate vs Winning Mate Prolongation`() {
        // Equal mate
        assertEquals(0.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, 1)))
        // Faster mate found
        assertEquals(0.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 3), EvalScore(null, 1)))
        // Slight prolongation (mate 2 instead of mate 1)
        assertEquals(0.10, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, 2)))
        // Mate 3 instead of mate 1
        assertEquals(0.20, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, 3)))
        // Mate 5 instead of mate 1
        assertEquals(0.40, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, 5)))
        // Capped at 0.50 (< 0.80, remains acceptable move)
        assertEquals(0.50, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, 10)))
    }

    @Test
    fun `calculateEvalLoss handles Scenario 3 Winning Mate vs Centipawns`() {
        // Threw away forced mate to high advantage
        assertEquals(8.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 1), EvalScore(800, null)))
        assertEquals(8.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 1), EvalScore(500, null)))
        // Threw away forced mate to slight advantage
        assertEquals(11.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 1), EvalScore(200, null)))
        // Threw away forced mate to equal position
        assertEquals(13.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 1), EvalScore(0, null)))
        // Threw away forced mate to losing position (capped at 15.0)
        assertEquals(15.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 1), EvalScore(-300, null)))
    }

    @Test
    fun `calculateEvalLoss handles Scenario 4 Winning Mate vs Losing Mate`() {
        // Blundered into mate in 1 (highest loss)
        assertEquals(19.5, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, -1)))
        assertEquals(19.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, -2)))
        assertEquals(17.5, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, -5)))
        // Capped at 15.0 for slow mate
        assertEquals(15.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, -10)))
    }

    @Test
    fun `calculateEvalLoss handles Scenario 5 Centipawns vs Losing Mate`() {
        // From +2.00 into mate in 1
        assertEquals(16.5, EvaluationLossPolicy.calculateEvalLoss(EvalScore(200, null), EvalScore(null, -1)))
        assertEquals(14.5, EvaluationLossPolicy.calculateEvalLoss(EvalScore(200, null), EvalScore(null, -5)))
        // From 0.00 into mate in 1
        assertEquals(14.5, EvaluationLossPolicy.calculateEvalLoss(EvalScore(0, null), EvalScore(null, -1)))
        assertEquals(12.5, EvaluationLossPolicy.calculateEvalLoss(EvalScore(0, null), EvalScore(null, -5)))
        // From -2.00 into mate in 1
        assertEquals(14.5, EvaluationLossPolicy.calculateEvalLoss(EvalScore(-200, null), EvalScore(null, -1)))
        // Cap at 20.0
        assertEquals(20.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(1500, null), EvalScore(null, -1)))
    }

    @Test
    fun `calculateEvalLoss handles Scenario 6 Centipawns vs Winning Mate`() {
        assertEquals(0.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(200, null), EvalScore(null, 2)))
    }

    @Test
    fun `calculateEvalLoss handles Scenario 7 Losing Mate Defense Shortening vs Prolongation`() {
        // Equal defense
        assertEquals(0.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, -5), EvalScore(null, -5)))
        // Prolonged defense (held out longer)
        assertEquals(0.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, -5), EvalScore(null, -6)))
        // Shortened defense by 1 move (mate in 4 instead of 5 -> 1.0 >= 0.80)
        assertEquals(1.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, -5), EvalScore(null, -4)))
        // Shortened defense by 2 moves (mate in 3 instead of 5 -> 2.0)
        assertEquals(2.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, -5), EvalScore(null, -3)))
        // Shortened defense to mate in 1 (mate in 1 instead of 5 -> 4.0)
        assertEquals(4.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, -5), EvalScore(null, -1)))
        // Capped at 5.0
        assertEquals(5.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, -10), EvalScore(null, -1)))
    }

    @Test
    fun `calculateEvalLoss handles Scenario 8 Losing Mate Escape vs Inconclusive Horizon Effect`() {
        // Proven escape to winning mate
        assertEquals(0.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, -5), EvalScore(null, 2)))
        // Proven escape to equal or positive centipawns
        assertEquals(0.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, -5), EvalScore(0, null)))
        assertEquals(0.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, -5), EvalScore(150, null)))
        // Inconclusive horizon effect (negative centipawns): cannot prove escape, returns null
        assertNull(EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, -5), EvalScore(-500, null)))
    }

    @Test
    fun `calculateEvalLoss handles Precedence, Nulls, and Semantic Equality`() {
        // Null / missing evaluations strictly return null, never 0.0
        assertNull(EvaluationLossPolicy.calculateEvalLoss(null, EvalScore(100, null)))
        assertNull(EvaluationLossPolicy.calculateEvalLoss(EvalScore(100, null), null))
        assertNull(EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, null), EvalScore(100, null)))
        assertNull(EvaluationLossPolicy.calculateEvalLoss(EvalScore(100, null), EvalScore(null, null)))
        // Illegal mate = 0 returns null
        assertNull(EvaluationLossPolicy.calculateEvalLoss(EvalScore(null, 0), EvalScore(100, null)))
        assertNull(EvaluationLossPolicy.calculateEvalLoss(EvalScore(100, null), EvalScore(null, 0)))
        // Semantic equality: mate takes precedence over cp
        assertEquals(0.0, EvaluationLossPolicy.calculateEvalLoss(EvalScore(500, 2), EvalScore(100, 2)))
    }
}
