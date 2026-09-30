package com.chessecho.controller

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.SchedulingEventType
import com.chessecho.dto.PuzzleEventRequest
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.service.AccountOwnershipService
import com.chessecho.service.AccountSelectionRequiredException
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PuzzleEventControllerTest {
    private val accountOwnershipService: AccountOwnershipService = mock()
    private val appUserRepository: AppUserRepository = mock()
    private val occurrenceRepository: PositionOccurrenceRepository = mock()
    private val eventRepository: PuzzleSchedulingEventRepository = mock()
    private val controller =
        PuzzleEventController(
            accountOwnershipService,
            appUserRepository,
            occurrenceRepository,
            eventRepository,
        )

    @Test
    fun `record attributes event to requesting principal and named shared account`() {
        val user = AppUser()
        val principal = AuthenticatedPrincipal(user.id, devPrincipal = false)
        val account = ChessAccount(platform = "CHESS_COM", username = "shared-player")
        val position = Position(hash = "position-hash", fen = "fen")
        val occurrence =
            PositionOccurrence(
                chessAccount = account,
                game = mock(),
                position = position,
                plyNumber = 1,
                movePlayed = "e4",
                playerColor = "WHITE",
            )
        val request =
            PuzzleEventRequest(
                positionId = position.id,
                playerColor = "WHITE",
                eventType = SchedulingEventType.SOLVED,
                accountId = account.id,
            )
        whenever(accountOwnershipService.resolveSharedAccount(account.id, principal)).thenReturn(account)
        whenever(appUserRepository.getReferenceById(user.id)).thenReturn(user)
        whenever(
            occurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                request.playerColor,
                listOf(position.id),
            ),
        ).thenReturn(listOf(occurrence))

        val response = controller.record(request, principal)

        assertTrue(response.statusCode.is2xxSuccessful)
        val event = argumentCaptor<com.chessecho.domain.PuzzleSchedulingEvent>()
        verify(eventRepository).save(event.capture())
        assertEquals(user.id, event.firstValue.appUser?.id)
        assertEquals(account.id, event.firstValue.chessAccount.id)
        verify(accountOwnershipService).resolveSharedAccount(account.id, principal)
    }

    @Test
    fun `record searches only the explicitly selected account`() {
        val user = AppUser()
        val principal = AuthenticatedPrincipal(user.id, devPrincipal = false)
        val accountId = com.chessecho.domain.ChessAccount(platform = "CHESS_COM", username = "shared-player").id
        val positionId = Position(hash = "position-hash", fen = "fen").id
        whenever(
            accountOwnershipService.resolveSharedAccount(accountId, principal),
        ).thenReturn(ChessAccount(id = accountId, platform = "CHESS_COM", username = "shared-player"))
        whenever(
            occurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                accountId,
                "WHITE",
                listOf(positionId),
            ),
        ).thenReturn(emptyList())

        val response =
            controller.record(
                PuzzleEventRequest(
                    positionId = positionId,
                    playerColor = "WHITE",
                    eventType = SchedulingEventType.SOLVED,
                    accountId = accountId,
                ),
                principal,
            )

        assertEquals(404, response.statusCode.value())
        verify(eventRepository, org.mockito.kotlin.never()).save(any())
    }

    @Test
    fun `record requires an account selection`() {
        val principal = AuthenticatedPrincipal(AppUser().id, devPrincipal = false)
        val request =
            PuzzleEventRequest(
                positionId = Position(hash = "position-hash", fen = "fen").id,
                playerColor = "WHITE",
                eventType = SchedulingEventType.SOLVED,
            )

        assertThrows(AccountSelectionRequiredException::class.java) {
            controller.record(request, principal)
        }
    }
}
