package com.chessecho.service

import com.chessecho.domain.MoveEvaluation

internal fun MoveEvaluation.weaknessEvaluationLoss(
    bestMoveEvalCp: Int?,
    bestMoveEvalMate: Int? = null,
): Double? =
    evalLossFromBest ?: EngineAnalysisService.calculateEvalLoss(
        EvalScore(cp = bestMoveEvalCp, mate = bestMoveEvalMate),
        EvalScore(cp = evalCp, mate = evalMate),
    )
