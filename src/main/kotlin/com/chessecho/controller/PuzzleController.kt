package com.chessecho.controller

import com.chessecho.domain.ContinuationMode
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.dto.ContinuationResponse
import com.chessecho.dto.MoveEvaluationResponse
import com.chessecho.dto.PuzzleResponse
import com.chessecho.service.MoveEvaluationService
import com.chessecho.service.WeaknessCalculationService
import com.chessecho.service.continuation.ContinuationService
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class PuzzleController(
    private val weaknessCalculationService: WeaknessCalculationService,
    private val continuationService: ContinuationService,
    private val moveEvaluationService: MoveEvaluationService,
) {
    @GetMapping("/puzzles")
    fun getPuzzles(
        @RequestParam platform: Platform,
        @RequestParam username: String,
        @RequestParam playerColor: PlayerColor,
        @Parameter(
            description =
                "Minimum engine evaluation loss, in pawns, required for a move to be classified as a mistake. " +
                    "A lower value means a stricter definition of a mistake.",
            example = "0.8",
        )
        @RequestParam(required = false) minEvalLoss: Double?,
        @RequestParam(defaultValue = "3") minMistakeCount: Int,
        @RequestParam(defaultValue = "5") limit: Int,
        @RequestParam(defaultValue = "0") page: Int,
    ): ResponseEntity<List<PuzzleResponse>> {
        val threshold = minEvalLoss ?: WeaknessCalculationService.DEFAULT_MIN_EVAL_LOSS
        if (threshold < 0.0) {
            throw IllegalArgumentException("minEvalLoss must be non-negative")
        }

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = platform,
                username = username,
                playerColor = playerColor,
                minEvalLoss = threshold,
                minMistakeCount = maxOf(1, minMistakeCount),
            )

        val pagedWeaknesses =
            weaknesses
                .drop(page * maxOf(1, limit))
                .take(maxOf(1, limit))

        val puzzles =
            pagedWeaknesses.map { w ->
                PuzzleResponse(
                    puzzleId = w.positionId,
                    fen = w.fen,
                    playerColor = w.playerColor,
                    targetMove = w.bestMove,
                    acceptableMoves = w.acceptableMoves,
                    movesPlayed = w.movesPlayed,
                    priority = w.priority,
                    timesReached = w.timesReached,
                    mistakeCount = w.mistakeCount,
                    mistakeRate = w.mistakeRate,
                    evalCp = w.evalCp,
                    gameUrls = w.gameUrls,
                    recommendationPriority = w.recommendationPriority,
                    objectiveEvidenceState = w.objectiveEvidenceState,
                    evidenceCombination = w.evidenceCombination,
                    practicalEvidence = w.practicalEvidence,
                )
            }

        return ResponseEntity.ok(puzzles)
    }

    @GetMapping("/puzzles/continuation")
    fun getContinuation(
        @RequestParam fen: String,
        @RequestParam(defaultValue = "ENGINE") mode: ContinuationMode,
        @RequestParam(required = false) ratingBand: String? = null,
    ): ResponseEntity<ContinuationResponse> {
        val result = continuationService.getContinuation(fen = fen, mode = mode, ratingBand = ratingBand)
        if (result.candidates.isEmpty()) {
            return ResponseEntity.notFound().build()
        }

        return ResponseEntity.ok(
            ContinuationResponse(
                fen = result.fen,
                requestedMode = result.requestedMode,
                effectiveProvider = result.effectiveProvider,
                candidates = result.candidates,
            ),
        )
    }

    @GetMapping("/puzzles/evaluate-move")
    fun evaluateMove(
        @RequestParam fen: String,
        @RequestParam move: String,
    ): ResponseEntity<MoveEvaluationResponse> {
        val response = moveEvaluationService.evaluateMove(fen = fen, move = move)
        return ResponseEntity.ok(response)
    }
}
