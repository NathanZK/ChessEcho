package com.chessecho.controller

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Platform
import com.chessecho.dto.SimilarPositionOccurrenceDto
import com.chessecho.dto.SimilarPositionResult
import com.chessecho.dto.SimilarPositionsResponse
import com.chessecho.service.AccountOwnershipService
import com.chessecho.service.AccountSelectionRequiredException
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.service.similarity.FenPositionValidator
import com.chessecho.service.similarity.PositionSimilarityService
import com.chessecho.service.similarity.SimilarPositionsResult
import com.chessecho.web.OptionalAuthenticatedPrincipal
import com.chessecho.web.SessionAuthenticationFilter
import com.chessecho.web.UnauthenticatedException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/positions/similar")
class PositionSimilarityController(
    private val positionSimilarityService: PositionSimilarityService,
    private val accountOwnershipService: AccountOwnershipService,
    private val fenPositionValidator: FenPositionValidator,
) {
    @GetMapping
    fun getSimilarPositions(
        @RequestParam fen: String,
        @RequestParam(required = false) accountId: UUID?,
        @RequestParam(required = false) username: String?,
        @RequestParam(required = false) minScore: Double?,
        @RequestParam(defaultValue = "20") limit: Int,
        @OptionalAuthenticatedPrincipal
        @RequestAttribute(name = SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, required = false)
        principal: AuthenticatedPrincipal?,
    ): SimilarPositionsResponse {
        if (minScore != null && minScore < 0.0) {
            throw IllegalArgumentException("minScore must be non-negative: $minScore")
        }
        val validatedFen = fenPositionValidator.validateOrthodoxFen(fen)
        val resolvedLimit = limit.coerceIn(1, 100)
        val account = resolveAccount(accountId, username, principal)

        val result =
            positionSimilarityService.findSimilarPositions(
                queryFen = validatedFen,
                account = account,
                minScore = minScore ?: 0.0,
                limit = resolvedLimit,
            )

        return toResponse(result)
    }

    private fun resolveAccount(
        accountId: UUID?,
        username: String?,
        principal: AuthenticatedPrincipal?,
    ): ChessAccount {
        return if (principal != null) {
            if (!username.isNullOrBlank()) {
                throw AccountSelectionRequiredException("An accountId is required for authenticated private data")
            }
            if (accountId == null) {
                throw IllegalArgumentException("Either accountId or username must be provided")
            }
            accountOwnershipService.resolvePrivateRead(accountId, principal)
        } else {
            if (accountId != null) {
                throw UnauthenticatedException("Authentication required to access account by accountId")
            }
            if (username.isNullOrBlank()) {
                throw IllegalArgumentException("Either accountId or username must be provided")
            }
            accountOwnershipService.resolvePrivateRead(Platform.CHESS_COM, username, null)
        }
    }

    private fun toResponse(result: SimilarPositionsResult): SimilarPositionsResponse =
        SimilarPositionsResponse(
            queryFen = result.queryFen,
            candidatesEvaluated = result.candidatesEvaluated,
            results =
                result.results.map { match ->
                    SimilarPositionResult(
                        rank = match.rank,
                        score = match.score,
                        positionId = match.positionId,
                        fen = match.fen,
                        canonicalFen = match.canonicalFen,
                        occurrences =
                            match.occurrences.map { occ ->
                                SimilarPositionOccurrenceDto(
                                    id = occ.id,
                                    platformGameId = occ.platformGameId,
                                    playedMove = occ.playedMove,
                                    plyNumber = occ.plyNumber,
                                    playerColor = occ.playerColor,
                                    playedAt = occ.playedAt,
                                    opponentUsername = occ.opponentUsername,
                                    timeControl = occ.timeControl,
                                    gameResult = occ.gameResult,
                                    chessComUrl = occ.chessComUrl,
                                )
                            },
                    )
                },
        )
}
