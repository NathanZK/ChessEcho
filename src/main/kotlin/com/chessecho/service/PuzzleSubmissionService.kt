package com.chessecho.service

import com.chessecho.domain.SchedulingEventType
import com.chessecho.dto.PuzzleAttemptCountResponse
import com.chessecho.dto.PuzzleEventRequest
import com.chessecho.dto.PuzzleSubmissionReceipt
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class PuzzleSubmissionService(
    private val accounts: AccountOwnershipService,
    private val occurrences: PositionOccurrenceRepository,
    private val events: PuzzleSchedulingEventRepository,
) {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun record(
        request: PuzzleEventRequest,
        principal: AuthenticatedPrincipal,
    ): PuzzleSubmissionReceipt {
        val submissionId = requireNotNull(request.submissionId) { "submissionId is required" }
        val move = requireNotNull(request.submittedMove) { "submittedMove is required" }
        require(move.isNotBlank() && move == move.trim() && move.length <= 255) { "submittedMove must be nonblank canonical SAN" }
        require(request.eventType == SchedulingEventType.SOLVED || request.eventType == SchedulingEventType.FAILED) {
            "Identified submissions must be SOLVED or FAILED"
        }
        val accountId = request.accountId ?: throw AccountSelectionRequiredException()
        validateContext(accountId, request.positionId, request.playerColor, principal)
        events.insertSubmission(
            UUID.randomUUID(),
            principal.appUserId,
            accountId,
            request.positionId,
            request.playerColor,
            request.eventType.name,
            Instant.now(),
            submissionId,
            move,
        )
        // Separate READ COMMITTED statement sees the winner after a concurrent insert waits.
        val stored =
            events.findByAppUserIdAndSubmissionId(principal.appUserId, submissionId)
                ?: throw IllegalStateException("Persisted puzzle submission receipt is missing")
        if (stored.chessAccount.id != accountId || stored.position.id != request.positionId ||
            stored.playerColor != request.playerColor || stored.eventType != request.eventType || stored.submittedMove != move
        ) {
            throw PuzzleSubmissionConflictException()
        }
        return PuzzleSubmissionReceipt(submissionId)
    }

    @Transactional(readOnly = true)
    fun count(
        accountId: UUID,
        positionId: UUID,
        playerColor: String,
        principal: AuthenticatedPrincipal,
    ): PuzzleAttemptCountResponse {
        validateContext(accountId, positionId, playerColor, principal)
        val counts =
            events.countSubmittedAnswers(principal.appUserId, accountId, positionId, playerColor)
                .associate { it.outcome to it.submissionCount }
        return PuzzleAttemptCountResponse(
            solvedCount = counts[SchedulingEventType.SOLVED] ?: 0,
            failedCount = counts[SchedulingEventType.FAILED] ?: 0,
        )
    }

    private fun validateContext(
        accountId: UUID,
        positionId: UUID,
        playerColor: String,
        principal: AuthenticatedPrincipal,
    ) {
        require(playerColor == "WHITE" || playerColor == "BLACK") { "playerColor must be WHITE or BLACK" }
        accounts.resolveSharedAccount(accountId, principal)
        if (occurrences.findByChessAccountIdAndPlayerColorAndPositionIdIn(accountId, playerColor, listOf(positionId)).isEmpty()) {
            throw NoSuchElementException("Puzzle position is not present in the selected account/color")
        }
    }
}

class PuzzleSubmissionConflictException : RuntimeException("Submission identity was already used for a different answer")
