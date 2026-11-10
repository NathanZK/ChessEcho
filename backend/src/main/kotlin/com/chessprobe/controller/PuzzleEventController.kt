package com.chessprobe.controller

import com.chessprobe.domain.PuzzleSchedulingEvent
import com.chessprobe.dto.PuzzleAttemptCountResponse
import com.chessprobe.dto.PuzzleEventRequest
import com.chessprobe.repository.AppUserRepository
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.repository.PuzzleSchedulingEventRepository
import com.chessprobe.service.AccountOwnershipService
import com.chessprobe.service.AccountSelectionRequiredException
import com.chessprobe.service.PuzzleSubmissionService
import com.chessprobe.service.auth.AuthenticatedPrincipal
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/puzzles")
class PuzzleEventController(
    private val accountOwnershipService: AccountOwnershipService,
    private val appUserRepository: AppUserRepository,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository,
    private val submissionService: PuzzleSubmissionService,
) {
    @GetMapping("/{positionId}/attempt-count")
    fun count(
        @PathVariable positionId: UUID,
        @RequestParam accountId: UUID,
        @RequestParam playerColor: String,
        principal: AuthenticatedPrincipal,
    ): PuzzleAttemptCountResponse = submissionService.count(accountId, positionId, playerColor, principal)

    @PostMapping("/events")
    fun record(
        @RequestBody request: PuzzleEventRequest,
        principal: AuthenticatedPrincipal,
    ): ResponseEntity<*> {
        require((request.submissionId == null) == (request.submittedMove == null)) {
            "submissionId and submittedMove must be supplied together"
        }
        if (request.submissionId != null) {
            return ResponseEntity.accepted().body(submissionService.record(request, principal))
        }
        val accountId = request.accountId ?: throw AccountSelectionRequiredException()
        val account = accountOwnershipService.resolveSharedAccount(accountId, principal)
        val occurrence =
            positionOccurrenceRepository
                .findByChessAccountIdAndPlayerColorAndPositionIdIn(
                    account.id,
                    request.playerColor,
                    listOf(request.positionId),
                ).firstOrNull()
                ?: return ResponseEntity.notFound().build<Unit>()

        val event =
            PuzzleSchedulingEvent(
                appUser = appUserRepository.getReferenceById(principal.appUserId),
                chessAccount = occurrence.chessAccount,
                position = occurrence.position,
                playerColor = request.playerColor,
                eventType = request.eventType,
                occurredAt = Instant.now(),
            )
        puzzleSchedulingEventRepository.save(event)
        return ResponseEntity.accepted().build<Unit>()
    }
}
