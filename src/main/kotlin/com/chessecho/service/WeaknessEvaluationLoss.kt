package com.chessecho.service

import com.chessecho.domain.MoveEvaluation

internal fun MoveEvaluation.weaknessEvaluationLoss(bestMoveEvalCp: Int?): Double? =
    evalLossFromBest
        ?: if (bestMoveEvalCp == null || evalCp == null) {
            null
        } else {
            maxOf(0.0, (bestMoveEvalCp - evalCp) / 100.0)
        }
