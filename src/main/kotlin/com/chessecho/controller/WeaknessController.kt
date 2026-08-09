package com.chessecho.controller

import com.chessecho.dto.WeaknessResponse
import com.chessecho.service.WeaknessCalculationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class WeaknessController(
    private val weaknessCalculationService: WeaknessCalculationService,
) {
    @GetMapping("/positions/weaknesses")
    fun getWeaknesses(
        @RequestParam platform: String,
        @RequestParam username: String,
        @RequestParam playerColor: String,
        @RequestParam(required = false) mistakeThreshold: Double?,
        @RequestParam(defaultValue = "3") minMistakeCount: Int,
    ): ResponseEntity<List<WeaknessResponse>> {
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
        return ResponseEntity.ok(weaknesses)
    }
}
