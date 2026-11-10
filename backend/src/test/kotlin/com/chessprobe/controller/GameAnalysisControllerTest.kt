package com.chessprobe.controller

import com.chessprobe.domain.DecisionSeverity
import com.chessprobe.domain.PositionWeaknessSeverity
import com.chessprobe.dto.AnalyzedPlyDto
import com.chessprobe.dto.GameAnalysisResponseDto
import com.chessprobe.dto.GameAnalysisStatus
import com.chessprobe.service.GameAnalysisService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@WebMvcTest(GameAnalysisController::class)
class GameAnalysisControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var gameAnalysisService: GameAnalysisService

    @Test
    fun `should return 200 with plies when analysis is complete`() {
        val gameId = UUID.randomUUID()
        val plyDto =
            AnalyzedPlyDto(
                plyNumber = 1,
                movePlayed = "e4",
                playerColor = "WHITE",
                preMoveFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                decisionTimeMs = 4000L,
                decisionSeverity = DecisionSeverity.NO_CONCERN,
                bestMove = "e4",
                bestEvalCp = 30,
                evalCp = 30,
                evalLoss = 0.0,
                weaknessSeverity = PositionWeaknessSeverity.NO_CONCERN,
            )
        val response =
            GameAnalysisResponseDto(
                gameId = gameId,
                whiteUsername = "whiteUser",
                blackUsername = "blackUser",
                timeControl = "blitz",
                playedAt = Instant.parse("2026-11-09T12:00:00Z"),
                result = "win",
                analysisStatus = GameAnalysisStatus.COMPLETE,
                plies = listOf(plyDto),
            )

        whenever(gameAnalysisService.analyzeGame(eq(gameId), anyOrNull())).thenReturn(response)

        mockMvc
            .perform(post("/api/games/{id}/analyze", gameId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.gameId").value(gameId.toString()))
            .andExpect(jsonPath("$.analysisStatus").value("COMPLETE"))
            .andExpect(jsonPath("$.plies[0].movePlayed").value("e4"))
            .andExpect(jsonPath("$.plies[0].evalLoss").value(0.0))
    }

    @Test
    fun `should return 429 with Retry-After when capacity is exceeded`() {
        val gameId = UUID.randomUUID()
        val response =
            GameAnalysisResponseDto(
                gameId = gameId,
                whiteUsername = "whiteUser",
                blackUsername = "blackUser",
                timeControl = "blitz",
                playedAt = Instant.parse("2026-11-09T12:00:00Z"),
                result = "win",
                analysisStatus = GameAnalysisStatus.CAPACITY_EXCEEDED,
                plies = emptyList(),
            )

        whenever(gameAnalysisService.analyzeGame(eq(gameId), anyOrNull())).thenReturn(response)

        mockMvc
            .perform(post("/api/games/{id}/analyze", gameId))
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
            .andExpect(jsonPath("$.analysisStatus").value("CAPACITY_EXCEEDED"))
    }

    @Test
    fun `should return 404 when game not found`() {
        val gameId = UUID.randomUUID()
        whenever(gameAnalysisService.analyzeGame(eq(gameId), anyOrNull()))
            .thenThrow(ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found"))

        mockMvc
            .perform(post("/api/games/{id}/analyze", gameId))
            .andExpect(status().isNotFound)
    }
}
