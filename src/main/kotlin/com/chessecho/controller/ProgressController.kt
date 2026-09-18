package com.chessecho.controller

import com.chessecho.domain.PlayerColor
import com.chessecho.dto.ProgressResponse
import com.chessecho.service.ProgressService
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/positions")
class ProgressController(
    private val progressService: ProgressService,
) {
    @GetMapping("/{positionId}/progress")
    fun getProgress(
        @PathVariable positionId: UUID,
        @RequestParam playerColor: PlayerColor,
        principal: AuthenticatedPrincipal,
    ): ProgressResponse = progressService.getProgress(positionId, playerColor, principal)
}
