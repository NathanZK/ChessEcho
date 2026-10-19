package com.chessecho.dto

import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.TimeControl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ImportGamesRequestTest {
    @Test
    fun `hasSelector accepts accountId alone and platform with username, rejects incomplete selectors`() {
        val accountOnly = ImportGamesRequest(accountId = UUID.randomUUID())
        assertTrue(accountOnly.hasSelector())

        val guestValid = ImportGamesRequest(platform = Platform.CHESS_COM, username = "hikaru")
        assertTrue(guestValid.hasSelector())

        val missingPlatform = ImportGamesRequest(platform = null, username = "hikaru")
        assertFalse(missingPlatform.hasSelector())

        val missingUsername = ImportGamesRequest(platform = Platform.CHESS_COM, username = "")
        assertFalse(missingUsername.hasSelector())

        val blankUsername = ImportGamesRequest(platform = Platform.CHESS_COM, username = "   ")
        assertFalse(blankUsername.hasSelector())

        val completelyEmpty = ImportGamesRequest()
        assertFalse(completelyEmpty.hasSelector())
    }

    @Test
    fun `canonicalTimeControls deduplicates and sorts time controls alphabetically`() {
        val request =
            ImportGamesRequest(
                timeControls = listOf(TimeControl.RAPID, TimeControl.BULLET, TimeControl.BLITZ, TimeControl.RAPID),
            )
        assertEquals("BLITZ,BULLET,RAPID", request.canonicalTimeControls())

        val emptyRequest = ImportGamesRequest(timeControls = emptyList())
        assertEquals("", emptyRequest.canonicalTimeControls())
    }

    @Test
    fun `normalizedPlatform uppercases and trims platform name, preserves null`() {
        val presentRequest = ImportGamesRequest(platform = Platform.CHESS_COM)
        assertEquals("CHESS_COM", presentRequest.normalizedPlatform())

        val nullRequest = ImportGamesRequest(platform = null)
        assertNull(nullRequest.normalizedPlatform())
    }

    @Test
    fun `normalizedUsername trims whitespace and converts empty or blank to null`() {
        val paddedRequest = ImportGamesRequest(username = "  magnuscarlsen  ")
        assertEquals("magnuscarlsen", paddedRequest.normalizedUsername())

        val emptyRequest = ImportGamesRequest(username = "")
        assertNull(emptyRequest.normalizedUsername())

        val blankRequest = ImportGamesRequest(username = "    ")
        assertNull(blankRequest.normalizedUsername())
    }

    @Test
    fun `normalizedFromDate and normalizedToDate trim whitespace and preserve null`() {
        val paddedDates = ImportGamesRequest(fromDate = " 2024-01 ", toDate = " 2024-06 ")
        assertEquals("2024-01", paddedDates.normalizedFromDate())
        assertEquals("2024-06", paddedDates.normalizedToDate())

        val nullDates = ImportGamesRequest(fromDate = null, toDate = null)
        assertNull(nullDates.normalizedFromDate())
        assertNull(nullDates.normalizedToDate())
    }

    @Test
    fun `isAuthenticatedForm distinguishes presence of accountId`() {
        val authenticated = ImportGamesRequest(accountId = UUID.randomUUID())
        assertTrue(authenticated.isAuthenticatedForm())

        val unauthenticated = ImportGamesRequest(accountId = null)
        assertFalse(unauthenticated.isAuthenticatedForm())
    }

    @Test
    fun `hasOrderedDateRange validates chronological order, equal months, and rejects inverted or malformed ranges`() {
        val bothNull = ImportGamesRequest(fromDate = null, toDate = null)
        assertTrue(bothNull.hasOrderedDateRange())

        val fromOnly = ImportGamesRequest(fromDate = "2024-01", toDate = null)
        assertTrue(fromOnly.hasOrderedDateRange())

        val toOnly = ImportGamesRequest(fromDate = null, toDate = "2024-06")
        assertTrue(toOnly.hasOrderedDateRange())

        val validChronological = ImportGamesRequest(fromDate = "2023-01", toDate = "2023-12")
        assertTrue(validChronological.hasOrderedDateRange())

        val equalMonths = ImportGamesRequest(fromDate = "2024-03", toDate = "2024-03")
        assertTrue(equalMonths.hasOrderedDateRange())

        val inverted = ImportGamesRequest(fromDate = "2024-07", toDate = "2024-02")
        assertFalse(inverted.hasOrderedDateRange())

        val malformedFrom = ImportGamesRequest(fromDate = "invalid", toDate = "2024-02")
        assertFalse(malformedFrom.hasOrderedDateRange())

        val malformedTo = ImportGamesRequest(fromDate = "2024-02", toDate = "2024-13")
        assertFalse(malformedTo.hasOrderedDateRange())
    }

    @Test
    fun `data class properties retain constructor parameters`() {
        val accountId = UUID.randomUUID()
        val request =
            ImportGamesRequest(
                accountId = accountId,
                platform = Platform.CHESS_COM,
                username = "player",
                timeControls = listOf(TimeControl.RAPID),
                playerColor = PlayerColor.WHITE,
                fromDate = "2024-01",
                toDate = "2024-06",
                multiPv = 3,
                maxEligibleGames = 10,
            )
        assertEquals(accountId, request.accountId)
        assertEquals(Platform.CHESS_COM, request.platform)
        assertEquals("player", request.username)
        assertEquals(listOf(TimeControl.RAPID), request.timeControls)
        assertEquals(PlayerColor.WHITE, request.playerColor)
        assertEquals("2024-01", request.fromDate)
        assertEquals("2024-06", request.toDate)
        assertEquals(3, request.multiPv)
        assertEquals(10, request.maxEligibleGames)
    }
}
