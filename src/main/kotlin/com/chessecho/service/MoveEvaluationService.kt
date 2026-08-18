package com.chessecho.service

import com.chessecho.dto.MoveEvaluationResponse
import com.github.bhlangonijr.chesslib.Board
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import kotlin.math.round

@Service
class MoveEvaluationService(
    private val stockfishService: StockfishService,
    @Value("\${engine.exploration.max-eval-loss:0.80}")
    private val explorationMaxEvalLoss: Double = DEFAULT_EXPLORATION_MAX_EVAL_LOSS,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEFAULT_EXPLORATION_MAX_EVAL_LOSS = 0.80
    }

    fun evaluateMove(
        fen: String,
        move: String,
    ): MoveEvaluationResponse {
        val board = Board()
        try {
            board.loadFromFen(fen)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid FEN position: $fen")
        }

        val isLegal =
            try {
                if (board.doMove(move)) {
                    board.undoMove()
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }

        if (!isLegal) {
            throw IllegalArgumentException("Illegal or unparseable move '$move' for FEN '$fen'")
        }

        // 1. Obtain baseline best move and MultiPV candidates
        val candidates = stockfishService.analyzeMultiPv(fen = fen, depth = 16, multiPv = 5)
        val bestCandidate =
            candidates.firstOrNull()
                ?: throw IllegalStateException("Stockfish produced no candidate moves for FEN: $fen")

        val bestMove = bestCandidate.move
        val bestEvalCp = scoreToCp(bestCandidate.score)

        // 2. Obtain evaluation score for the exact requested move
        val matchingCandidate = candidates.find { it.move == move }
        val userEvalCp: Int? =
            if (matchingCandidate != null) {
                scoreToCp(matchingCandidate.score)
            } else {
                log.debug("Move {} not in MultiPV=5 for FEN {}, running single move evaluation...", move, fen)
                val singleAnalysis = stockfishService.evaluateSingleMove(fen = fen, sanMove = move, depth = 16)
                scoreToCp(singleAnalysis?.score)
            }

        val rawLoss = calculateEvalLoss(bestEvalCp, userEvalCp)
        val evalLoss = round(rawLoss * 100.0) / 100.0
        val acceptable = evalLoss <= explorationMaxEvalLoss

        return MoveEvaluationResponse(
            fen = fen,
            move = move,
            bestMove = bestMove,
            bestEvalCp = bestEvalCp,
            evalCp = userEvalCp,
            evalLoss = evalLoss,
            maxEvalLoss = explorationMaxEvalLoss,
            threshold = explorationMaxEvalLoss,
            acceptable = acceptable,
        )
    }

    private fun calculateEvalLoss(
        bestCp: Int?,
        userCp: Int?,
    ): Double {
        if (bestCp == null || userCp == null) return 0.0
        return maxOf(0.0, (bestCp - userCp) / 100.0)
    }

    private fun scoreToCp(score: EvalScore?): Int? {
        if (score == null) return null
        if (score.cp != null) return score.cp
        if (score.mate != null) {
            return if (score.mate > 0) 10000 - (score.mate * 10) else -10000 - (score.mate * 10)
        }
        return null
    }
}
