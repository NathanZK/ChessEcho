package com.chessecho.controller

import com.chessecho.dto.ImportGamesRequest
import com.chessecho.dto.ImportJobResponse
import com.chessecho.dto.JobStatusResponse
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.service.AccountOwnershipService
import com.chessecho.service.GameImportService
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.web.OptionalAuthenticatedPrincipal
import com.chessecho.web.SessionAuthenticationFilter
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class GameImportController(
    private val gameImportService: GameImportService,
    private val asyncJobRepository: AsyncJobRepository,
    private val accountOwnershipService: AccountOwnershipService? = null,
) {
    /**
     * Initiates an asynchronous game import job for the requested player and platform.
     * Returns 202 Accepted with the created job ID.
     */
    @PostMapping("/games/import")
    fun importGames(
        @OptionalAuthenticatedPrincipal
        @RequestAttribute(name = SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, required = false)
        principal: AuthenticatedPrincipal?,
        @Valid @RequestBody request: ImportGamesRequest,
    ): ResponseEntity<ImportJobResponse> {
        val job =
            if (principal == null) {
                gameImportService.createImportJob(request)
            } else {
                gameImportService.createImportJob(request, principal)
            } ?: when {
                request.isAuthenticatedForm() && request.normalizedUsername() != null ->
                    throw com.chessecho.service.AccountSelectionMismatchException()
                accountOwnershipService == null ->
                    throw com.chessecho.service.AccountSelectionRequiredException()
                else -> throw com.chessecho.service.AccountNotFoundException("Import job could not be created")
            }
        gameImportService.executeImportJob(job.id)
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(job.toImportJobResponse())
    }

    /**
     * Retrieves current status and metrics for an import job by ID.
     */
    @GetMapping("/jobs/{id}")
    fun getJobStatus(
        @PathVariable id: UUID,
        @OptionalAuthenticatedPrincipal
        @RequestAttribute(name = SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, required = false)
        principal: AuthenticatedPrincipal?,
    ): ResponseEntity<JobStatusResponse> {
        val job =
            asyncJobRepository.findByIdWithAccount(id)
                ?: asyncJobRepository.findById(id).orElseThrow { NoSuchElementException("Job not found: $id") }
        if (accountOwnershipService != null) {
            try {
                job.validateConfiguration()
            } catch (_: IllegalArgumentException) {
                throw com.chessecho.service.AccountNotFoundException("Import job is not resolvable")
            }
            if (!job.isReady()) {
                throw com.chessecho.service.AccountNotFoundException("Import job is unresolved")
            }
            accountOwnershipService.authorizeJob(job.chessAccount, principal)
        }
        return ResponseEntity.ok(
            JobStatusResponse(
                jobId = job.id,
                status = job.status,
                gamesImported = job.gamesImported,
                gamesSkipped = job.gamesSkipped,
                gamesProcessed = job.gamesProcessed,
                errorMessage = job.errorMessage,
                analysisStatus = job.analysisStatus,
                accountId = job.chessAccount?.id,
                platform = job.chessAccount?.platform,
                username = job.chessAccount?.username,
                fromDate = job.fromDate,
                toDate = job.toDate,
                timeControls = job.timeControlsCsv?.split(','),
                playerColor = job.playerColor,
                configurationState = job.configurationState.takeIf { job.chessAccount != null },
            ),
        )
    }

    private fun com.chessecho.domain.AsyncJob.toImportJobResponse(): ImportJobResponse =
        ImportJobResponse(
            jobId = id,
            status = status,
            accountId = chessAccount?.id,
            platform = chessAccount?.platform,
            username = chessAccount?.username,
            fromDate = fromDate,
            toDate = toDate,
            timeControls = timeControlsCsv?.split(','),
            playerColor = playerColor,
            configurationState = configurationState.takeIf { chessAccount != null },
        )
}
