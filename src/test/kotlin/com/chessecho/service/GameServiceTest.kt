package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.Platform
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class GameServiceTest {
    @Mock
    private lateinit var gameRepository: GameRepository

    @Mock
    private lateinit var chessAccountRepository: ChessAccountRepository

    @InjectMocks
    private lateinit var gameService: GameService

    @Test
    fun `should return empty page if account not found`() {
        whenever(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "user")).thenReturn(null)

        val page = gameService.getGames("user", Platform.CHESS_COM, PageRequest.of(0, 20))

        assertEquals(0, page.totalElements)
    }

    @Test
    fun `should return paginated games if account found`() {
        val user = AppUser(email = "test@test.com")
        val account = ChessAccount(user = user, platform = "CHESS_COM", username = "user")
        val game =
            Game(
                chessAccount = account,
                platformGameId = "url",
                pgn = "pgn",
                timeControl = "blitz",
                playedAt = Instant.now(),
                result = "win",
                whiteUsername = "white",
                blackUsername = "black",
            )
        val pagedGames = PageImpl(listOf(game))

        whenever(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "user")).thenReturn(account)
        whenever(gameRepository.findAllByChessAccountOrderByPlayedAtDesc(eq(account), any())).thenReturn(pagedGames)

        val page = gameService.getGames("user", Platform.CHESS_COM, PageRequest.of(0, 20))

        assertEquals(1, page.totalElements)
        assertEquals("url", page.content[0].platformGameId)
    }
}
