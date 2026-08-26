package com.chessecho.controller

import com.chessecho.dto.HumanMoveBfsResponse
import com.chessecho.dto.HumanMoveFinalizeResponse
import com.chessecho.service.HumanMoveBfsService
import com.chessecho.service.HumanMoveDistributionFinalizationService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(HumanMoveBfsController::class)
class HumanMoveBfsControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var humanMoveBfsService: HumanMoveBfsService

    @MockBean
    lateinit var humanMoveDistributionFinalizationService: HumanMoveDistributionFinalizationService

    @Test
    fun `POST bfs returns 200 with response body`() {
        val request =
            mapOf(
                "ratingBand" to "1000-1200",
                "seedPlayers" to listOf("hikaru"),
            )
        whenever(humanMoveBfsService.runBfs(any())).thenReturn(
            HumanMoveBfsResponse(
                ratingBand = "1000-1200",
                seedPlayers = 1,
                playersVisited = 1,
                maxDepthReached = 0,
                maxGamesPerPlayer = 100,
                gamesInspected = 0,
                rapidGames = 0,
                qualifyingGames = 0,
                uniqueGamesProcessed = 0,
                uniquePositions = 0,
                totalObservations = 0,
                stopReason = "EMPTY_FRONTIER",
            ),
        )

        mockMvc.post("/api/admin/human-move-distribution/bfs") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.ratingBand") { value("1000-1200") }
            jsonPath("$.stopReason") { value("EMPTY_FRONTIER") }
        }
    }

    @Test
    fun `POST finalize returns 200 with counts`() {
        val request =
            mapOf(
                "ratingBand" to "1000-1200",
                "minObservations" to 5,
            )
        whenever(humanMoveDistributionFinalizationService.finalize(any())).thenReturn(
            HumanMoveFinalizeResponse(
                ratingBand = "1000-1200",
                minObservations = 5,
                positionsEvaluated = 100,
                positionsRemoved = 40,
                rowsRemoved = 90,
                positionsRetained = 60,
            ),
        )

        mockMvc.post("/api/admin/human-move-distribution/finalize") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.ratingBand") { value("1000-1200") }
            jsonPath("$.minObservations") { value(5) }
            jsonPath("$.positionsEvaluated") { value(100) }
            jsonPath("$.positionsRemoved") { value(40) }
            jsonPath("$.rowsRemoved") { value(90) }
            jsonPath("$.positionsRetained") { value(60) }
        }
    }

    @Test
    fun `POST finalize returns 400 when rating band is invalid`() {
        val request =
            mapOf(
                "ratingBand" to "not-a-band",
                "minObservations" to 5,
            )
        doThrow(IllegalArgumentException("Invalid rating band: not-a-band"))
            .whenever(humanMoveDistributionFinalizationService).finalize(any())

        mockMvc.post("/api/admin/human-move-distribution/finalize") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("VALIDATION_ERROR") }
        }
    }
}
