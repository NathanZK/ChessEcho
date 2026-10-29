package com.chessecho.service

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.DecisionSeverity
import com.chessecho.domain.Game
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.TimeControl
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.service.similarity.ChessComUrlBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LongDecisionServiceTest {
    private val accountOwnershipService = mock<AccountOwnershipService>()
    private val positionOccurrenceRepository = mock<PositionOccurrenceRepository>()
    private val chessComUrlBuilder = mock<ChessComUrlBuilder>()
    private val decisionTimingPolicy = DecisionTimingPolicy()
    private val service =
        LongDecisionService(
            accountOwnershipService,
            positionOccurrenceRepository,
            chessComUrlBuilder,
            decisionTimingPolicy,
        )

    private val accountId = UUID.randomUUID()
    private val principal = AuthenticatedPrincipal(UUID.randomUUID(), devPrincipal = false)
    private val account = ChessAccount(id = accountId, platform = "CHESS_COM", username = "tester")

    private fun createGame(
        platformGameId: String = "game-1",
        whiteUsername: String? = "tester",
        blackUsername: String? = "opponent",
        playedAt: Instant? = null,
        timeControl: String? = null,
    ) = Game(
        chessAccount = account,
        platformGameId = platformGameId,
        pgn = "1. e4 e5 *",
        playedAt = playedAt,
        timeControl = timeControl,
        whiteUsername = whiteUsername,
        blackUsername = blackUsername,
    )

    @BeforeEach
    fun setUp() {
        whenever(accountOwnershipService.resolveSharedAccount(accountId, principal)).thenReturn(account)
        whenever(chessComUrlBuilder.buildExactPlyUrl(anyOrNull(), any(), anyOrNull()))
            .thenReturn("https://www.chess.com/game/live/default?move=1")
    }

    @Test
    fun `rejects non-positive thresholdSeconds`() {
        assertFailsWith<IllegalArgumentException> {
            service.findLongDecisions(
                accountId = accountId,
                timeControl = TimeControl.BLITZ,
                thresholdSeconds = 0,
                page = 0,
                size = 20,
                principal = principal,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            service.findLongDecisions(
                accountId = accountId,
                timeControl = TimeControl.BLITZ,
                thresholdSeconds = -5,
                page = 0,
                size = 20,
                principal = principal,
            )
        }
    }

    @Test
    fun `rejects negative minDurationSeconds`() {
        assertFailsWith<IllegalArgumentException> {
            service.findLongDecisions(
                accountId = accountId,
                timeControl = TimeControl.BLITZ,
                minDurationSeconds = -1,
                thresholdSeconds = null,
                severity = null,
                page = 0,
                size = 20,
                principal = principal,
            )
        }
    }

    @Test
    fun `accepts zero minDurationSeconds`() {
        whenever(
            positionOccurrenceRepository.findLongDecisionOccurrences(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
            ),
        ).thenReturn(PageImpl(emptyList()))

        service.findLongDecisions(
            accountId = accountId,
            timeControl = TimeControl.BLITZ,
            minDurationSeconds = 0,
            thresholdSeconds = null,
            severity = null,
            page = 0,
            size = 20,
            principal = principal,
        )

        verify(positionOccurrenceRepository).findLongDecisionOccurrences(
            eq(accountId),
            eq("BLITZ"),
            eq(0L),
            eq(null),
            eq(PageRequest.of(0, 20)),
        )
    }

    @Test
    fun `rejects negative page`() {
        assertFailsWith<IllegalArgumentException> {
            service.findLongDecisions(
                accountId = accountId,
                timeControl = TimeControl.BLITZ,
                thresholdSeconds = 10,
                page = -1,
                size = 20,
                principal = principal,
            )
        }
    }

    @Test
    fun `clamps page size to minimum boundary when size is zero`() {
        whenever(
            positionOccurrenceRepository.findLongDecisionOccurrences(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
            ),
        ).thenReturn(PageImpl(emptyList()))

        service.findLongDecisions(
            accountId = accountId,
            timeControl = TimeControl.BLITZ,
            thresholdSeconds = 10,
            page = 0,
            size = 0,
            principal = principal,
        )

        val captor = argumentCaptor<Pageable>()
        verify(positionOccurrenceRepository).findLongDecisionOccurrences(
            eq(accountId),
            eq("BLITZ"),
            eq(10_000L),
            eq(null),
            captor.capture(),
        )
        assertEquals(1, captor.firstValue.pageSize)
    }

    @Test
    fun `clamps page size to minimum boundary when size is negative`() {
        whenever(
            positionOccurrenceRepository.findLongDecisionOccurrences(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
            ),
        ).thenReturn(PageImpl(emptyList()))

        service.findLongDecisions(
            accountId = accountId,
            timeControl = TimeControl.BLITZ,
            thresholdSeconds = 10,
            page = 2,
            size = -15,
            principal = principal,
        )

        val captor = argumentCaptor<Pageable>()
        verify(positionOccurrenceRepository).findLongDecisionOccurrences(
            eq(accountId),
            eq("BLITZ"),
            eq(10_000L),
            eq(null),
            captor.capture(),
        )
        assertEquals(1, captor.firstValue.pageSize)
        assertEquals(2, captor.firstValue.pageNumber)
    }

    @Test
    fun `clamps page size to maximum boundary when size exceeds 100`() {
        whenever(
            positionOccurrenceRepository.findLongDecisionOccurrences(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
            ),
        ).thenReturn(PageImpl(emptyList()))

        service.findLongDecisions(
            accountId = accountId,
            timeControl = TimeControl.RAPID,
            thresholdSeconds = 20,
            page = 1,
            size = 101,
            principal = principal,
        )

        val captor = argumentCaptor<Pageable>()
        verify(positionOccurrenceRepository).findLongDecisionOccurrences(
            eq(accountId),
            eq("RAPID"),
            eq(20_000L),
            eq(null),
            captor.capture(),
        )
        assertEquals(100, captor.firstValue.pageSize)
        assertEquals(1, captor.firstValue.pageNumber)
    }

    @Test
    fun `preserves valid page size within bounds`() {
        whenever(
            positionOccurrenceRepository.findLongDecisionOccurrences(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
            ),
        ).thenReturn(PageImpl(emptyList()))

        service.findLongDecisions(
            accountId = accountId,
            timeControl = TimeControl.BULLET,
            thresholdSeconds = 5,
            page = 0,
            size = 25,
            principal = principal,
        )

        val captor = argumentCaptor<Pageable>()
        verify(positionOccurrenceRepository).findLongDecisionOccurrences(
            eq(accountId),
            eq("BULLET"),
            eq(5_000L),
            eq(null),
            captor.capture(),
        )
        assertEquals(25, captor.firstValue.pageSize)
    }

    @Test
    fun `converts threshold seconds to milliseconds and queries repository with resolved account`() {
        val targetAccountId = UUID.randomUUID()
        val resolvedAccount = ChessAccount(id = targetAccountId, platform = "CHESS_COM", username = "resolved_user")
        whenever(accountOwnershipService.resolveSharedAccount(targetAccountId, principal)).thenReturn(resolvedAccount)
        whenever(
            positionOccurrenceRepository.findLongDecisionOccurrences(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
            ),
        ).thenReturn(PageImpl(emptyList()))

        service.findLongDecisions(
            accountId = targetAccountId,
            timeControl = TimeControl.CLASSICAL,
            thresholdSeconds = 45,
            page = 3,
            size = 50,
            principal = principal,
        )

        verify(accountOwnershipService).resolveSharedAccount(targetAccountId, principal)
        verify(positionOccurrenceRepository).findLongDecisionOccurrences(
            eq(targetAccountId),
            eq("CLASSICAL"),
            eq(45_000L),
            eq(null),
            eq(PageRequest.of(3, 50)),
        )
    }

    @Test
    fun `applies independent minDurationSeconds and severity range to query bounds`() {
        whenever(
            positionOccurrenceRepository.findLongDecisionOccurrences(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
            ),
        ).thenReturn(PageImpl(emptyList()))

        // Rapid MINOR is [30_000ms, 60_000ms). User specifies 40 seconds.
        // effectiveMinMs should be 40_000L, effectiveMaxMs should be 60_000L.
        service.findLongDecisions(
            accountId = accountId,
            timeControl = TimeControl.RAPID,
            minDurationSeconds = 40,
            thresholdSeconds = null,
            severity = DecisionSeverity.MINOR,
            page = 0,
            size = 20,
            principal = principal,
        )

        verify(positionOccurrenceRepository).findLongDecisionOccurrences(
            eq(accountId),
            eq("RAPID"),
            eq(40_000L),
            eq(60_000L),
            eq(PageRequest.of(0, 20)),
        )
    }

    @Test
    fun `minDurationSeconds takes precedence over thresholdSeconds`() {
        whenever(
            positionOccurrenceRepository.findLongDecisionOccurrences(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
            ),
        ).thenReturn(PageImpl(emptyList()))

        service.findLongDecisions(
            accountId = accountId,
            timeControl = TimeControl.BLITZ,
            minDurationSeconds = 25,
            thresholdSeconds = 10,
            severity = null,
            page = 0,
            size = 20,
            principal = principal,
        )

        verify(positionOccurrenceRepository).findLongDecisionOccurrences(
            eq(accountId),
            eq("BLITZ"),
            eq(25_000L),
            eq(null),
            eq(PageRequest.of(0, 20)),
        )
    }

    @Test
    fun `maps WHITE player occurrences resolving blackUsername as opponent and calculates severity`() {
        val playedAt = Instant.parse("2026-10-15T14:30:00Z")
        val game =
            createGame(
                platformGameId = "game-white-1",
                whiteUsername = "tester",
                blackUsername = "black_opponent",
                playedAt = playedAt,
            )
        val position = Position(hash = "hash-1", fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        val occurrence =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "e4",
                playerColor = "WHITE",
                decisionTimeMs = 16_000L,
            )

        val page = PageImpl(listOf(occurrence), PageRequest.of(0, 20), 1)
        whenever(
            positionOccurrenceRepository.findLongDecisionOccurrences(
                any(),
                any(),
                any(),
                anyOrNull(),
                any(),
            ),
        ).thenReturn(page)

        val response =
            service.findLongDecisions(
                accountId = accountId,
                timeControl = TimeControl.BLITZ,
                thresholdSeconds = 10,
                page = 0,
                size = 20,
                principal = principal,
            )

        assertEquals(1, response.content.size)
        val item = response.content.single()
        assertEquals(occurrence.id, item.id)
        assertEquals(position.id, item.positionId)
        assertEquals(position.fen, item.fen)
        assertEquals("WHITE", item.playerColor)
        assertEquals(1, item.plyNumber)
        assertEquals("e4", item.movePlayed)
        assertEquals(16_000L, item.decisionTimeMs)
        assertEquals("BLITZ", item.timeControl)
        assertEquals("game-white-1", item.platformGameId)
        assertEquals(playedAt, item.playedAt)
        assertEquals("black_opponent", item.opponentUsername)
        assertEquals(DecisionSeverity.MINOR, item.severity)

        assertEquals(0, response.page)
        assertEquals(20, response.size)
        assertEquals(1, response.totalElements)
        assertEquals(1, response.totalPages)
        assertFalse(response.hasNext)
    }

    @Test
    fun `when filter intersection is empty, returns empty response without querying repository`() {
        val response =
            service.findLongDecisions(
                accountId = accountId,
                timeControl = TimeControl.BLITZ,
                minDurationSeconds = 35,
                thresholdSeconds = null,
                severity = DecisionSeverity.MINOR,
                page = 0,
                size = 20,
                principal = principal,
            )

        assertTrue(response.content.isEmpty())
        assertEquals(0, response.totalElements)
        assertEquals(0, response.totalPages)
        assertFalse(response.hasNext)
        verify(positionOccurrenceRepository, never())
            .findLongDecisionOccurrences(any(), any(), any(), anyOrNull(), any())
    }
}
