package com.chessecho.service

import com.chessecho.domain.TrainingAttempt
import com.chessecho.domain.TrainingAttemptMode
import com.chessecho.domain.TrainingAttemptOutcome
import com.chessecho.dto.TrainingAttemptRequest
import com.chessecho.dto.TrainingAttemptResponse
import com.chessecho.repository.TrainingAttemptRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class TrainingAttemptService(
    private val trainingAttemptRepository: TrainingAttemptRepository,
) {
    fun submit(
        request: TrainingAttemptRequest,
        principal: AuthenticatedPrincipal?,
    ): TrainingAttemptResponse = submitAttempt(principal, request)

    fun submitAttempt(
        principal: AuthenticatedPrincipal?,
        request: TrainingAttemptRequest,
    ): TrainingAttemptResponse {
        // Validate non-negative durations
        if (request.elapsedMs < 0) {
            throw IllegalArgumentException("Elapsed time cannot be negative: ${request.elapsedMs}ms")
        }
        if (request.allowedMs != null && request.allowedMs < 0) {
            throw IllegalArgumentException(
                "Allowed time cannot be negative: ${request.allowedMs}ms",
            )
        }

        // Parse timing mode and outcome enums
        val mode =
            try {
                TrainingAttemptMode.valueOf(request.mode)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid timing mode: ${request.mode}")
            }

        val outcome =
            try {
                TrainingAttemptOutcome.valueOf(request.outcome)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid outcome: ${request.outcome}")
            }

        // Persist attempt with optional account ownership
        val attempt =
            TrainingAttempt(
                id = request.attemptId,
                puzzleId = request.puzzleId,
                mode = mode,
                elapsedMs = request.elapsedMs,
                allowedMs = request.allowedMs,
                outcome = outcome,
                chessAccount = null,
                createdAt = Instant.now(),
            )

        val saved = trainingAttemptRepository.save(attempt)

        return TrainingAttemptResponse(
            attemptId = saved.id,
            puzzleId = saved.puzzleId,
            mode = saved.mode.name,
            elapsedMs = saved.elapsedMs,
            allowedMs = saved.allowedMs,
            outcome = saved.outcome.name,
            recordedAt = saved.createdAt,
        )
    }
}
