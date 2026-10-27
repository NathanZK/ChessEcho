package com.chessecho.controller

import com.chessecho.service.HumanMoveCorpusMaterializationService
import com.chessecho.service.HumanMoveCorpusMaterializeRequest
import com.chessecho.service.HumanMoveCorpusMaterializeResponse
import com.chessecho.service.HumanMoveCorpusProjectionFinalizationService
import com.chessecho.service.HumanMoveCorpusProjectionFinalizeResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Issue #426 corpus-aware projection materialization and scoped finalization. */
@RestController
@RequestMapping("/api/admin/human-move-distribution/corpus-projections")
class HumanMoveCorpusProjectionController(
    private val materializationService: HumanMoveCorpusMaterializationService,
    private val finalizationService: HumanMoveCorpusProjectionFinalizationService,
) {
    @PostMapping("/materialize")
    fun materialize(
        @RequestBody request: HumanMoveCorpusMaterializeRequest,
    ): ResponseEntity<HumanMoveCorpusMaterializeResponse> = ResponseEntity.ok(materializationService.materialize(request))

    @PostMapping("/{projectionId}/finalize")
    fun finalize(
        @PathVariable projectionId: UUID,
    ): ResponseEntity<HumanMoveCorpusProjectionFinalizeResponse> = ResponseEntity.ok(finalizationService.finalize(projectionId))
}
