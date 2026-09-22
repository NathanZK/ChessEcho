package com.chessecho.controller

import com.chessecho.domain.ArchiveDerivedStatus
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.dto.ImportJobResponse
import com.chessecho.dto.JobStatusResponse
import com.chessecho.repository.ArchiveDerivedProcessingRepository
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
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

@RestController
@RequestMapping("/api")
class GameImportController(
    private val gameImportService: GameImportService,
    private val asyncJobRepository: AsyncJobRepository,
    private val accountOwnershipService: AccountOwnershipService? = null,
    private val archiveDerivedProcessingRepository: ArchiveDerivedProcessingRepository? = null,
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
                multiPv = job.analysisMultiPv,
                configurationState = job.configurationState.takeIf { job.chessAccount != null },
                derivedStatus = derivedStatusFor(job),
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
            multiPv = analysisMultiPv,
            configurationState = configurationState.takeIf { chessAccount != null },
            derivedStatus = derivedStatusFor(this),
        )

    /**
     * Derived-processing status for **this job's requested archive range only**.
     *
     * Scoping rules, all of which are deliberate:
     *  - Only archives of the job's own account are considered, and only those
     *    whose `yearMonth` falls inside the job's requested `[fromDate, toDate]`
     *    range. An unrelated archive from an earlier, differently scoped import
     *    is never reported here. An absent bound means the job really did request
     *    an unbounded side of the range, so archives on that side are in scope.
     *  - The upper bound is additionally clamped below the current month. The
     *    current month is intentionally not recorded as an `imported_archive`,
     *    because it is still growing, so it has no durable derived-processing
     *    unit to report. A current-month-only import therefore reports no derived
     *    status; its derived outcome is carried by the job's own status.
     *  - A job that has not started yet cannot describe derived work, so it
     *    reports nothing rather than echoing a previous job's archives.
     *  - No in-scope archive (nothing imported yet, or a current-month-only
     *    range) reports nothing rather than inventing a status.
     *
     * When several in-scope archives disagree, the least-complete status wins, so
     * the field never claims completion while a scoped archive is unfinished.
     */
    private fun derivedStatusFor(job: com.chessecho.domain.AsyncJob): String? {
        val accountId = job.chessAccount?.id ?: return null
        val repository = archiveDerivedProcessingRepository ?: return null
        if (job.status == "QUEUED") return null

        val currentMonth = YearMonth.now(ZoneOffset.UTC).toString()
        val fromMonth = job.fromDate
        val toMonth = job.toDate
        val scopedStatuses =
            repository.findDerivedStatusesByChessAccountId(accountId)
                .filter { view ->
                    view.yearMonth < currentMonth &&
                        (fromMonth == null || view.yearMonth >= fromMonth) &&
                        (toMonth == null || view.yearMonth <= toMonth)
                }
                .map { it.status }
        return when {
            scopedStatuses.isEmpty() -> null
            ArchiveDerivedStatus.FAILED in scopedStatuses -> ArchiveDerivedStatus.FAILED.name
            ArchiveDerivedStatus.PROCESSING in scopedStatuses -> ArchiveDerivedStatus.PROCESSING.name
            ArchiveDerivedStatus.PENDING in scopedStatuses -> ArchiveDerivedStatus.PENDING.name
            else -> ArchiveDerivedStatus.COMPLETED.name
        }
    }
}
