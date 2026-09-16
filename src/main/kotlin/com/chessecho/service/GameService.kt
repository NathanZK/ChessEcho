package com.chessecho.service

import com.chessecho.domain.Game
import com.chessecho.domain.Platform
import com.chessecho.dto.GameDto
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import java.util.UUID

@Service
class GameService(
    private val gameRepository: GameRepository,
    private val chessAccountRepository: ChessAccountRepository,
) {
    @Autowired(required = false)
    private var accountOwnershipService: AccountOwnershipService? = null

    @Transactional(readOnly = true)
    fun getGames(
        username: String,
        platform: Platform,
        pageable: Pageable,
    ): Page<GameDto> {
        val requestAttributes = RequestContextHolder.getRequestAttributes()
        val ownership = accountOwnershipService
        if (requestAttributes != null && ownership != null) {
            val principal =
                requestAttributes.getAttribute(
                    com.chessecho.web.SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
                    org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST,
                ) as? AuthenticatedPrincipal
            val account = ownership.resolvePrivateRead(platform, username, principal)
            return gameRepository
                .findAllByChessAccountOrderByPlayedAtDesc(account, pageable)
                .map { it.toDto() }
        }

        val account =
            chessAccountRepository.findByPlatformAndUsernameIgnoreCase(platform.name, username)
                ?: return Page.empty()

        return gameRepository
            .findAllByChessAccountOrderByPlayedAtDesc(account, pageable)
            .map { it.toDto() }
    }

    @Transactional(readOnly = true)
    fun getGames(
        accountId: UUID,
        pageable: Pageable,
        principal: AuthenticatedPrincipal?,
    ): Page<GameDto> {
        val account =
            accountOwnershipService?.resolvePrivateRead(accountId, principal)
                ?: return Page.empty()
        return gameRepository
            .findAllByChessAccountOrderByPlayedAtDesc(account, pageable)
            .map { it.toDto() }
    }

    @Transactional(readOnly = true)
    fun getGames(
        username: String,
        platform: Platform,
        pageable: Pageable,
        principal: AuthenticatedPrincipal?,
    ): Page<GameDto> {
        val account =
            accountOwnershipService?.resolvePrivateRead(platform, username, principal)
                ?: chessAccountRepository.findByPlatformAndUsernameIgnoreCase(platform.name, username)
                ?: return Page.empty()
        return gameRepository
            .findAllByChessAccountOrderByPlayedAtDesc(account, pageable)
            .map { it.toDto() }
    }

    private fun Game.toDto(): GameDto {
        return GameDto(
            id = this.id.toString(),
            platformGameId = this.platformGameId,
            timeControl = this.timeControl,
            playedAt = this.playedAt,
            result = this.result,
            whiteUsername = this.whiteUsername,
            blackUsername = this.blackUsername,
            pgn = this.pgn,
        )
    }
}
