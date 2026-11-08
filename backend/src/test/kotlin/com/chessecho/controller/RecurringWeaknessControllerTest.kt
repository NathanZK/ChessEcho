package com.chessecho.controller

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.DiscoveryJobStatus
import com.chessecho.domain.DiscoveryStage
import com.chessecho.domain.Platform
import com.chessecho.dto.RecurringDiscoveryJobResponse
import com.chessecho.dto.RecurringDiscoveryRequest
import com.chessecho.dto.RecurringWeaknessResponse
import com.chessecho.service.AccountOwnershipService
import com.chessecho.service.weakness.RecurringWeaknessDiscoveryService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID

class RecurringWeaknessControllerTest {
    private val discoveryService = mock(RecurringWeaknessDiscoveryService::class.java)
    private val accountOwnershipService = mock(AccountOwnershipService::class.java)

    private lateinit var controller: RecurringWeaknessController

    private val testAccount =
        ChessAccount(
            id = UUID.randomUUID(),
            platform = "CHESS_COM",
            username = "testplayer",
        )

    @BeforeEach
    fun setup() {
        controller =
            RecurringWeaknessController(
                discoveryService = discoveryService,
                accountOwnershipService = accountOwnershipService,
            )
    }

    @Test
    fun `POST discover returns 202 Accepted on valid request`() {
        val request = RecurringDiscoveryRequest(username = "testplayer", timeControls = listOf("RAPID"))
        `when`(accountOwnershipService.resolvePrivateRead(Platform.CHESS_COM, "testplayer", null)).thenReturn(testAccount)

        val jobResponse =
            RecurringDiscoveryJobResponse(
                jobId = UUID.randomUUID(),
                accountId = testAccount.id,
                status = DiscoveryJobStatus.QUEUED.name,
                progressPercent = 0,
                stage = DiscoveryStage.INITIALIZING.name,
                errorMessage = null,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        `when`(discoveryService.triggerDiscovery(testAccount, request)).thenReturn(jobResponse)

        val response = controller.triggerDiscovery(request, null)
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        assertEquals(jobResponse.jobId, response.body?.jobId)
    }

    @Test
    fun `POST discover throws IllegalArgumentException on missing account credentials`() {
        val request = RecurringDiscoveryRequest(accountId = null, username = null)

        assertThrows<IllegalArgumentException> {
            controller.triggerDiscovery(request, null)
        }
    }

    @Test
    fun `GET job status returns 200 OK for existing job`() {
        val jobId = UUID.randomUUID()
        val jobResponse =
            RecurringDiscoveryJobResponse(
                jobId = jobId,
                accountId = testAccount.id,
                status = DiscoveryJobStatus.PROCESSING.name,
                progressPercent = 40,
                stage = DiscoveryStage.EVALUATION.name,
                errorMessage = null,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        `when`(discoveryService.getJob(jobId)).thenReturn(jobResponse)

        val response = controller.getJobStatus(jobId, null)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(DiscoveryJobStatus.PROCESSING.name, response.body?.status)
    }

    @Test
    fun `GET job status throws NoSuchElementException when job missing`() {
        val jobId = UUID.randomUUID()
        `when`(discoveryService.getJob(jobId)).thenReturn(null)

        assertThrows<NoSuchElementException> {
            controller.getJobStatus(jobId, null)
        }
    }

    @Test
    fun `POST cancel job returns 200 OK with cancelled job response`() {
        val jobId = UUID.randomUUID()
        val jobResponse =
            RecurringDiscoveryJobResponse(
                jobId = jobId,
                accountId = testAccount.id,
                status = DiscoveryJobStatus.CANCELLED.name,
                progressPercent = 40,
                stage = DiscoveryStage.CANCELLED.name,
                errorMessage = null,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        `when`(discoveryService.getJob(jobId)).thenReturn(jobResponse)
        `when`(accountOwnershipService.resolvePrivateRead(Platform.CHESS_COM, "testplayer", null)).thenReturn(testAccount)
        `when`(discoveryService.cancelJob(jobId, testAccount)).thenReturn(jobResponse)

        val response = controller.cancelJob(jobId, null, "testplayer", null)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(DiscoveryJobStatus.CANCELLED.name, response.body?.status)
    }

    @Test
    fun `GET recurring weaknesses returns 200 OK with weakness list`() {
        `when`(accountOwnershipService.resolvePrivateRead(Platform.CHESS_COM, "testplayer", null)).thenReturn(testAccount)
        val weakness =
            RecurringWeaknessResponse(
                clusterId = UUID.randomUUID(),
                representativeFen = "fen1",
                memberPositionCount = 12,
                totalOccurrences = 15,
                distinctGamesCount = 3,
                mistakeCount = 5,
                mistakeFrequency = 33.3,
                blunderRate = 13.3,
                evalLossDistribution = mapOf("CRITICAL" to 2),
                evalLossPercentages = mapOf("CRITICAL" to 13.3),
                timingProfile = mapOf("BLITZ_DECISION" to 15),
                formatBreakdown = mapOf("BLITZ" to 15),
                practiceContinuationUrl = "/api/puzzles/practice?fen=fen1",
            )
        `when`(discoveryService.getLatestRecurringWeaknesses(testAccount)).thenReturn(listOf(weakness))

        val response = controller.getRecurringWeaknesses(null, "testplayer", null)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(1, response.body?.size)
        assertEquals(weakness.clusterId, response.body?.get(0)?.clusterId)
    }
}
