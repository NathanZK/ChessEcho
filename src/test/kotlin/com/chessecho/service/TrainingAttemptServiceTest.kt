package com.chessecho.service

import com.chessecho.domain.TrainingAttempt
import com.chessecho.dto.TrainingAttemptRequest
import com.chessecho.repository.TrainingAttemptRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class TrainingAttemptServiceTest {
    private val repository: TrainingAttemptRepository = mock()
    private val service = TrainingAttemptService(repository)

    @Test
    fun `rejects negative elapsed duration`() {
        val request =
            TrainingAttemptRequest(
                attemptId = UUID.randomUUID(),
                puzzleId = "puzzle-1",
                mode = "STOPWATCH",
                elapsedMs = -1,
                allowedMs = null,
                outcome = "SUBMITTED",
            )

        assertThrows(IllegalArgumentException::class.java) {
            service.submit(request, null)
        }
    }

    @Test
    fun `persists a valid submitted attempt`() {
        val attemptId = UUID.randomUUID()
        val request =
            TrainingAttemptRequest(
                attemptId = attemptId,
                puzzleId = "puzzle-1",
                mode = "STOPWATCH",
                elapsedMs = 2_500,
                allowedMs = null,
                outcome = "SUBMITTED",
            )
        whenever(repository.existsById(attemptId)).thenReturn(false)
        whenever(repository.save(org.mockito.kotlin.any<TrainingAttempt>()))
            .thenAnswer { it.arguments[0] as TrainingAttempt }

        val response = service.submit(request, null)

        assertEquals(attemptId, response.attemptId)
        assertEquals(2_500, response.elapsedMs)
        assertEquals("SUBMITTED", response.outcome)
    }
}
