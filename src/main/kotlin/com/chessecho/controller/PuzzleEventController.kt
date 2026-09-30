package com.chessecho.controller

import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.dto.PuzzleEventRequest
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.service.AccountOwnershipService
import com.chessecho.service.AccountSelectionRequiredException
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/puzzles/events")
class PuzzleEventController(
    private val accountOwnershipService: AccountOwnershipService,
    private val appUserRepository: AppUserRepository,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository,
) {
    @PostMapping
    fun record(
        @RequestBody request: PuzzleEventRequest,
        principal: AuthenticatedPrincipal,
    ): ResponseEntity<Unit> {
        val accountId = request.accountId ?: throw AccountSelectionRequiredException()
        val account = accountOwnershipService.resolveSharedAccount(accountId, principal)
        val occurrence =
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                request.playerColor,
                listOf(request.positionId),
            ).firstOrNull()
                ?: return ResponseEntity.notFound().build()

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
        return ResponseEntity.accepted().build()
    }
}
