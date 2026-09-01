package com.chessecho.controller

import com.chessecho.domain.AsyncJob
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.service.ActiveImportJobException
import com.chessecho.service.GameImportService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals

@WebMvcTest(GameImportController::class)
class GameImportControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var gameImportService: GameImportService

    @MockBean
    lateinit var asyncJobRepository: AsyncJobRepository

    private val validRequest =
        mapOf(
            "username" to "hikaru",
            "platform" to "CHESS_COM",
            "timeControls" to listOf("RAPID", "BLITZ"),
            "playerColor" to "BOTH",
        )

    @Test
    fun `POST games import returns 202 with jobId on valid request`() {
        val job = AsyncJob(username = "hikaru", platform = "CHESS_COM")
        whenever(gameImportService.createImportJob(any())).thenReturn(job)

        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(validRequest)
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.jobId") { exists() }
            jsonPath("$.status") { value("QUEUED") }
        }
    }

    @Test
    fun `POST games import returns 400 when username is blank`() {
        val request = validRequest + ("username" to "")

        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("VALIDATION_ERROR") }
            jsonPath("$.details[0]") { exists() }
        }
    }

    @Test
    fun `POST games import returns 400 when timeControls is empty`() {
        val request = validRequest + ("timeControls" to emptyList<String>())

        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("VALIDATION_ERROR") }
        }
    }

    @Test
    fun `POST games import returns 400 when playerColor is invalid`() {
        val request = validRequest + ("playerColor" to "invalid_color")

        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("VALIDATION_ERROR") }
        }
    }

    @Test
    fun `POST games import returns 409 when active job exists for username`() {
        doThrow(ActiveImportJobException("Active job exists for hikaru"))
            .whenever(gameImportService).createImportJob(any())

        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(validRequest)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error") { value("CONFLICT") }
        }
    }

    @Test
    fun `GET jobs id returns job status with counts`() {
        val jobId = UUID.randomUUID()
        val job =
            AsyncJob(
                id = jobId,
                username = "hikaru",
                platform = "CHESS_COM",
                status = "COMPLETED",
                gamesImported = 142,
                gamesSkipped = 30,
                gamesProcessed = 200,
                gamesFilteredOut = 28,
                analysisStatus = "FAILED",
            )
        whenever(asyncJobRepository.findById(eq(jobId))).thenReturn(Optional.of(job))

        val result =
            mockMvc.get("/api/jobs/$jobId")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.jobId") { value(jobId.toString()) }
                    jsonPath("$.status") { value("COMPLETED") }
                    jsonPath("$.gamesImported") { value(142) }
                    jsonPath("$.gamesSkipped") { value(30) }
                    jsonPath("$.gamesProcessed") { value(200) }
                    jsonPath("$.gamesFilteredOut") { value(28) }
                    jsonPath("$.analysisStatus") { value("FAILED") }
                }
                .andReturn()

        val responseFields = objectMapper.readTree(result.response.contentAsString).fieldNames().asSequence().toSet()
        assertEquals(
            setOf(
                "jobId",
                "status",
                "gamesImported",
                "gamesSkipped",
                "gamesProcessed",
                "gamesFilteredOut",
                "errorMessage",
                "analysisStatus",
            ),
            responseFields,
        )
    }

    @Test
    fun `GET jobs id returns 404 when job does not exist`() {
        val jobId = UUID.randomUUID()
        whenever(asyncJobRepository.findById(eq(jobId))).thenReturn(Optional.empty())

        mockMvc.get("/api/jobs/$jobId")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.error") { value("NOT_FOUND") }
            }
    }
}
