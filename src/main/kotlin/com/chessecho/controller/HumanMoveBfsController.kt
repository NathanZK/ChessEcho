package com.chessecho.controller

import com.chessecho.dto.HumanMoveBfsRequest
import com.chessecho.dto.HumanMoveBfsResponse
import com.chessecho.service.HumanMoveBfsService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/human-move-distribution")
class HumanMoveBfsController(
    private val humanMoveBfsService: HumanMoveBfsService,
) {
    @PostMapping("/bfs")
    fun runBfs(
        @RequestBody request: HumanMoveBfsRequest,
    ): ResponseEntity<HumanMoveBfsResponse> {
        val response = humanMoveBfsService.runBfs(request)
        return ResponseEntity.ok(response)
    }
}
