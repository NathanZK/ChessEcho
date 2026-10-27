package com.chessecho.integration.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.Platform
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import com.chessecho.service.GameService
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.web.UnauthenticatedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertFailsWith

/**
 * Issue #457, task T6. Games are shared account-derived data: they are keyed by
 * `chess_account_id` alone and are identical no matter who asks. Any authenticated principal may
 * therefore read them for any account that exists, whether or not that principal currently
 * connects it (Decision 1, approved at the human gate).
 */
@SpringBootTest
@ActiveProfiles("test")
class SharedGameServiceReadIntegrationTest {
    @Autowired
    private lateinit var gameService: GameService

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var gameRepository: GameRepository

    private fun user() = appUserRepository.save(AppUser(email = "${UUID.randomUUID()}@example.com"))

    private fun accountWithGame(owner: AppUser?): ChessAccount {
        val account =
            chessAccountRepository.save(
                ChessAccount(
                    platform = Platform.CHESS_COM.name,
                    username = "u${UUID.randomUUID().toString().take(8)}",
                ),
            )
        gameRepository.save(Game(chessAccount = account, platformGameId = "g-${UUID.randomUUID()}", pgn = "1. e4 e5"))
        return account
    }

    @Test
    fun `a principal who does not connect the account still reads its shared games`() {
        val account = accountWithGame(user())
        val stranger = AuthenticatedPrincipal(user().id, devPrincipal = false)

        val games = gameService.getGames(account.id, PageRequest.of(0, 20), stranger)

        assertEquals(1, games.totalElements)
    }

    @Test
    fun `the connecting principal still reads its shared games`() {
        val owner = user()
        val account = accountWithGame(owner)

        val games = gameService.getGames(account.id, PageRequest.of(0, 20), AuthenticatedPrincipal(owner.id, false))

        assertEquals(1, games.totalElements)
    }

    @Test
    fun `an unauthenticated caller is still rejected for an accountId read`() {
        val account = accountWithGame(user())

        assertFailsWith<UnauthenticatedException> {
            gameService.getGames(account.id, PageRequest.of(0, 20), null)
        }
    }
}
