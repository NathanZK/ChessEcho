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
}
