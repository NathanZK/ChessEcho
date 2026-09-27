package com.chessecho.controller

import com.chessecho.dto.ErrorResponse
import com.chessecho.dto.HumanMoveCorpusCheckpointRequest
import com.chessecho.dto.HumanMoveCorpusCheckpointResponse
import com.chessecho.dto.HumanMoveCorpusRunRequest
import com.chessecho.dto.HumanMoveCorpusRunResponse
import com.chessecho.service.HumanMoveCorpusCheckpointService
import com.chessecho.service.HumanMoveCorpusIntegrityException
import com.chessecho.service.HumanMoveCorpusService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Issue #423 run-scoped reference-corpus runs and non-destructive nested checkpoints. */
@RestController
@RequestMapping("/api/admin/human-move-distribution/corpus-runs")
class HumanMoveCorpusController(
    private val humanMoveCorpusService: HumanMoveCorpusService,
    private val humanMoveCorpusCheckpointService: HumanMoveCorpusCheckpointService,
) {
    @PostMapping
    fun runCorpus(
        @RequestBody request: HumanMoveCorpusRunRequest,
    ): ResponseEntity<HumanMoveCorpusRunResponse> = ResponseEntity.ok(humanMoveCorpusService.runCorpus(request))

    @GetMapping
    fun listRuns(): ResponseEntity<List<HumanMoveCorpusRunResponse>> = ResponseEntity.ok(humanMoveCorpusService.listRuns())

    @GetMapping("/{runId}")
    fun getRun(
        @PathVariable runId: UUID,
    ): ResponseEntity<HumanMoveCorpusRunResponse> = ResponseEntity.ok(humanMoveCorpusService.getRun(runId))

    @PostMapping("/{runId}/checkpoints")
    fun calculateCheckpoint(
        @PathVariable runId: UUID,
        @RequestBody request: HumanMoveCorpusCheckpointRequest,
    ): ResponseEntity<HumanMoveCorpusCheckpointResponse> = ResponseEntity.ok(humanMoveCorpusCheckpointService.calculate(runId, request))

    @ExceptionHandler(HumanMoveCorpusIntegrityException::class)
    fun handleIntegrityViolation(e: HumanMoveCorpusIntegrityException): ResponseEntity<ErrorResponse> =
        ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(error = "CORPUS_INTEGRITY_VIOLATION", details = listOf(e.message ?: "integrity violation")))
}
