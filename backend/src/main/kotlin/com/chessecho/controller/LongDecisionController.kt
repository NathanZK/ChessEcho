package com.chessecho.controller

import com.chessecho.domain.TimeControl
import com.chessecho.dto.LongDecisionPageResponse
import com.chessecho.service.LongDecisionService
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/positions")
class LongDecisionController(
    private val longDecisionService: LongDecisionService,
) {
    @GetMapping("/long-decisions")
    fun getLongDecisions(
        @RequestParam accountId: UUID,
        @RequestParam timeControl: TimeControl,
        @RequestParam thresholdSeconds: Int,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        principal: AuthenticatedPrincipal,
    ): LongDecisionPageResponse =
        longDecisionService.findLongDecisions(
            accountId = accountId,
            timeControl = timeControl,
            thresholdSeconds = thresholdSeconds,
            page = page,
            size = size,
            principal = principal,
        )
}
