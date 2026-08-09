package com.chessecho.controller

import com.chessecho.dto.PuzzleResponse
import com.chessecho.service.WeaknessCalculationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class PuzzleController(
    private val weaknessCalculationService: WeaknessCalculationService,
) {
    @GetMapping("/puzzles")
    fun getPuzzles(
        @RequestParam platform: String,
        @RequestParam username: String,
        @RequestParam playerColor: String,
        @RequestParam(required = false) mistakeThreshold: Double?,
        @RequestParam(defaultValue = "3") minMistakeCount: Int,
        @RequestParam(defaultValue = "5") limit: Int,
        @RequestParam(defaultValue = "0") page: Int,
    ): ResponseEntity<List<PuzzleResponse>> {
        val threshold = mistakeThreshold ?: WeaknessCalculationService.DEFAULT_MISTAKE_THRESHOLD
        if (threshold < 0.0) {
            throw IllegalArgumentException("mistakeThreshold must be non-negative")
        }

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = platform,
                username = username,
                playerColor = playerColor,
                mistakeThreshold = threshold,
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
                    playerColor = playerColor.uppercase(),
                    targetMove = w.bestMove,
                    acceptableMoves = w.acceptableMoves,
                    movesPlayed = w.movesPlayed,
                    priority = w.priority,
                    timesReached = w.timesReached,
                    mistakeCount = w.mistakeCount,
                    mistakeRate = w.mistakeRate,
                    evalCp = w.evalCp,
                )
            }

        return ResponseEntity.ok(puzzles)
    }
}
