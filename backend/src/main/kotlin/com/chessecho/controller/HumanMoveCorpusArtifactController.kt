package com.chessecho.controller

import com.chessecho.dto.ErrorResponse
import com.chessecho.humanmove.artifact.CorpusArtifactConflict
import com.chessecho.humanmove.artifact.CorpusArtifactInvalidException
import com.chessecho.humanmove.artifact.HumanMoveCorpusArtifactService
import com.chessecho.humanmove.artifact.HumanMoveCorpusExportReceipt
import com.chessecho.humanmove.artifact.HumanMoveCorpusExportRequest
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.io.InputStreamResource
import org.springframework.core.io.Resource
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MultipartFile

/**
 * Issue #426 versioned corpus artifact export, download, and read-only
 * verification. Verification never exposes a mutation path; publication of a
 * verified artifact happens only through [HumanMoveCorpusImportController].
 */
@RestController
@RequestMapping("/api/admin/human-move-distribution/corpus-artifacts")
class HumanMoveCorpusArtifactController(
    private val artifactService: HumanMoveCorpusArtifactService,
) {
    @PostMapping("/export")
    fun export(
        @RequestBody request: HumanMoveCorpusExportRequest,
    ): ResponseEntity<HumanMoveCorpusExportReceipt> = ResponseEntity.ok(artifactService.export(request))

    @GetMapping("/{contentDigest}")
    fun download(
        @PathVariable contentDigest: String,
    ): ResponseEntity<Resource> {
        val path =
            artifactService.verifiedArchivePath(contentDigest)
                ?: throw NoSuchElementException("Artifact $contentDigest not found")
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(java.nio.file.Files.size(path))
            .body(InputStreamResource(java.nio.file.Files.newInputStream(path)))
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @RestControllerAdvice(
        assignableTypes = [
            HumanMoveCorpusArtifactController::class,
            HumanMoveCorpusImportController::class,
            HumanMoveCorpusProjectionController::class,
            HumanMoveCorpusCrossCohortController::class,
        ],
    )
    class HumanMoveCorpusArtifactExceptionHandler {
        private val logger = LoggerFactory.getLogger(javaClass)

        @ExceptionHandler(CorpusArtifactInvalidException::class)
        fun invalid(ex: CorpusArtifactInvalidException): ResponseEntity<ErrorResponse> =
            ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse(error = "CORPUS_ARTIFACT_INVALID", details = listOf(ex.message ?: "Invalid corpus artifact")))

        @ExceptionHandler(CorpusArtifactConflict::class)
        fun conflict(ex: CorpusArtifactConflict): ResponseEntity<ErrorResponse> =
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse(error = "CORPUS_ARTIFACT_CONFLICT", details = listOf(ex.message ?: "Corpus artifact conflict")))

        @ExceptionHandler(DataAccessException::class)
        fun persistence(ex: DataAccessException): ResponseEntity<ErrorResponse> {
            logger.error("Corpus lifecycle persistence failed", ex)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse(error = "INTERNAL_ERROR", details = listOf("Corpus lifecycle persistence failed")))
        }
    }

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.OK)
    fun verify(
        @RequestPart("artifact") artifact: MultipartFile,
        @RequestParam expectedDigest: String,
    ) {
        if (artifact.size > artifactService.maxArchiveBytes()) {
            throw CorpusArtifactInvalidException("Archive exceeds the configured maximum of ${artifactService.maxArchiveBytes()} bytes")
        }
        artifact.inputStream.use { input -> artifactService.verify(input, expectedDigest) }
    }
}
