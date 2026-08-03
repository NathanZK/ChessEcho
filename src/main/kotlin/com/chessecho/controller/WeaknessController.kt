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
        @RequestParam(defaultValue = "0.8") minEvalLoss: Double,
        @RequestParam(defaultValue = "0.3") acceptableThreshold: Double,
    ): ResponseEntity<List<WeaknessResponse>> {
        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = platform,
                username = username,
                playerColor = playerColor,
                minEvalLoss = minEvalLoss,
                acceptableThreshold = acceptableThreshold,
            )
        return ResponseEntity.ok(weaknesses)
    }
}
