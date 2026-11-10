package com.chessprobe.service

import com.chessprobe.domain.TrainingAttempt
import com.chessprobe.domain.TrainingAttemptMode
import com.chessprobe.domain.TrainingAttemptOutcome
import com.chessprobe.dto.TrainingAttemptRequest
import com.chessprobe.dto.TrainingAttemptResponse
import com.chessprobe.repository.AppUserRepository
import com.chessprobe.repository.TrainingAttemptRepository
import com.chessprobe.service.auth.AuthenticatedPrincipal
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class TrainingAttemptService(
    private val trainingAttemptRepository: TrainingAttemptRepository,
    private val accountOwnershipService: AccountOwnershipService,
    private val appUserRepository: AppUserRepository,
) {
    fun submit(
        request: TrainingAttemptRequest,
        principal: AuthenticatedPrincipal?,
    ): TrainingAttemptResponse = submitAttempt(principal, request)

    fun submitAttempt(
        principal: AuthenticatedPrincipal?,
        request: TrainingAttemptRequest,
    ): TrainingAttemptResponse {
        val chessAccount =
            when {
                request.accountId != null -> accountOwnershipService.resolveSharedAccount(request.accountId, principal)
                principal != null -> throw AccountSelectionRequiredException()
                else -> null
            }

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
                throw IllegalArgumentException("Invalid timing mode: ${request.mode}", e)
            }

        val outcome =
            try {
                TrainingAttemptOutcome.valueOf(request.outcome)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid outcome: ${request.outcome}", e)
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
                chessAccount = chessAccount,
                appUser = principal?.let { appUserRepository.getReferenceById(it.appUserId) },
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
