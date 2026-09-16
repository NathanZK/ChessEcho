package com.chessecho.controller

import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.dto.WeaknessResponse
import com.chessecho.service.AccountOwnershipService
import com.chessecho.service.WeaknessCalculationService
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.web.OptionalAuthenticatedPrincipal
import com.chessecho.web.SessionAuthenticationFilter
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class WeaknessController(
    private val weaknessCalculationService: WeaknessCalculationService,
) {
    @Autowired(required = false)
    private var accountOwnershipService: AccountOwnershipService? = null

    @GetMapping("/positions/weaknesses")
    fun getWeaknesses(
        @RequestParam(required = false) platform: Platform?,
        @RequestParam(required = false) username: String?,
        @RequestParam(required = false) accountId: java.util.UUID?,
        @RequestParam playerColor: PlayerColor,
        @Parameter(
            description =
                "Minimum engine evaluation loss, in pawns, required for a move to be classified as a mistake. " +
                    "A lower value means a stricter definition of a mistake.",
            example = "0.8",
        )
        @RequestParam(required = false) minEvalLoss: Double?,
        @RequestParam(defaultValue = "3") minMistakeCount: Int,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @OptionalAuthenticatedPrincipal
        @RequestAttribute(name = SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, required = false)
        principal: AuthenticatedPrincipal?,
    ): ResponseEntity<List<WeaknessResponse>> {
        val threshold = minEvalLoss ?: WeaknessCalculationService.DEFAULT_MIN_EVAL_LOSS
        if (threshold < 0.0) {
            throw IllegalArgumentException("minEvalLoss must be non-negative")
        }

        val weaknesses =
            weaknessCalculationService.getWeaknesses(
                platform = platform ?: Platform.CHESS_COM,
                username = username.orEmpty(),
                playerColor = playerColor,
                minEvalLoss = threshold,
                minMistakeCount = maxOf(1, minMistakeCount),
                principal = principal.takeIf { accountOwnershipService != null },
                accountId = accountId,
            )

        val pageSize = maxOf(1, size)
        val pageIndex = maxOf(0, page)
        val pagedWeaknesses =
            weaknesses
                .drop(pageIndex * pageSize)
                .take(pageSize)

        return ResponseEntity.ok(pagedWeaknesses)
    }
}
