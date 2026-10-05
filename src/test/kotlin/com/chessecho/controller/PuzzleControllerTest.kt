package com.chessecho.controller

import com.chessecho.dto.TrainingAttemptRequest
import com.chessecho.dto.TrainingAttemptResponse
import com.chessecho.service.TrainingAttemptService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

class PuzzleControllerTest {
    private val trainingAttemptService: TrainingAttemptService = mock()
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val mockMvc =
        MockMvcBuilders
            .standaloneSetup(PuzzleController(trainingAttemptService = trainingAttemptService))
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()

    @ParameterizedTest
    @ValueSource(strings = ["STOPWATCH", "COUNTDOWN"])
    fun `valid UUID JSON reaches the training service`(mode: String) {
        val request =
            TrainingAttemptRequest(
                attemptId = UUID.randomUUID(),
                puzzleId = "puzzle-1",
                mode = mode,
                elapsedMs = 1_250,
                allowedMs = if (mode == "COUNTDOWN") 5_000 else null,
                outcome = "SUBMITTED",
            )
        whenever(trainingAttemptService.submitAttempt(null, request)).thenReturn(
            TrainingAttemptResponse(
                attemptId = request.attemptId,
                puzzleId = request.puzzleId,
                mode = request.mode,
                elapsedMs = request.elapsedMs,
                allowedMs = request.allowedMs,
                outcome = request.outcome,
                recordedAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )

        mockMvc
            .perform(
                post("/api/puzzles/attempt")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isOk)

        verify(trainingAttemptService).submitAttempt(null, request)
    }

    @Test
    fun `legacy attempt identifier is rejected before the training service runs`() {
        mockMvc
            .perform(
                post("/api/puzzles/attempt")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "attemptId": "attempt-puzzle-1-123-random",
                          "puzzleId": "puzzle-1",
                          "mode": "STOPWATCH",
                          "elapsedMs": 1250,
                          "outcome": "SUBMITTED"
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))

        verifyNoInteractions(trainingAttemptService)
    }
}
