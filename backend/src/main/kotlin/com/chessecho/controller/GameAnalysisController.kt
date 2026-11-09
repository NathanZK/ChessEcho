package com.chessecho.controller

import com.chessecho.dto.GameAnalysisResponseDto
import com.chessecho.dto.GameAnalysisStatus
import com.chessecho.service.GameAnalysisService
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.web.OptionalAuthenticatedPrincipal
import com.chessecho.web.SessionAuthenticationFilter
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/games")
class GameAnalysisController(
    private val gameAnalysisService: GameAnalysisService,
) {
    @PostMapping("/{id}/analyze")
    fun analyzeGame(
        @PathVariable id: UUID,
        @OptionalAuthenticatedPrincipal
        @RequestAttribute(name = SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, required = false)
        principal: AuthenticatedPrincipal?,
    ): ResponseEntity<GameAnalysisResponseDto> {
        val response = gameAnalysisService.analyzeGame(id, principal)
        return if (response.analysisStatus == GameAnalysisStatus.CAPACITY_EXCEEDED) {
            ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(response)
        } else {
            ResponseEntity.ok(response)
        }
    }
}
