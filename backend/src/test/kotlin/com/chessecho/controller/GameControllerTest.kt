package com.chessecho.controller

import com.chessecho.domain.Platform
import com.chessecho.dto.GameDto
import com.chessecho.service.GameService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(GameController::class)
class GameControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var gameService: GameService

    @Test
    fun `should return paginated games`() {
        val gameDto =
            GameDto(
                id = "123",
                platformGameId = "url",
                timeControl = "blitz",
                playedAt = Instant.parse("2023-10-01T12:00:00Z"),
                result = "win",
                whiteUsername = "white",
                blackUsername = "black",
                pgn = "pgn",
            )
        val page = PageImpl(listOf(gameDto), PageRequest.of(0, 20), 1)

        whenever(gameService.getGames(eq("user"), eq(Platform.CHESS_COM), any())).thenReturn(page)

        mockMvc.perform(
            get("/api/games")
                .param("username", "user")
                .param("platform", "CHESS_COM")
                .param("page", "0")
                .param("size", "20"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].id").value("123"))
            .andExpect(jsonPath("$.content[0].platformGameId").value("url"))
            .andExpect(jsonPath("$.totalElements").value(1))
    }
}
