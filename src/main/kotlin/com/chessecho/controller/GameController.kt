package com.chessecho.controller

import com.chessecho.domain.Platform
import com.chessecho.dto.GameDto
import com.chessecho.service.AccountOwnershipService
import com.chessecho.service.GameService
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.web.OptionalAuthenticatedPrincipal
import com.chessecho.web.SessionAuthenticationFilter
import org.springdoc.core.annotations.ParameterObject
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/games")
class GameController(
    private val gameService: GameService,
) {
    @Autowired(required = false)
    private var accountOwnershipService: AccountOwnershipService? = null

    @GetMapping
    fun getGames(
        @RequestParam(required = false) username: String?,
        @RequestParam(required = false) platform: Platform?,
        @RequestParam(required = false) accountId: java.util.UUID?,
        @ParameterObject pageable: Pageable,
        @OptionalAuthenticatedPrincipal
        @RequestAttribute(name = SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, required = false)
        principal: AuthenticatedPrincipal?,
    ): Page<GameDto> {
        return if (accountId != null) {
            gameService.getGames(accountId, pageable, principal)
        } else if (accountOwnershipService == null) {
            gameService.getGames(username ?: "", platform ?: Platform.CHESS_COM, pageable)
        } else {
            gameService.getGames(username ?: "", platform ?: Platform.CHESS_COM, pageable, principal)
        }
    }
}
