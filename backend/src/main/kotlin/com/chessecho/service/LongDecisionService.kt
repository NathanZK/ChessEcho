package com.chessecho.service

import com.chessecho.domain.DecisionSeverity
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.TimeControl
import com.chessecho.dto.LongDecisionOccurrenceResponse
import com.chessecho.dto.LongDecisionPageResponse
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.service.similarity.ChessComUrlBuilder
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class LongDecisionService(
    private val accountOwnershipService: AccountOwnershipService,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val chessComUrlBuilder: ChessComUrlBuilder,
    private val decisionTimingPolicy: DecisionTimingPolicy,
) {
    @Transactional(readOnly = true)
    fun findLongDecisions(
        accountId: UUID,
        timeControl: TimeControl,
        thresholdSeconds: Int,
        page: Int,
        size: Int,
        principal: AuthenticatedPrincipal,
    ): LongDecisionPageResponse =
        findLongDecisions(
            accountId = accountId,
            timeControl = timeControl,
            minDurationSeconds = null,
            thresholdSeconds = thresholdSeconds,
            severity = null,
            page = page,
            size = size,
            principal = principal,
        )

    @Transactional(readOnly = true)
    fun findLongDecisions(
        accountId: UUID,
        timeControl: TimeControl,
        minDurationSeconds: Int?,
        thresholdSeconds: Int?,
        severity: DecisionSeverity?,
        page: Int,
        size: Int,
        principal: AuthenticatedPrincipal,
    ): LongDecisionPageResponse {
        validateInputs(minDurationSeconds, thresholdSeconds, page)

        val effectiveSeconds = minDurationSeconds ?: thresholdSeconds ?: 0
        val pageSize = size.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        val account = accountOwnershipService.resolveSharedAccount(accountId, principal)

        val bounds =
            decisionTimingPolicy.calculateEffectiveBounds(
                userMinDurationSeconds = effectiveSeconds,
                severity = severity,
                timeControl = timeControl,
            )

        if (bounds.isEmpty) {
            return LongDecisionPageResponse(
                content = emptyList(),
                page = page,
                size = pageSize,
                totalElements = 0,
                totalPages = 0,
                hasNext = false,
            )
        }

        val results =
            positionOccurrenceRepository.findLongDecisionOccurrences(
                chessAccountId = account.id,
                timeControl = timeControl.name,
                effectiveMinMs = bounds.minMs,
                effectiveMaxMs = bounds.maxMs,
                pageable = PageRequest.of(page, pageSize),
            )

        return LongDecisionPageResponse(
            content = results.content.map { toOccurrenceResponse(it, timeControl) },
            page = results.number,
            size = results.size,
            totalElements = results.totalElements,
            totalPages = results.totalPages,
            hasNext = results.hasNext(),
        )
    }

    private fun validateInputs(
        minDurationSeconds: Int?,
        thresholdSeconds: Int?,
        page: Int,
    ) {
        minDurationSeconds?.let {
            require(it >= 0) { "minDurationSeconds must be non-negative" }
        }
        if (minDurationSeconds == null && thresholdSeconds != null) {
            require(thresholdSeconds > 0) { "thresholdSeconds must be positive" }
        }
        require(page >= 0) { "page must be non-negative" }
    }

    private fun toOccurrenceResponse(
        occurrence: PositionOccurrence,
        timeControl: TimeControl,
    ): LongDecisionOccurrenceResponse {
        val game = occurrence.game
        val decisionTimeMs = requireNotNull(occurrence.decisionTimeMs)
        return LongDecisionOccurrenceResponse(
            id = occurrence.id,
            positionId = occurrence.position.id,
            fen = occurrence.position.fen,
            playerColor = occurrence.playerColor,
            plyNumber = occurrence.plyNumber,
            movePlayed = occurrence.movePlayed,
            decisionTimeMs = decisionTimeMs,
            timeControl = timeControl.name,
            platformGameId = game.platformGameId,
            playedAt = game.playedAt,
            opponentUsername =
                when (occurrence.playerColor) {
                    "WHITE" -> game.blackUsername
                    "BLACK" -> game.whiteUsername
                    else -> null
                },
            chessComUrl =
                chessComUrlBuilder.buildExactPlyUrl(
                    platformGameId = game.platformGameId,
                    plyNumber = occurrence.plyNumber,
                    timeControl = game.timeControl,
                ),
            severity = decisionTimingPolicy.classify(timeControl, decisionTimeMs),
        )
    }

    companion object {
        private const val MIN_PAGE_SIZE = 1
        private const val MAX_PAGE_SIZE = 100
    }
}
