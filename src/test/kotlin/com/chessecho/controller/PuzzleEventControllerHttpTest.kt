package com.chessecho.controller

import com.chessecho.config.SessionCookieProperties
import com.chessecho.config.SessionWebConfig
import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.service.AccountOwnershipService
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.service.auth.IdentitySessionService
import com.chessecho.web.AuthenticatedPrincipalArgumentResolver
import com.chessecho.web.CsrfEnforcementInterceptor
import com.chessecho.web.SessionAuthenticationFilter
import com.chessecho.web.SessionCookieWriter
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
import org.springframework.test.web.servlet.post

@WebMvcTest(PuzzleEventController::class)
@EnableConfigurationProperties(SessionCookieProperties::class)
@Import(
    SessionAuthenticationFilter::class,
    AuthenticatedPrincipalArgumentResolver::class,
    CsrfEnforcementInterceptor::class,
    SessionWebConfig::class,
    SessionCookieWriter::class,
)
@TestPropertySource(properties = ["chessecho.auth.cookie.name=CHESSECHO_SESSION"])
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

        mockMvc.post("/api/puzzles/events") {
            cookie(Cookie("CHESSECHO_SESSION", "valid-session"))
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
}
