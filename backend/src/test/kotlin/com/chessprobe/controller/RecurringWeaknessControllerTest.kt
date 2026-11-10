package com.chessprobe.controller

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.DecisionSeverity
import com.chessprobe.domain.DiscoveryJobStatus
import com.chessprobe.domain.DiscoveryStage
import com.chessprobe.domain.MistakeSeverity
import com.chessprobe.domain.Platform
import com.chessprobe.domain.clustering.ClusterType
import com.chessprobe.dto.ClusterMemberPositionDto
import com.chessprobe.dto.ClusterMemberPositionsResponse
import com.chessprobe.dto.RecurringDiscoveryJobResponse
import com.chessprobe.dto.RecurringDiscoveryRequest
import com.chessprobe.dto.RecurringWeaknessResponse
import com.chessprobe.service.AccountOwnershipService
import com.chessprobe.service.auth.AuthenticatedPrincipal
import com.chessprobe.service.weakness.RecurringWeaknessDiscoveryService
import com.chessprobe.web.UnauthenticatedException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
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
                clusterType = ClusterType.STRUCTURAL,
                recommendationPriority = 13.3,
                representativeFen = "fen1",
                memberPositionCount = 12,
                totalOccurrences = 15,
                distinctGamesCount = 3,
                mistakeCount = 5,
                mistakeFrequency = 33.3,
                blunderRate = 13.3,
                evaluatedOccurrences = 15,
                unevaluatedOccurrences = 0,
                evalLossDistribution = mapOf("CRITICAL" to 2),
                evalLossPercentages = mapOf("CRITICAL" to 13.3),
                timingProfile = mapOf("BLITZ_DECISION" to 15),
                formatBreakdown = mapOf("BLITZ" to 15),
            )
        `when`(discoveryService.getLatestRecurringWeaknesses(testAccount)).thenReturn(listOf(weakness))

        val response = controller.getRecurringWeaknesses(null, "testplayer", null)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(1, response.body?.size)
        assertEquals(weakness.clusterId, response.body?.get(0)?.clusterId)
    }

    @Test
    fun `GET cluster positions returns 200 OK with positions for authenticated caller`() {
        val clusterId = UUID.randomUUID()
        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)
        val expectedResponse =
            ClusterMemberPositionsResponse(
                page = 0,
                size = 20,
                totalElements = 1L,
                totalPages = 1,
                hasNext = false,
                positions =
                    listOf(
                        ClusterMemberPositionDto(
                            positionId = UUID.randomUUID(),
                            fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
                            playerColor = "BLACK",
                            movePlayed = "e5",
                            targetMove = "c5",
                            evalLoss = 0.5,
                            mistakeSeverity = MistakeSeverity.MINOR,
                            decisionTimeMs = 4500L,
                            decisionSeverity = DecisionSeverity.NO_CONCERN,
                            timeControl = "BLITZ",
                            gameId = UUID.randomUUID(),
                            platformGameId = "chesscom_12345",
                            plyNumber = 2,
                            moveNumber = 1,
                        ),
                    ),
            )

        `when`(discoveryService.getClusterAccountId(clusterId)).thenReturn(testAccount.id)
        `when`(accountOwnershipService.resolvePrivateRead(testAccount.id, principal)).thenReturn(testAccount)
        `when`(discoveryService.getClusterPositions(clusterId, testAccount, 0, 20, false)).thenReturn(expectedResponse)

        val response = controller.getClusterPositions(clusterId, 0, 20, false, null, principal)
        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body
        assertEquals(1L, body?.totalElements)
        assertEquals("e5", body?.positions?.firstOrNull()?.movePlayed)
    }

    @Test
    fun `GET cluster positions throws UnauthenticatedException when unauthenticated`() {
        val clusterId = UUID.randomUUID()

        assertThrows<UnauthenticatedException> {
            controller.getClusterPositions(clusterId, 0, 20, false, null, null)
        }
    }

    @Test
    fun `GET cluster positions throws ResponseStatusException with 400 when page or size is invalid`() {
        val clusterId = UUID.randomUUID()
        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)

        val exNegativePage =
            assertThrows<ResponseStatusException> {
                controller.getClusterPositions(clusterId, -1, 20, false, null, principal)
            }
        assertEquals(HttpStatus.BAD_REQUEST, exNegativePage.statusCode)

        val exZeroSize =
            assertThrows<ResponseStatusException> {
                controller.getClusterPositions(clusterId, 0, 0, false, null, principal)
            }
        assertEquals(HttpStatus.BAD_REQUEST, exZeroSize.statusCode)

        val exOversize =
            assertThrows<ResponseStatusException> {
                controller.getClusterPositions(clusterId, 0, 101, false, null, principal)
            }
        assertEquals(HttpStatus.BAD_REQUEST, exOversize.statusCode)
    }

    @Test
    fun `GET cluster positions throws ResponseStatusException with 404 when cluster not found`() {
        val clusterId = UUID.randomUUID()
        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)

        `when`(discoveryService.getClusterAccountId(clusterId)).thenReturn(null)

        val ex =
            assertThrows<ResponseStatusException> {
                controller.getClusterPositions(clusterId, 0, 20, false, null, principal)
            }
        assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `GET cluster positions forwards playableOnly parameter`() {
        val clusterId = UUID.randomUUID()
        val principal = AuthenticatedPrincipal(appUserId = UUID.randomUUID(), devPrincipal = false)
        val expectedResponse =
            ClusterMemberPositionsResponse(
                page = 0,
                size = 100,
                totalElements = 0L,
                totalPages = 0,
                hasNext = false,
                positions = emptyList(),
            )

        `when`(discoveryService.getClusterAccountId(clusterId)).thenReturn(testAccount.id)
        `when`(accountOwnershipService.resolvePrivateRead(testAccount.id, principal)).thenReturn(testAccount)
        `when`(discoveryService.getClusterPositions(clusterId, testAccount, 0, 100, true)).thenReturn(expectedResponse)

        val response = controller.getClusterPositions(clusterId, 0, 100, true, null, principal)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(0L, response.body?.totalElements)
    }
}
