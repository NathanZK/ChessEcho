package com.chessprobe.controller

import com.chessprobe.dto.HumanMoveBfsRequest
import com.chessprobe.dto.HumanMoveBfsResponse
import com.chessprobe.dto.HumanMoveFinalizeRequest
import com.chessprobe.dto.HumanMoveFinalizeResponse
import com.chessprobe.dto.HumanMovePopulationDiscoveryRequest
import com.chessprobe.dto.HumanMovePopulationDiscoveryResponse
import com.chessprobe.service.HumanMoveBfsService
import com.chessprobe.service.HumanMoveDistributionFinalizationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/human-move-distribution")
class HumanMoveBfsController(
    private val humanMoveBfsService: HumanMoveBfsService,
    private val humanMoveDistributionFinalizationService: HumanMoveDistributionFinalizationService,
) {
    @PostMapping("/bfs")
    fun runBfs(
        @RequestBody request: HumanMoveBfsRequest,
    ): ResponseEntity<HumanMoveBfsResponse> {
        val response = humanMoveBfsService.runBfs(request)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/population/discover")
    fun discoverPopulation(
        @RequestBody request: HumanMovePopulationDiscoveryRequest,
    ): ResponseEntity<HumanMovePopulationDiscoveryResponse> = ResponseEntity.ok(humanMoveBfsService.discoverPopulation(request))

    @PostMapping("/finalize")
    fun finalize(
        @RequestBody request: HumanMoveFinalizeRequest,
    ): ResponseEntity<HumanMoveFinalizeResponse> {
        val response = humanMoveDistributionFinalizationService.finalize(request)
        return ResponseEntity.ok(response)
    }
}
