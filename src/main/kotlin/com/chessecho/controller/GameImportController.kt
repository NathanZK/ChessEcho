package com.chessecho.controller

import com.chessecho.dto.ImportGamesRequest
import com.chessecho.dto.ImportJobResponse
import com.chessecho.dto.JobStatusResponse
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.service.GameImportService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class GameImportController(
    private val gameImportService: GameImportService,
    private val asyncJobRepository: AsyncJobRepository,
) {
    /**
     * Initiates an asynchronous game import job for the requested player and platform.
     * Returns 202 Accepted with the created job ID.
     */
    @PostMapping("/games/import")
    fun importGames(
        @Valid @RequestBody request: ImportGamesRequest,
    ): ResponseEntity<ImportJobResponse> {
        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(ImportJobResponse(jobId = job.id, status = job.status))
    }

    /**
     * Retrieves current status and metrics for an import job by ID.
     */
    @GetMapping("/jobs/{id}")
    fun getJobStatus(
        @PathVariable id: UUID,
    ): ResponseEntity<JobStatusResponse> {
        val job =
            asyncJobRepository.findById(id)
                .orElseThrow { NoSuchElementException("Job not found: $id") }
        return ResponseEntity.ok(
            JobStatusResponse(
                jobId = job.id,
                status = job.status,
                gamesImported = job.gamesImported,
                gamesSkipped = job.gamesSkipped,
                gamesProcessed = job.gamesProcessed,
                errorMessage = job.errorMessage,
                analysisStatus = job.analysisStatus,
            ),
        )
    }
}
