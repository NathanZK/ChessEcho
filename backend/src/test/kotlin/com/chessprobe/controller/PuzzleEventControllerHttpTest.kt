package com.chessprobe.controller

import com.chessprobe.config.SessionCookieProperties
import com.chessprobe.config.SessionWebConfig
import com.chessprobe.domain.AppUser
import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.Game
import com.chessprobe.domain.Position
import com.chessprobe.domain.PositionOccurrence
import com.chessprobe.dto.PuzzleAttemptCountResponse
import com.chessprobe.dto.PuzzleSubmissionReceipt
import com.chessprobe.repository.AppUserRepository
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.repository.PuzzleSchedulingEventRepository
import com.chessprobe.service.AccountOwnershipService
import com.chessprobe.service.PuzzleSubmissionConflictException
import com.chessprobe.service.PuzzleSubmissionService
import com.chessprobe.service.auth.AuthenticatedPrincipal
import com.chessprobe.service.auth.IdentitySessionService
import com.chessprobe.web.AuthenticatedPrincipalArgumentResolver
import com.chessprobe.web.CsrfEnforcementInterceptor
import com.chessprobe.web.SessionAuthenticationFilter
import com.chessprobe.web.SessionCookieWriter
import jakarta.servlet.http.Cookie
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

@WebMvcTest(PuzzleEventController::class)
@EnableConfigurationProperties(SessionCookieProperties::class)
@Import(
    SessionAuthenticationFilter::class,
    AuthenticatedPrincipalArgumentResolver::class,
    CsrfEnforcementInterceptor::class,
    SessionWebConfig::class,
    SessionCookieWriter::class,
)
@TestPropertySource(properties = ["chessprobe.auth.cookie.name=CHESSPROBE_SESSION"])
class PuzzleEventControllerHttpTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var accountOwnershipService: AccountOwnershipService

    @MockBean
    private lateinit var appUserRepository: AppUserRepository

    @MockBean
    private lateinit var occurrenceRepository: PositionOccurrenceRepository

    @MockBean
    private lateinit var eventRepository: PuzzleSchedulingEventRepository

    @MockBean
    private lateinit var submissionService: PuzzleSubmissionService

    @MockBean
    private lateinit var identitySessionService: IdentitySessionService

    private val user = AppUser()
    private val principal = AuthenticatedPrincipal(user.id, devPrincipal = false)
    private val account = ChessAccount(platform = "CHESS_COM", username = "player")
    private val position = Position(hash = "position-hash", fen = "fen")
    private val occurrence =
        PositionOccurrence(
            chessAccount = account,
            game = mock<Game>(),
            position = position,
            plyNumber = 1,
            movePlayed = "e4",
            playerColor = "WHITE",
        )

    @ParameterizedTest
    @ValueSource(strings = ["PRESENTED", "STARTED", "SKIPPED"])
    fun `removed event type is rejected before event persistence`(eventType: String) {
        whenever(identitySessionService.resolveSession("valid-session")).thenReturn(principal)
        whenever(accountOwnershipService.resolveSharedAccount(account.id, principal)).thenReturn(account)
        whenever(appUserRepository.getReferenceById(user.id)).thenReturn(user)
        whenever(
            occurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                listOf(position.id),
            ),
        ).thenReturn(listOf(occurrence))

        mockMvc
            .post("/api/puzzles/events") {
                cookie(Cookie("CHESSPROBE_SESSION", "valid-session"))
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"positionId":"${position.id}","playerColor":"WHITE","eventType":"$eventType","accountId":"${account.id}"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("VALIDATION_ERROR") }
            }

        verify(occurrenceRepository, never()).findByChessAccountIdAndPlayerColorAndPositionIdIn(
            account.id,
            "WHITE",
            listOf(position.id),
        )
        verify(eventRepository, never()).save(any())
    }

    @org.junit.jupiter.api.Test
    fun `identified submissions return their submission receipt`() {
        val submissionId = UUID.randomUUID()
        whenever(submissionService.record(any(), any())).thenReturn(PuzzleSubmissionReceipt(submissionId))
        whenever(identitySessionService.resolveSession("valid-session")).thenReturn(principal)
        whenever(accountOwnershipService.resolveSharedAccount(account.id, principal)).thenReturn(account)
        whenever(appUserRepository.getReferenceById(user.id)).thenReturn(user)
        whenever(
            occurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                listOf(position.id),
            ),
        ).thenReturn(listOf(occurrence))

        mockMvc
            .post("/api/puzzles/events") {
                cookie(Cookie("CHESSPROBE_SESSION", "valid-session"))
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {"positionId":"${position.id}","playerColor":"WHITE","eventType":"SOLVED",
                     "accountId":"${account.id}","submissionId":"$submissionId","submittedMove":"e4"}
                    """.trimIndent()
            }.andExpect {
                status { isAccepted() }
                jsonPath("$.submissionId") { value(submissionId.toString()) }
            }
    }

    @org.junit.jupiter.api.Test
    fun `identified submission requires both identity and submitted move`() {
        whenever(identitySessionService.resolveSession("valid-session")).thenReturn(principal)

        mockMvc
            .post("/api/puzzles/events") {
                cookie(Cookie("CHESSPROBE_SESSION", "valid-session"))
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {"positionId":"${position.id}","playerColor":"WHITE","eventType":"SOLVED",
                     "accountId":"${account.id}","submissionId":"${UUID.randomUUID()}"}
                    """.trimIndent()
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("VALIDATION_ERROR") }
            }

        verify(occurrenceRepository, never()).findByChessAccountIdAndPlayerColorAndPositionIdIn(
            any(),
            any(),
            any(),
        )
    }

    @org.junit.jupiter.api.Test
    fun `attempt count endpoint returns exactly the authenticated scoped outcome counts`() {
        whenever(submissionService.count(account.id, position.id, "WHITE", principal)).thenReturn(PuzzleAttemptCountResponse(2, 3))
        whenever(identitySessionService.resolveSession("valid-session")).thenReturn(principal)
        whenever(accountOwnershipService.resolveSharedAccount(account.id, principal)).thenReturn(account)

        mockMvc
            .get("/api/puzzles/${position.id}/attempt-count") {
                cookie(Cookie("CHESSPROBE_SESSION", "valid-session"))
                param("accountId", account.id.toString())
                param("playerColor", "WHITE")
            }.andExpect {
                status { isOk() }
                jsonPath("$.solvedCount") { value(2) }
                jsonPath("$.failedCount") { value(3) }
                jsonPath("$.attemptCount") { doesNotExist() }
                jsonPath("$.*") { value(org.hamcrest.Matchers.hasSize<Int>(2)) }
            }
    }

    @org.junit.jupiter.api.Test
    fun `guest count request is rejected before service invocation`() {
        mockMvc
            .get("/api/puzzles/${position.id}/attempt-count") {
                param("accountId", UUID.randomUUID().toString())
                param("playerColor", "WHITE")
            }.andExpect {
                status { isUnauthorized() }
                jsonPath("$.error") { value("UNAUTHENTICATED") }
            }
        verify(submissionService, never()).count(any(), any(), any(), any())
    }

    @org.junit.jupiter.api.Test
    fun `count request requires explicit account`() {
        whenever(identitySessionService.resolveSession("valid-session")).thenReturn(principal)
        mockMvc
            .get("/api/puzzles/${position.id}/attempt-count") {
                cookie(Cookie("CHESSPROBE_SESSION", "valid-session"))
                param("playerColor", "WHITE")
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("VALIDATION_ERROR") }
            }
        verify(submissionService, never()).count(any(), any(), any(), any())
    }

    @org.junit.jupiter.api.Test
    fun `record returns 404 Not Found when position occurrence does not exist`() {
        whenever(identitySessionService.resolveSession("valid-session")).thenReturn(principal)
        whenever(accountOwnershipService.resolveSharedAccount(account.id, principal)).thenReturn(account)
        whenever(
            occurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                listOf(position.id),
            ),
        ).thenReturn(emptyList())

        mockMvc
            .post("/api/puzzles/events") {
                cookie(Cookie("CHESSPROBE_SESSION", "valid-session"))
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"positionId":"${position.id}","playerColor":"WHITE","eventType":"SOLVED","accountId":"${account.id}"}"""
            }.andExpect {
                status { isNotFound() }
            }
    }

    @org.junit.jupiter.api.Test
    fun `record returns 409 Conflict when PuzzleSubmissionConflictException is raised`() {
        whenever(identitySessionService.resolveSession("valid-session")).thenReturn(principal)
        whenever(submissionService.record(any(), any())).thenThrow(PuzzleSubmissionConflictException())

        mockMvc
            .post("/api/puzzles/events") {
                cookie(Cookie("CHESSPROBE_SESSION", "valid-session"))
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {"positionId":"${position.id}","playerColor":"WHITE","eventType":"SOLVED",
                     "accountId":"${account.id}","submissionId":"${UUID.randomUUID()}","submittedMove":"e4"}
                    """.trimIndent()
            }.andExpect {
                status { isConflict() }
                jsonPath("$.error") { value("PUZZLE_SUBMISSION_CONFLICT") }
            }
    }
}
