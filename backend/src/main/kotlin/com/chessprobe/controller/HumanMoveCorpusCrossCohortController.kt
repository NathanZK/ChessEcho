package com.chessprobe.controller

import com.chessprobe.service.HumanMoveCorpusCrossCohortCompareRequest
import com.chessprobe.service.HumanMoveCorpusCrossCohortCompareResponse
import com.chessprobe.service.HumanMoveCorpusCrossCohortService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Issue #426 read-only, directional cross-cohort weakness overlap. */
@RestController
@RequestMapping("/api/admin/human-move-distribution/corpus-projections")
class HumanMoveCorpusCrossCohortController(
    private val crossCohortService: HumanMoveCorpusCrossCohortService,
) {
    @PostMapping("/compare")
    fun compare(
        @RequestBody request: HumanMoveCorpusCrossCohortCompareRequest,
    ): ResponseEntity<HumanMoveCorpusCrossCohortCompareResponse> = ResponseEntity.ok(crossCohortService.compare(request))
}
