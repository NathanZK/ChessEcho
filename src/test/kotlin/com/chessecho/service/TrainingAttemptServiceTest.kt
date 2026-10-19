package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.TrainingAttempt
import com.chessecho.dto.TrainingAttemptRequest
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.TrainingAttemptRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.web.UnauthenticatedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

class TrainingAttemptServiceTest {
    private val repository: TrainingAttemptRepository = mock()
    private val accountOwnershipService: AccountOwnershipService = mock()
    private val appUserRepository: AppUserRepository = mock()
    private val service = TrainingAttemptService(repository, accountOwnershipService, appUserRepository)

    @Test
    fun `rejects negative elapsed duration`() {
        val request =
            TrainingAttemptRequest(
                attemptId = UUID.randomUUID(),
                puzzleId = "puzzle-1",
                mode = "STOPWATCH",
                elapsedMs = -1,
                allowedMs = null,
                outcome = "SUBMITTED",
            )

        assertThrows(IllegalArgumentException::class.java) {
            service.submit(request, null)
        }
    }

    @Test
    fun `persists a valid submitted attempt`() {
        val attemptId = UUID.randomUUID()
        val request =
            TrainingAttemptRequest(
                attemptId = attemptId,
                puzzleId = "puzzle-1",
                mode = "STOPWATCH",
                elapsedMs = 2_500,
                allowedMs = null,
                outcome = "SUBMITTED",
            )
        whenever(repository.existsById(attemptId)).thenReturn(false)
        whenever(repository.save(org.mockito.kotlin.any<TrainingAttempt>()))
            .thenAnswer { it.arguments[0] as TrainingAttempt }

        val response = service.submit(request, null)
        val attempt = argumentCaptor<TrainingAttempt>()
        verify(repository).save(attempt.capture())

        assertEquals(attemptId, response.attemptId)
        assertEquals(2_500, response.elapsedMs)
        assertEquals("SUBMITTED", response.outcome)
        assertEquals(null, attempt.firstValue.appUser)
        assertEquals(null, attempt.firstValue.chessAccount)
    }

    @Test
    fun `authenticated attempts are attributed to the principal and selected shared account`() {
        val attemptId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val appUser = AppUser()
        val account = ChessAccount(id = accountId, platform = "CHESS_COM", username = "player")
        val principal = AuthenticatedPrincipal(appUser.id, devPrincipal = false)
        val request =
            TrainingAttemptRequest(
                attemptId = attemptId,
                puzzleId = "puzzle-1",
                mode = "STOPWATCH",
                elapsedMs = 2_500,
                outcome = "SUBMITTED",
                accountId = accountId,
            )
        whenever(accountOwnershipService.resolveSharedAccount(accountId, principal)).thenReturn(account)
        whenever(appUserRepository.getReferenceById(appUser.id)).thenReturn(appUser)
        whenever(repository.save(any<TrainingAttempt>())).thenAnswer { it.arguments[0] as TrainingAttempt }

        service.submit(request, principal)

        val attempt = argumentCaptor<TrainingAttempt>()
        verify(repository).save(attempt.capture())
        assertEquals(appUser.id, attempt.firstValue.appUser?.id)
        assertEquals(accountId, attempt.firstValue.chessAccount?.id)
    }

    @Test
    fun `authenticated attempt requires an account selection`() {
        val appUser = AppUser()
        val principal = AuthenticatedPrincipal(appUser.id, devPrincipal = false)
        val request =
            TrainingAttemptRequest(
                attemptId = UUID.randomUUID(),
                puzzleId = "puzzle-1",
                mode = "STOPWATCH",
                elapsedMs = 2_500,
                outcome = "SUBMITTED",
            )

        assertThrows(AccountSelectionRequiredException::class.java) {
            service.submit(request, principal)
        }
    }

    @Test
    fun `unauthenticated attempt cannot select a shared account`() {
        val accountId = UUID.randomUUID()
        val request =
            TrainingAttemptRequest(
                attemptId = UUID.randomUUID(),
                puzzleId = "puzzle-1",
                mode = "STOPWATCH",
                elapsedMs = 2_500,
                outcome = "SUBMITTED",
                accountId = accountId,
            )
        whenever(accountOwnershipService.resolveSharedAccount(accountId, null))
            .thenThrow(UnauthenticatedException())

        assertThrows(UnauthenticatedException::class.java) {
            service.submit(request, null)
        }
    }

    @Test
    fun `boundary condition for elapsedMs allows exactly 0 ms and rejects negative`() {
        val attemptId = UUID.randomUUID()
        val requestZero =
            TrainingAttemptRequest(
                attemptId = attemptId,
                puzzleId = "puzzle-1",
                mode = "STOPWATCH",
                elapsedMs = 0,
                allowedMs = null,
                outcome = "SUBMITTED",
            )
        whenever(repository.save(any<TrainingAttempt>())).thenAnswer { it.arguments[0] as TrainingAttempt }

        val response = service.submit(requestZero, null)
        assertEquals(0, response.elapsedMs)

        val requestNegative = requestZero.copy(elapsedMs = -1)
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                service.submit(requestNegative, null)
            }
        assertEquals("Elapsed time cannot be negative: -1ms", ex.message)
    }

    @Test
    fun `allowedMs allows null and non-negative, rejects negative`() {
        whenever(repository.save(any<TrainingAttempt>())).thenAnswer { it.arguments[0] as TrainingAttempt }

        val baseRequest =
            TrainingAttemptRequest(
                attemptId = UUID.randomUUID(),
                puzzleId = "puzzle-1",
                mode = "COUNTDOWN",
                elapsedMs = 1_000,
                allowedMs = 0,
                outcome = "SUBMITTED",
            )

        val responseZero = service.submit(baseRequest, null)
        assertEquals(0, responseZero.allowedMs)

        val responsePositive = service.submit(baseRequest.copy(allowedMs = 15_000), null)
        assertEquals(15_000, responsePositive.allowedMs)

        val responseNull = service.submit(baseRequest.copy(allowedMs = null), null)
        assertEquals(null, responseNull.allowedMs)

        val requestNegativeAllowed = baseRequest.copy(allowedMs = -1)
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                service.submit(requestNegativeAllowed, null)
            }
        assertEquals("Allowed time cannot be negative: -1ms", ex.message)
    }

    @Test
    fun `rejects invalid mode or outcome string values with descriptive exceptions`() {
        val baseRequest =
            TrainingAttemptRequest(
                attemptId = UUID.randomUUID(),
                puzzleId = "puzzle-1",
                mode = "STOPWATCH",
                elapsedMs = 1_000,
                allowedMs = null,
                outcome = "SUBMITTED",
            )

        val invalidModeEx =
            assertThrows(IllegalArgumentException::class.java) {
                service.submit(baseRequest.copy(mode = "SPRINT"), null)
            }
        assertEquals("Invalid timing mode: SPRINT", invalidModeEx.message)

        val invalidOutcomeEx =
            assertThrows(IllegalArgumentException::class.java) {
                service.submit(baseRequest.copy(outcome = "GAVE_UP"), null)
            }
        assertEquals("Invalid outcome: GAVE_UP", invalidOutcomeEx.message)
    }
}
