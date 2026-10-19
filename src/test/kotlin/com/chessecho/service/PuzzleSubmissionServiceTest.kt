package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import com.chessecho.dto.PuzzleEventRequest
import com.chessecho.dto.PuzzleSubmissionReceipt
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleOutcomeCount
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID

class PuzzleSubmissionServiceTest {
    private val accounts: AccountOwnershipService = mock()
    private val occurrences: PositionOccurrenceRepository = mock()
    private val events: PuzzleSchedulingEventRepository = mock()
    private lateinit var service: PuzzleSubmissionService

    private val user = AppUser()
    private val principal = AuthenticatedPrincipal(user.id, devPrincipal = false)
    private val account = ChessAccount(id = UUID.randomUUID(), platform = "CHESS_COM", username = "tester")
    private val position = Position(id = UUID.randomUUID(), hash = "pos-hash", fen = "pos-fen")
    private val occurrence =
        PositionOccurrence(
            id = UUID.randomUUID(),
            game = mock(),
            position = position,
            chessAccount = account,
            plyNumber = 1,
            movePlayed = "e4",
            playerColor = "WHITE",
        )

    @BeforeEach
    fun setup() {
        service = PuzzleSubmissionService(accounts, occurrences, events)
    }

    private data class OutcomeCountImpl(
        override val outcome: SchedulingEventType,
        override val submissionCount: Long,
    ) : PuzzleOutcomeCount

    @Test
    fun `record rejects null submissionId and null submittedMove`() {
        val requestNoSubmissionId =
            PuzzleEventRequest(
                positionId = position.id,
                playerColor = "WHITE",
                eventType = SchedulingEventType.SOLVED,
                accountId = account.id,
                submissionId = null,
                submittedMove = "e4",
            )
        assertThrows(IllegalArgumentException::class.java) {
            service.record(requestNoSubmissionId, principal)
        }

        val requestNoMove =
            PuzzleEventRequest(
                positionId = position.id,
                playerColor = "WHITE",
                eventType = SchedulingEventType.SOLVED,
                accountId = account.id,
                submissionId = UUID.randomUUID(),
                submittedMove = null,
            )
        assertThrows(IllegalArgumentException::class.java) {
            service.record(requestNoMove, principal)
        }
    }

    @Test
    fun `record rejects blank untrimmed or excessively long move strings`() {
        val validSubmissionId = UUID.randomUUID()

        fun req(move: String) =
            PuzzleEventRequest(
                positionId = position.id,
                playerColor = "WHITE",
                eventType = SchedulingEventType.SOLVED,
                accountId = account.id,
                submissionId = validSubmissionId,
                submittedMove = move,
            )

        assertThrows(IllegalArgumentException::class.java) { service.record(req(""), principal) }
        assertThrows(IllegalArgumentException::class.java) { service.record(req("   "), principal) }
        assertThrows(IllegalArgumentException::class.java) { service.record(req(" e4"), principal) }
        assertThrows(IllegalArgumentException::class.java) { service.record(req("e4 "), principal) }
        assertThrows(IllegalArgumentException::class.java) { service.record(req("a".repeat(256)), principal) }
    }

    @Test
    fun `record rejects invalid event types other than SOLVED and FAILED`() {
        val request =
            PuzzleEventRequest(
                positionId = position.id,
                playerColor = "WHITE",
                eventType = SchedulingEventType.GAME_MISTAKE,
                accountId = account.id,
                submissionId = UUID.randomUUID(),
                submittedMove = "e4",
            )
        assertThrows(IllegalArgumentException::class.java) {
            service.record(request, principal)
        }
    }

    @Test
    fun `record rejects missing accountId and invalid playerColor`() {
        val requestNoAccount =
            PuzzleEventRequest(
                positionId = position.id,
                playerColor = "WHITE",
                eventType = SchedulingEventType.SOLVED,
                accountId = null,
                submissionId = UUID.randomUUID(),
                submittedMove = "e4",
            )
        assertThrows(AccountSelectionRequiredException::class.java) {
            service.record(requestNoAccount, principal)
        }

        val requestInvalidColor =
            PuzzleEventRequest(
                positionId = position.id,
                playerColor = "RED",
                eventType = SchedulingEventType.SOLVED,
                accountId = account.id,
                submissionId = UUID.randomUUID(),
                submittedMove = "e4",
            )
        assertThrows(IllegalArgumentException::class.java) {
            service.record(requestInvalidColor, principal)
        }
    }

    @Test
    fun `record throws NoSuchElementException when position occurrence is not found`() {
        val request =
            PuzzleEventRequest(
                positionId = position.id,
                playerColor = "WHITE",
                eventType = SchedulingEventType.SOLVED,
                accountId = account.id,
                submissionId = UUID.randomUUID(),
                submittedMove = "e4",
            )
        whenever(accounts.resolveSharedAccount(account.id, principal)).thenReturn(account)
        whenever(
            occurrences.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                listOf(position.id),
            ),
        ).thenReturn(emptyList())

        val ex =
            assertThrows(NoSuchElementException::class.java) {
                service.record(request, principal)
            }
        assertEquals("Puzzle position is not present in the selected account/color", ex.message)
        verify(events, never()).insertSubmission(any(), any(), any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `record returns existing receipt idempotently on matching duplicate submission`() {
        val submissionId = UUID.randomUUID()
        val request =
            PuzzleEventRequest(
                positionId = position.id,
                playerColor = "WHITE",
                eventType = SchedulingEventType.SOLVED,
                accountId = account.id,
                submissionId = submissionId,
                submittedMove = "e4",
            )
        whenever(accounts.resolveSharedAccount(account.id, principal)).thenReturn(account)
        whenever(
            occurrences.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                listOf(position.id),
            ),
        ).thenReturn(listOf(occurrence))

        val storedEvent =
            PuzzleSchedulingEvent(
                id = UUID.randomUUID(),
                appUser = user,
                chessAccount = account,
                position = position,
                playerColor = "WHITE",
                eventType = SchedulingEventType.SOLVED,
                occurredAt = Instant.now(),
                submissionId = submissionId,
                submittedMove = "e4",
            )
        whenever(events.findByAppUserIdAndSubmissionId(principal.appUserId, submissionId))
            .thenReturn(storedEvent)

        val receipt = service.record(request, principal)

        assertEquals(PuzzleSubmissionReceipt(submissionId), receipt)
        verify(events).insertSubmission(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
        )
    }

    @Test
    fun `record throws PuzzleSubmissionConflictException on mismatched duplicate submission`() {
        val submissionId = UUID.randomUUID()
        val request =
            PuzzleEventRequest(
                positionId = position.id,
                playerColor = "WHITE",
                eventType = SchedulingEventType.SOLVED,
                accountId = account.id,
                submissionId = submissionId,
                submittedMove = "e4",
            )
        whenever(accounts.resolveSharedAccount(account.id, principal)).thenReturn(account)
        whenever(
            occurrences.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                listOf(position.id),
            ),
        ).thenReturn(listOf(occurrence))

        // Mismatched move
        val conflictingEvent =
            PuzzleSchedulingEvent(
                id = UUID.randomUUID(),
                appUser = user,
                chessAccount = account,
                position = position,
                playerColor = "WHITE",
                eventType = SchedulingEventType.SOLVED,
                occurredAt = Instant.now(),
                submissionId = submissionId,
                submittedMove = "Nf3",
            )
        whenever(events.findByAppUserIdAndSubmissionId(principal.appUserId, submissionId))
            .thenReturn(conflictingEvent)

        assertThrows(PuzzleSubmissionConflictException::class.java) {
            service.record(request, principal)
        }
    }

    @Test
    fun `record throws IllegalStateException when persisted receipt is missing after insertion`() {
        val submissionId = UUID.randomUUID()
        val request =
            PuzzleEventRequest(
                positionId = position.id,
                playerColor = "WHITE",
                eventType = SchedulingEventType.SOLVED,
                accountId = account.id,
                submissionId = submissionId,
                submittedMove = "e4",
            )
        whenever(accounts.resolveSharedAccount(account.id, principal)).thenReturn(account)
        whenever(
            occurrences.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                listOf(position.id),
            ),
        ).thenReturn(listOf(occurrence))
        whenever(events.findByAppUserIdAndSubmissionId(principal.appUserId, submissionId)).thenReturn(null)

        val ex =
            assertThrows(IllegalStateException::class.java) {
                service.record(request, principal)
            }
        assertEquals("Persisted puzzle submission receipt is missing", ex.message)
    }

    @Test
    fun `count aggregates SOLVED and FAILED counts and defaults absent outcomes to 0`() {
        whenever(accounts.resolveSharedAccount(account.id, principal)).thenReturn(account)
        whenever(
            occurrences.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                listOf(position.id),
            ),
        ).thenReturn(listOf(occurrence))

        whenever(events.countSubmittedAnswers(principal.appUserId, account.id, position.id, "WHITE"))
            .thenReturn(
                listOf(
                    OutcomeCountImpl(SchedulingEventType.SOLVED, 7L),
                    OutcomeCountImpl(SchedulingEventType.FAILED, 3L),
                ),
            )

        val response = service.count(account.id, position.id, "WHITE", principal)
        assertEquals(7L, response.solvedCount)
        assertEquals(3L, response.failedCount)

        // Missing FAILED outcome in counts list
        whenever(events.countSubmittedAnswers(principal.appUserId, account.id, position.id, "WHITE"))
            .thenReturn(
                listOf(
                    OutcomeCountImpl(SchedulingEventType.SOLVED, 4L),
                ),
            )

        val partialResponse = service.count(account.id, position.id, "WHITE", principal)
        assertEquals(4L, partialResponse.solvedCount)
        assertEquals(0L, partialResponse.failedCount)
    }

    @Test
    fun `record accepts move of length exactly 255`() {
        val submissionId = UUID.randomUUID()
        val move255 = "e".repeat(255)
        val request =
            PuzzleEventRequest(
                positionId = position.id,
                playerColor = "WHITE",
                eventType = SchedulingEventType.SOLVED,
                accountId = account.id,
                submissionId = submissionId,
                submittedMove = move255,
            )
        whenever(accounts.resolveSharedAccount(account.id, principal)).thenReturn(account)
        whenever(
            occurrences.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                listOf(position.id),
            ),
        ).thenReturn(listOf(occurrence))

        val storedEvent =
            PuzzleSchedulingEvent(
                id = UUID.randomUUID(),
                appUser = user,
                chessAccount = account,
                position = position,
                playerColor = "WHITE",
                eventType = SchedulingEventType.SOLVED,
                occurredAt = Instant.now(),
                submissionId = submissionId,
                submittedMove = move255,
            )
        whenever(events.findByAppUserIdAndSubmissionId(principal.appUserId, submissionId))
            .thenReturn(storedEvent)

        val receipt = service.record(request, principal)
        assertEquals(PuzzleSubmissionReceipt(submissionId), receipt)
    }

    @Test
    fun `count validates playerColor and position presence`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.count(account.id, position.id, "RED", principal)
        }

        whenever(accounts.resolveSharedAccount(account.id, principal)).thenReturn(account)
        whenever(
            occurrences.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                listOf(position.id),
            ),
        ).thenReturn(emptyList())

        assertThrows(NoSuchElementException::class.java) {
            service.count(account.id, position.id, "WHITE", principal)
        }
    }
}
