package com.chessecho.controller

import com.chessecho.domain.Platform
import com.chessecho.dto.GameDto
import com.chessecho.service.GameService
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/games")
class GameController(
    private val gameService: GameService,
) {
    @GetMapping
    fun getGames(
        @RequestParam username: String,
        @RequestParam platform: Platform,
        @ParameterObject pageable: Pageable,
    ): Page<GameDto> {
        return gameService.getGames(username, platform, pageable)
    }
}
