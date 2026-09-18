package com.chessecho.controller

import com.chessecho.service.TrainingAttemptService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class PuzzleControllerTest {
    private val trainingAttemptService: TrainingAttemptService = mock()

    @Test
    fun `training attempt endpoint dependency is available`() {
        PuzzleController(trainingAttemptService = trainingAttemptService)
    }
}
