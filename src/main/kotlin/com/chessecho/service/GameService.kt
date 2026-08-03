package com.chessecho.service

import com.chessecho.domain.Game
import com.chessecho.dto.GameDto
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GameService(
    private val gameRepository: GameRepository,
    private val chessAccountRepository: ChessAccountRepository,
) {
    @Transactional(readOnly = true)
    fun getGames(
        username: String,
        platform: String,
        pageable: Pageable,
    ): Page<GameDto> {
        val account =
            chessAccountRepository.findByPlatformAndUsernameIgnoreCase(platform, username)
                ?: return Page.empty()

        return gameRepository.findAllByChessAccountOrderByPlayedAtDesc(account, pageable)
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
