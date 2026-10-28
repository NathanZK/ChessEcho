package com.chessecho.service

import com.chessecho.domain.ChessAccount
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LongDecisionServiceTest {
    private val accountOwnershipService = mock<AccountOwnershipService>()
    private val positionOccurrenceRepository = mock<PositionOccurrenceRepository>()
    private val chessComUrlBuilder = mock<ChessComUrlBuilder>()
    private val service = LongDecisionService(accountOwnershipService, positionOccurrenceRepository, chessComUrlBuilder)

    private val accountId = UUID.randomUUID()
    private val principal = AuthenticatedPrincipal(UUID.randomUUID(), devPrincipal = false)
    private val account = ChessAccount(id = accountId, platform = "CHESS_COM", username = "tester")

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
            eq(PageRequest.of(3, 50)),
        )
    }

    @Test
    fun `maps WHITE player occurrences resolving blackUsername as opponent`() {
        val playedAt = Instant.parse("2026-10-15T14:30:00Z")
        val game =
            Game(
                chessAccount = account,
                platformGameId = "game-white-1",
                pgn = "1. e4 e5 *",
                playedAt = playedAt,
                whiteUsername = "tester",
                blackUsername = "black_opponent",
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
                decisionTimeMs = 12_500L,
            )

        val page = PageImpl(listOf(occurrence), PageRequest.of(0, 20), 1)
        whenever(
            positionOccurrenceRepository.findLongDecisionOccurrences(
                any(),
                any(),
                any(),
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
        assertEquals(12_500L, item.decisionTimeMs)
        assertEquals("BLITZ", item.timeControl)
        assertEquals("game-white-1", item.platformGameId)
        assertEquals(playedAt, item.playedAt)
        assertEquals("black_opponent", item.opponentUsername)

        assertEquals(0, response.page)
        assertEquals(20, response.size)
        assertEquals(1, response.totalElements)
        assertEquals(1, response.totalPages)
        assertFalse(response.hasNext)
    }

    @Test
    fun `maps BLACK player occurrences resolving whiteUsername as opponent`() {
        val playedAt = Instant.parse("2026-10-16T16:00:00Z")
        val game =
            Game(
                chessAccount = account,
                platformGameId = "game-black-1",
                pgn = "1. e4 e5 2. Nf3 Nc6 *",
                playedAt = playedAt,
                whiteUsername = "white_opponent",
                blackUsername = "tester",
            )
        val position = Position(hash = "hash-2", fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3")
        val occurrence =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 4,
                movePlayed = "Nc6",
                playerColor = "BLACK",
                decisionTimeMs = 25_000L,
            )

        val page = PageImpl(listOf(occurrence), PageRequest.of(0, 20), 1)
        whenever(
            positionOccurrenceRepository.findLongDecisionOccurrences(
                any(),
                any(),
                any(),
                any(),
            ),
        ).thenReturn(page)
        whenever(
            chessComUrlBuilder.buildExactPlyUrl(eq("game-white-1"), eq(1), anyOrNull()),
        ).thenReturn("https://www.chess.com/game/live/game-white-1?move=1")

        val response =
            service.findLongDecisions(
                accountId = accountId,
                timeControl = TimeControl.RAPID,
                thresholdSeconds = 15,
                page = 0,
                size = 20,
                principal = principal,
            )

        val item = response.content.single()
        assertEquals("BLACK", item.playerColor)
        assertEquals("white_opponent", item.opponentUsername)
    }

    @Test
    fun `maps unexpected player color to null opponentUsername`() {
        val game =
            Game(
                chessAccount = account,
                platformGameId = "game-other-1",
                pgn = "*",
                whiteUsername = "white_user",
                blackUsername = "black_user",
            )
        val position = Position(hash = "hash-3", fen = "8/8/8/8/8/8/8/8 w - -")
        val occurrence =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "e4",
                playerColor = "SPECTATOR",
                decisionTimeMs = 5_000L,
            )

        val page = PageImpl(listOf(occurrence), PageRequest.of(0, 10), 1)
        whenever(
            positionOccurrenceRepository.findLongDecisionOccurrences(
                any(),
                any(),
                any(),
                any(),
            ),
        ).thenReturn(page)

        val response =
            service.findLongDecisions(
                accountId = accountId,
                timeControl = TimeControl.BLITZ,
                thresholdSeconds = 5,
                page = 0,
                size = 10,
                principal = principal,
            )

        val item = response.content.single()
        assertEquals("SPECTATOR", item.playerColor)
        assertNull(item.opponentUsername)
    }

    @Test
    fun `throws IllegalArgumentException when occurrence decisionTimeMs is null`() {
        val game =
            Game(
                chessAccount = account,
                platformGameId = "game-null-time",
                pgn = "*",
                whiteUsername = "white_user",
                blackUsername = "black_user",
            )
        val position = Position(hash = "hash-4", fen = "8/8/8/8/8/8/8/8 w - -")
        val occurrence =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "e4",
                playerColor = "WHITE",
                decisionTimeMs = null,
            )

        val page = PageImpl(listOf(occurrence), PageRequest.of(0, 10), 1)
        whenever(
            positionOccurrenceRepository.findLongDecisionOccurrences(
                any(),
                any(),
                any(),
                any(),
            ),
        ).thenReturn(page)

        assertFailsWith<IllegalArgumentException> {
            service.findLongDecisions(
                accountId = accountId,
                timeControl = TimeControl.BLITZ,
                thresholdSeconds = 5,
                page = 0,
                size = 10,
                principal = principal,
            )
        }
    }

    @Test
    fun `maps empty repository page to response with empty content and accurate pagination`() {
        val page = PageImpl(emptyList<PositionOccurrence>(), PageRequest.of(2, 20), 0)
        whenever(
            positionOccurrenceRepository.findLongDecisionOccurrences(
                any(),
                any(),
                any(),
                any(),
            ),
        ).thenReturn(page)

        val response =
            service.findLongDecisions(
                accountId = accountId,
                timeControl = TimeControl.BLITZ,
                thresholdSeconds = 10,
                page = 2,
                size = 20,
                principal = principal,
            )

        assertTrue(response.content.isEmpty())
        assertEquals(2, response.page)
        assertEquals(20, response.size)
        assertEquals(0, response.totalElements)
        assertEquals(0, response.totalPages)
        assertFalse(response.hasNext)
    }

    @Test
    fun `maps chessComUrl delegating game timeControl for daily games`() {
        val dailyGame =
            Game(
                chessAccount = account,
                platformGameId = "https://chess.com/game/daily/987654",
                pgn = "*",
                timeControl = "daily",
                whiteUsername = "tester",
                blackUsername = "opponent",
            )
        val occ =
            PositionOccurrence(
                game = dailyGame,
                position = Position(hash = "hash-daily", fen = "8/8/8/8/8/8/8/8 w - -"),
                chessAccount = account,
                plyNumber = 12,
                movePlayed = "d4",
                playerColor = "WHITE",
                decisionTimeMs = 60_000L,
            )
        whenever(positionOccurrenceRepository.findLongDecisionOccurrences(any(), any(), any(), any()))
            .thenReturn(PageImpl(listOf(occ)))
        whenever(chessComUrlBuilder.buildExactPlyUrl("https://chess.com/game/daily/987654", 12, "daily"))
            .thenReturn("https://www.chess.com/game/daily/987654?move=12")

        val response = service.findLongDecisions(accountId, TimeControl.RAPID, 30, 0, 10, principal)
        assertEquals("https://www.chess.com/game/daily/987654?move=12", response.content[0].chessComUrl)
        verify(chessComUrlBuilder).buildExactPlyUrl("https://chess.com/game/daily/987654", 12, "daily")
    }

    @Test
    fun `maps chessComUrl to null when builder returns null`() {
        val malformedGame =
            Game(
                chessAccount = account,
                platformGameId = "invalid-url",
                pgn = "*",
                timeControl = "blitz",
                whiteUsername = "tester",
                blackUsername = "opponent",
            )
        val occ =
            PositionOccurrence(
                game = malformedGame,
                position = Position(hash = "hash-inv", fen = "8/8/8/8/8/8/8/8 w - -"),
                chessAccount = account,
                plyNumber = 3,
                movePlayed = "c4",
                playerColor = "WHITE",
                decisionTimeMs = 45_000L,
            )
        whenever(positionOccurrenceRepository.findLongDecisionOccurrences(any(), any(), any(), any()))
            .thenReturn(PageImpl(listOf(occ)))
        whenever(chessComUrlBuilder.buildExactPlyUrl("invalid-url", 3, "blitz"))
            .thenReturn(null)

        val response = service.findLongDecisions(accountId, TimeControl.BLITZ, 30, 0, 10, principal)
        assertNull(response.content[0].chessComUrl)
    }
}
