package com.chessecho.controller

import com.chessecho.dto.ErrorResponse
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.dto.ImportJobResponse
import com.chessecho.dto.JobStatusResponse
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.service.ActiveImportJobException
import com.chessecho.service.GameImportService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
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
    @PostMapping("/games/import")
    fun importGames(
        @Valid @RequestBody request: ImportGamesRequest,
    ): ResponseEntity<ImportJobResponse> {
        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job, request)
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(ImportJobResponse(jobId = job.id, status = job.status))
    }

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
                errorMessage = job.errorMessage,
            ),
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationErrors(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val details = ex.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity
            .badRequest()
            .body(ErrorResponse(error = "VALIDATION_ERROR", details = details))
    }

    @ExceptionHandler(ActiveImportJobException::class)
    fun handleActiveJobConflict(ex: ActiveImportJobException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(error = "CONFLICT", details = listOf(ex.message ?: "Active job exists")))

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(error = "NOT_FOUND", details = listOf(ex.message ?: "Resource not found")))
}
