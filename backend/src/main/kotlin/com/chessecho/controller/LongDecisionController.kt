package com.chessecho.controller

import com.chessecho.domain.DecisionSeverity
import com.chessecho.domain.TimeControl
import com.chessecho.dto.LongDecisionPageResponse
import com.chessecho.service.LongDecisionService
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
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
        @RequestParam(required = false) minDurationSeconds: Int?,
        @RequestParam(required = false) thresholdSeconds: Int?,
        @RequestParam(required = false) severity: DecisionSeverity?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        principal: AuthenticatedPrincipal,
    ): LongDecisionPageResponse {
        if (minDurationSeconds == null && thresholdSeconds == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Either minDurationSeconds or thresholdSeconds is required",
            )
        }
        return longDecisionService.findLongDecisions(
            accountId = accountId,
            timeControl = timeControl,
            minDurationSeconds = minDurationSeconds,
            thresholdSeconds = thresholdSeconds,
            severity = severity,
            page = page,
            size = size,
            principal = principal,
        )
    }
}
