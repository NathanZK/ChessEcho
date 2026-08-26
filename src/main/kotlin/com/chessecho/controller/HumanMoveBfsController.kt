package com.chessecho.controller

import com.chessecho.dto.HumanMoveBfsRequest
import com.chessecho.dto.HumanMoveBfsResponse
import com.chessecho.dto.HumanMoveFinalizeRequest
import com.chessecho.dto.HumanMoveFinalizeResponse
import com.chessecho.service.HumanMoveBfsService
import com.chessecho.service.HumanMoveDistributionFinalizationService
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

    @PostMapping("/finalize")
    fun finalize(
        @RequestBody request: HumanMoveFinalizeRequest,
    ): ResponseEntity<HumanMoveFinalizeResponse> {
        val response = humanMoveDistributionFinalizationService.finalize(request)
        return ResponseEntity.ok(response)
    }
}
