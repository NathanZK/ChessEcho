package com.chessprobe.controller

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.Platform
import com.chessprobe.domain.SimilarityRetrievalMode
import com.chessprobe.dto.PrincipalVariationDto
import com.chessprobe.dto.SimilarPositionOccurrenceDto
import com.chessprobe.dto.SimilarPositionResult
import com.chessprobe.dto.SimilarPositionsResponse
import com.chessprobe.service.AccountOwnershipService
import com.chessprobe.service.AccountSelectionRequiredException
import com.chessprobe.service.auth.AuthenticatedPrincipal
import com.chessprobe.service.similarity.FenPositionValidator
import com.chessprobe.service.similarity.PositionSimilarityService
import com.chessprobe.service.similarity.SimilarPositionsResult
import com.chessprobe.web.OptionalAuthenticatedPrincipal
import com.chessprobe.web.SessionAuthenticationFilter
import com.chessprobe.web.UnauthenticatedException
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
        @RequestParam(defaultValue = "8") depth: Int,
        @RequestParam(defaultValue = "1") multiPv: Int,
        @RequestParam(defaultValue = "STRUCTURAL") mode: String,
        @OptionalAuthenticatedPrincipal
        @RequestAttribute(name = SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE, required = false)
        principal: AuthenticatedPrincipal?,
    ): SimilarPositionsResponse {
        val parsedMode = parseRetrievalMode(mode)
        if (minScore != null && minScore < 0.0) {
            throw IllegalArgumentException("minScore must be non-negative: $minScore")
        }
        if (depth !in 4..12) {
            throw IllegalArgumentException("depth must be between 4 and 12: $depth")
        }
        if (multiPv !in 1..5) {
            throw IllegalArgumentException("multiPv must be between 1 and 5: $multiPv")
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
                depth = depth,
                multiPv = multiPv,
                mode = parsedMode,
            )

        return toResponse(result)
    }

    private fun parseRetrievalMode(mode: String): SimilarityRetrievalMode =
        try {
            SimilarityRetrievalMode.valueOf(mode.trim().uppercase())
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid retrieval mode: $mode")
        }

    private fun resolveAccount(
        accountId: UUID?,
        username: String?,
        principal: AuthenticatedPrincipal?,
    ): ChessAccount =
        if (principal != null) {
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

    private fun toResponse(result: SimilarPositionsResult): SimilarPositionsResponse =
        SimilarPositionsResponse(
            queryFen = result.queryFen,
            candidatesEvaluated = result.candidatesEvaluated,
            shortlistedCandidatesCount = result.shortlistedCandidatesCount,
            engineCompletedCount = result.engineCompletedCount,
            dynamicSimilarityApplied = result.dynamicSimilarityApplied,
            fallbackReason = result.fallbackReason,
            queryStatus = result.queryStatus?.name,
            queryAchievedDepth = result.queryAchievedDepth,
            queryPrincipalVariations =
                result.queryPrincipalVariations.map { pv ->
                    PrincipalVariationDto(
                        rank = pv.rank,
                        moves = pv.moves,
                        achievedDepth = pv.achievedDepth,
                    )
                },
            results =
                result.results.map { match ->
                    SimilarPositionResult(
                        rank = match.rank,
                        score = match.score,
                        staticScore = match.staticScore,
                        dynamicScore = match.dynamicScore,
                        positionId = match.positionId,
                        fen = match.fen,
                        canonicalFen = match.canonicalFen,
                        searchStatus = match.searchStatus?.name,
                        achievedDepth = match.achievedDepth,
                        principalVariations =
                            match.principalVariations.map { pv ->
                                PrincipalVariationDto(
                                    rank = pv.rank,
                                    moves = pv.moves,
                                    achievedDepth = pv.achievedDepth,
                                )
                            },
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
            mode = result.mode,
        )
}
