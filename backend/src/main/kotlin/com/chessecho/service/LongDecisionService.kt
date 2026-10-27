package com.chessecho.service

import com.chessecho.domain.TimeControl
import com.chessecho.dto.LongDecisionOccurrenceResponse
import com.chessecho.dto.LongDecisionPageResponse
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class LongDecisionService(
    private val accountOwnershipService: AccountOwnershipService,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
) {
    @Transactional(readOnly = true)
    fun findLongDecisions(
        accountId: UUID,
        timeControl: TimeControl,
        thresholdSeconds: Int,
        page: Int,
        size: Int,
        principal: AuthenticatedPrincipal,
    ): LongDecisionPageResponse {
        require(thresholdSeconds > 0) { "thresholdSeconds must be positive" }
        require(page >= 0) { "page must be non-negative" }
        val pageSize = size.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE)
        val account = accountOwnershipService.resolveSharedAccount(accountId, principal)
        val results =
            positionOccurrenceRepository.findLongDecisionOccurrences(
                chessAccountId = account.id,
                timeControl = timeControl.name,
                thresholdMs = thresholdSeconds.toLong() * MILLIS_PER_SECOND,
                pageable = PageRequest.of(page, pageSize),
            )

        return LongDecisionPageResponse(
            content =
                results.content.map { occurrence ->
                    val game = occurrence.game
                    LongDecisionOccurrenceResponse(
                        id = occurrence.id,
                        positionId = occurrence.position.id,
                        fen = occurrence.position.fen,
                        playerColor = occurrence.playerColor,
                        plyNumber = occurrence.plyNumber,
                        movePlayed = occurrence.movePlayed,
                        decisionTimeMs = requireNotNull(occurrence.decisionTimeMs),
                        timeControl = timeControl.name,
                        platformGameId = game.platformGameId,
                        playedAt = game.playedAt,
                        opponentUsername =
                            when (occurrence.playerColor) {
                                "WHITE" -> game.blackUsername
                                "BLACK" -> game.whiteUsername
                                else -> null
                            },
                    )
                },
            page = results.number,
            size = results.size,
            totalElements = results.totalElements,
            totalPages = results.totalPages,
            hasNext = results.hasNext(),
        )
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MIN_PAGE_SIZE = 1
        private const val MAX_PAGE_SIZE = 100
    }
}
