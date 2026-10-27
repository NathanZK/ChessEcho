package com.chessecho.controller

import com.chessecho.dto.HumanMoveBfsRequest
import com.chessecho.dto.HumanMoveBfsResponse
import com.chessecho.dto.HumanMoveFinalizeResponse
import com.chessecho.service.HumanMoveBfsService
import com.chessecho.service.HumanMoveDistributionFinalizationService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
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
    fun `POST bfs binds every non-empty combination of optional bounds`() {
        data class BoundsCase(
            val bounds: Map<String, Any>,
            val maxPlayers: Int?,
            val maxDepth: Int?,
            val maxQualifyingGames: Int?,
        )

        val cases =
            listOf(
                BoundsCase(mapOf("maxPlayers" to 2), 2, null, null),
                BoundsCase(mapOf("maxDepth" to 1), null, 1, null),
                BoundsCase(mapOf("maxQualifyingGames" to 5), null, null, 5),
                BoundsCase(mapOf("maxPlayers" to 2, "maxDepth" to 1), 2, 1, null),
                BoundsCase(mapOf("maxPlayers" to 2, "maxQualifyingGames" to 5), 2, null, 5),
                BoundsCase(mapOf("maxDepth" to 1, "maxQualifyingGames" to 5), null, 1, 5),
                BoundsCase(mapOf("maxPlayers" to 2, "maxDepth" to 1, "maxQualifyingGames" to 5), 2, 1, 5),
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

        cases.forEach { case ->
            mockMvc.post("/api/admin/human-move-distribution/bfs") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "ratingBand" to "1000-1200",
                            "seedPlayers" to listOf("hikaru"),
                        ) + case.bounds,
                    )
            }.andExpect {
                status { isOk() }
            }
        }

        val captor = argumentCaptor<HumanMoveBfsRequest>()
        verify(humanMoveBfsService, times(cases.size)).runBfs(captor.capture())
        cases.zip(captor.allValues).forEach { (case, request) ->
            assertEquals(case.maxPlayers, request.maxPlayers)
            assertEquals(case.maxDepth, request.maxDepth)
            assertEquals(case.maxQualifyingGames, request.maxQualifyingGames)
        }
    }

    @Test
    fun `POST bfs binds explicit null as an absent bound`() {
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
                stopReason = "MAX_DEPTH",
            ),
        )

        mockMvc.post("/api/admin/human-move-distribution/bfs") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"ratingBand":"1000-1200","seedPlayers":["hikaru"],"maxPlayers":null,"maxDepth":0}"""
        }.andExpect {
            status { isOk() }
        }

        val captor = argumentCaptor<HumanMoveBfsRequest>()
        verify(humanMoveBfsService).runBfs(captor.capture())
        assertEquals(null, captor.firstValue.maxPlayers)
        assertEquals(0, captor.firstValue.maxDepth)
        assertEquals(null, captor.firstValue.maxQualifyingGames)
    }

    @Test
    fun `POST bfs rejects a request with all bounds omitted`() {
        whenever(humanMoveBfsService.runBfs(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<HumanMoveBfsRequest>(0)
            val allBoundsAbsent =
                listOf<Any?>(request.maxPlayers, request.maxDepth, request.maxQualifyingGames).all { it == null }
            if (allBoundsAbsent) {
                throw IllegalArgumentException("At least one BFS bound must be supplied")
            }
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
            )
        }

        mockMvc.post("/api/admin/human-move-distribution/bfs") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"ratingBand":"1000-1200","seedPlayers":["hikaru"]}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("VALIDATION_ERROR") }
        }
    }

    @Test
    fun `POST bfs returns 200 with response body`() {
        val request =
            mapOf(
                "ratingBand" to "1000-1200",
                "seedPlayers" to listOf("hikaru"),
                "maxDepth" to 0,
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

        val captor = argumentCaptor<HumanMoveBfsRequest>()
        verify(humanMoveBfsService).runBfs(captor.capture())
        assertEquals(emptyList<String>(), captor.firstValue.excludedPlayers)
    }

    @Test
    fun `POST bfs binds excludedPlayers`() {
        val request =
            mapOf(
                "ratingBand" to "1000-1200",
                "seedPlayers" to listOf("hikaru"),
                "excludedPlayers" to listOf("eval-user", "holdout-user"),
                "maxDepth" to 0,
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
        }

        val captor = argumentCaptor<HumanMoveBfsRequest>()
        verify(humanMoveBfsService).runBfs(captor.capture())
        assertEquals(listOf("eval-user", "holdout-user"), captor.firstValue.excludedPlayers)
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
