package com.chessecho.controller

import com.chessecho.service.HumanMoveCorpusImportReceipt
import com.chessecho.service.HumanMoveCorpusImportService
import com.chessecho.service.HumanMoveCorpusPurgeResult
import com.chessecho.service.HumanMoveCorpusPurgeService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

data class HumanMoveCorpusPurgeRequest(val contentDigest: String)

/** Issue #426 verified, transactional import publication and gated purge/reconstruction. */
@RestController
@RequestMapping("/api/admin/human-move-distribution/corpus-artifacts")
class HumanMoveCorpusImportController(
    private val importService: HumanMoveCorpusImportService,
    private val purgeService: HumanMoveCorpusPurgeService,
) {
    @PostMapping("/import")
    fun import(
        @RequestPart("artifact") artifact: MultipartFile,
        @RequestParam expectedDigest: String,
    ): ResponseEntity<HumanMoveCorpusImportReceipt> =
        artifact.inputStream.use { input -> ResponseEntity.ok(importService.import(input, expectedDigest)) }

    @PostMapping("/{runId}/purge")
    fun purge(
        @PathVariable runId: UUID,
        @RequestBody request: HumanMoveCorpusPurgeRequest,
    ): ResponseEntity<HumanMoveCorpusPurgeResult> = ResponseEntity.ok(purgeService.purge(runId, request.contentDigest))
}
