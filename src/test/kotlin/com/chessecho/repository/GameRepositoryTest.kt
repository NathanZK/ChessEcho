package com.chessecho.repository

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@DataJpaTest
@ActiveProfiles("test")
class GameRepositoryTest {
    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Test
    fun `should find platform game ids by chess account and in list`() {
        val user = appUserRepository.save(AppUser(email = "test@example.com"))
        val account = chessAccountRepository.save(ChessAccount(user = user, platform = "CHESS_COM", username = "testuser"))

        val game1 =
            Game(
                chessAccount = account,
                platformGameId = "url1",
                pgn = "pgn1",
                timeControl = "blitz",
                playedAt = Instant.now(),
                result = "win",
                whiteUsername = "testuser",
                blackUsername = "opponent1",
            )
        val game2 =
            Game(
                chessAccount = account,
                platformGameId = "url2",
                pgn = "pgn2",
                timeControl = "rapid",
                playedAt = Instant.now(),
                result = "loss",
                whiteUsername = "opponent2",
                blackUsername = "testuser",
            )
        gameRepository.saveAll(listOf(game1, game2))

        val existingIds =
            gameRepository.findPlatformGameIdsByChessAccountAndPlatformGameIdIn(
                account,
                listOf("url1", "url3", "url2"),
            )

        assertEquals(2, existingIds.size)
        assertTrue(existingIds.contains("url1"))
        assertTrue(existingIds.contains("url2"))
    }
}
