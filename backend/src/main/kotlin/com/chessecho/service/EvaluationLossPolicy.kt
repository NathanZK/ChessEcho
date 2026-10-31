package com.chessecho.service

import kotlin.math.abs

/**
 * Pure domain policy encapsulating the 8-case piecewise evaluation loss calculation.
 *
 * Scores must be pre-normalized to the perspective of the player to move in the baseline position
 * (positive scores indicate advantage for the player to move; root-relative distance).
 */
object EvaluationLossPolicy {
    /**
     * Calculates evaluation loss in pawns relative to Stockfish's best move.
     */
    fun calculateEvalLoss(
        bestScore: EvalScore?,
        moveScore: EvalScore?,
    ): Double? {
        if (!hasValidScore(bestScore) || !hasValidScore(moveScore)) return null
        val best = bestScore ?: return null
        val move = moveScore ?: return null

        if (isInconclusiveLosingMateDefense(best, move)) return null
        if (isSemanticallyEqualScore(best, move)) return 0.0

        return dispatchLossScenario(best, move)?.coerceIn(0.0, 20.0)
    }

    /**
     * Calculates evaluation loss in pawns from centipawn values.
     */
    fun calculateEvalLoss(
        bestMoveEvalCp: Int?,
        moveEvalCp: Int?,
    ): Double? =
        calculateEvalLoss(
            bestMoveEvalCp?.let { EvalScore(it, null) },
            moveEvalCp?.let { EvalScore(it, null) },
        )

    private fun dispatchLossScenario(
        best: EvalScore,
        move: EvalScore,
    ): Double? =
        when {
            best.mate == null && move.mate == null -> computeCentipawnLoss(best, move)
            best.mate != null && best.mate > 0 -> computeWinningMateLoss(best.mate, move)
            best.mate == null && move.mate != null -> computeCpBestVsMateLoss(best, move.mate)
            best.mate != null && best.mate < 0 -> computeLosingMateLoss(best.mate, move)
            else -> null
        }

    private fun hasValidScore(score: EvalScore?): Boolean {
        if (score == null || score.mate == 0) return false
        return score.cp != null || score.mate != null
    }

    private fun isInconclusiveLosingMateDefense(
        bestScore: EvalScore,
        moveScore: EvalScore,
    ): Boolean {
        val isLosingMateBest = bestScore.mate?.let { it < 0 } ?: false
        val isNegativeCpMove = moveScore.mate == null && (moveScore.cp?.let { it < 0 } ?: false)
        return isLosingMateBest && isNegativeCpMove
    }

    private fun isSemanticallyEqualScore(
        bestScore: EvalScore,
        moveScore: EvalScore,
    ): Boolean =
        when {
            bestScore.mate != null && moveScore.mate != null -> bestScore.mate == moveScore.mate
            bestScore.mate == null && moveScore.mate == null ->
                bestScore.cp != null && bestScore.cp == moveScore.cp
            else -> false
        }

    private fun computeCentipawnLoss(
        bestScore: EvalScore,
        moveScore: EvalScore,
    ): Double? {
        val bestCp = bestScore.cp ?: return null
        val moveCp = moveScore.cp ?: return null
        return maxOf(0.0, (bestCp - moveCp) / 100.0)
    }

    private fun computeWinningMateLoss(
        bestMate: Int,
        moveScore: EvalScore,
    ): Double? =
        when {
            moveScore.mate != null && moveScore.mate > 0 -> {
                if (moveScore.mate <= bestMate) {
                    0.0
                } else {
                    minOf(0.50, (moveScore.mate - bestMate) * 0.10)
                }
            }
            moveScore.mate == null -> {
                val moveCp = moveScore.cp ?: return null
                (8.0 + maxOf(0.0, 5.0 - moveCp / 100.0)).coerceIn(8.0, 15.0)
            }
            moveScore.mate < 0 -> {
                val d = abs(moveScore.mate)
                (15.0 + maxOf(0, 10 - d) * 0.50).coerceIn(15.0, 20.0)
            }
            else -> null
        }

    private fun computeCpBestVsMateLoss(
        bestScore: EvalScore,
        moveMate: Int,
    ): Double? =
        when {
            moveMate > 0 -> 0.0
            moveMate < 0 -> {
                val bestCp = bestScore.cp ?: return null
                val base = maxOf(0.0, bestCp / 100.0)
                val d = abs(moveMate)
                (base + 10.0 + maxOf(0, 10 - d) * 0.50).coerceIn(10.0, 20.0)
            }
            else -> null
        }

    private fun computeLosingMateLoss(
        bestMate: Int,
        moveScore: EvalScore,
    ): Double? =
        when {
            moveScore.mate != null && moveScore.mate < 0 -> {
                val bestDist = abs(bestMate)
                val moveDist = abs(moveScore.mate)
                if (moveDist >= bestDist) {
                    0.0
                } else {
                    minOf(5.0, (bestDist - moveDist) * 1.0)
                }
            }
            moveScore.mate != null && moveScore.mate > 0 -> 0.0
            moveScore.mate == null && moveScore.cp != null && moveScore.cp >= 0 -> 0.0
            else -> null
        }
}
