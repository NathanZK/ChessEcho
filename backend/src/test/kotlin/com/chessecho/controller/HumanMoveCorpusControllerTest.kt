package com.chessecho.controller

import com.chessecho.domain.HumanMoveCorpusRunStatus
import com.chessecho.dto.HumanMoveCorpusCheckpointRequest
import com.chessecho.dto.HumanMoveCorpusCheckpointResponse
import com.chessecho.dto.HumanMoveCorpusCheckpointRow
import com.chessecho.dto.HumanMoveCorpusRunRequest
import com.chessecho.dto.HumanMoveCorpusRunResponse
import com.chessecho.service.HumanMoveBfsService
import com.chessecho.service.HumanMoveCorpusCheckpointService
import com.chessecho.service.HumanMoveCorpusIntegrityException
import com.chessecho.service.HumanMoveCorpusService
import com.chessecho.service.HumanMoveDistributionFinalizationService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID

@WebMvcTest(HumanMoveCorpusController::class)
class HumanMoveCorpusControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var humanMoveCorpusService: HumanMoveCorpusService

    @MockBean
    lateinit var humanMoveCorpusCheckpointService: HumanMoveCorpusCheckpointService

    @MockBean
    lateinit var humanMoveBfsService: HumanMoveBfsService

    @MockBean
    lateinit var humanMoveDistributionFinalizationService: HumanMoveDistributionFinalizationService

    private val base = "/api/admin/human-move-distribution/corpus-runs"
    private val runId: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")

    private fun runResponse(status: HumanMoveCorpusRunStatus = HumanMoveCorpusRunStatus.COMPLETED) =
        HumanMoveCorpusRunResponse(
            runId = runId,
            ratingBand = "1000-1200",
            status = status,
            seedPlayers = listOf("p1"),
            excludedPlayers = listOf("x"),
            maxQualifyingGames = 10,
            maxGamesPerPlayer = 100,
            maxPlayers = null,
            maxDepth = null,
            batchSize = 5000,
            algorithmVersion = "human-move-corpus-v1",
            sourceRevision = "abc123",
            sourceRevisionProvenance = "SELF_REPORTED",
            requestSha256 = "a".repeat(64),
            committedFrontier = 10,
            rejectedGameCount = 1,
            archiveFetchFailureCount = 0,
            stopReason = "MAX_QUALIFYING_GAMES",
            failureDetails = null,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:01:00Z"),
            finishedAt = Instant.parse("2026-01-01T00:01:00Z"),
        )

    private fun checkpointResponse() =
        HumanMoveCorpusCheckpointResponse(
            runId = runId,
            ratingBand = "1000-1200",
            runStatus = HumanMoveCorpusRunStatus.RUNNING,
            requestedN = 7,
            committedFrontier = 9,
            minObservations = 5,
            positionsEvaluated = 3,
            positionsRemoved = 2,
            positionsRetained = 1,
            rowsRetained = 1,
            observationsRetained = 6,
            rows =
                listOf(
                    HumanMoveCorpusCheckpointRow(
                        positionId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        positionHash = "hash-1",
                        movePlayed = "e4",
                        observationCount = 6,
                    ),
                ),
            distributionSha256 = "b".repeat(64),
            calculatedAt = Instant.parse("2026-01-02T00:00:00Z"),
            calculationVersion = "human-move-corpus-checkpoint-v1",
        )

    private fun createBody(bounds: Map<String, Any?>): String =
        objectMapper.writeValueAsString(
            mapOf(
                "ratingBand" to "1000-1200",
                "seedPlayers" to listOf("p1"),
                "sourceRevision" to "abc123",
            ) + bounds,
        )

    @Test
    fun `POST corpus-runs binds every non-empty combination of optional bounds`() {
        whenever(humanMoveCorpusService.runCorpus(any())).thenReturn(runResponse())
        val cases =
            listOf(
                mapOf("maxPlayers" to 2) to Triple(2, null, null),
                mapOf("maxDepth" to 1) to Triple(null, 1, null),
                mapOf("maxQualifyingGames" to 5) to Triple(null, null, 5),
                mapOf("maxPlayers" to 2, "maxDepth" to 1) to Triple(2, 1, null),
                mapOf("maxPlayers" to 2, "maxQualifyingGames" to 5) to Triple(2, null, 5),
                mapOf("maxDepth" to 1, "maxQualifyingGames" to 5) to Triple(null, 1, 5),
                mapOf("maxPlayers" to 2, "maxDepth" to 1, "maxQualifyingGames" to 5) to Triple(2, 1, 5),
                mapOf("maxPlayers" to null, "maxDepth" to 1, "maxQualifyingGames" to null) to Triple(null, 1, null),
            )

        cases.forEach { (bounds, _) ->
            mockMvc.post(base) {
                contentType = MediaType.APPLICATION_JSON
                content = createBody(bounds)
            }.andExpect { status { isOk() } }
        }

        val captor = argumentCaptor<HumanMoveCorpusRunRequest>()
        verify(humanMoveCorpusService, times(cases.size)).runCorpus(captor.capture())
        cases.zip(captor.allValues).forEach { (case, request) ->
            val (maxPlayers, maxDepth, maxQualifyingGames) = case.second
            assertEquals(maxPlayers, request.maxPlayers, "maxPlayers for ${case.first}")
            assertEquals(maxDepth, request.maxDepth, "maxDepth for ${case.first}")
            assertEquals(maxQualifyingGames, request.maxQualifyingGames, "maxQualifyingGames for ${case.first}")
            assertEquals("abc123", request.sourceRevision)
            assertEquals(100, request.maxGamesPerPlayer)
            assertEquals(emptyList<String>(), request.excludedPlayers)
        }
    }

    @Test
    fun `POST corpus-runs returns run identity, configuration, status, and frontier`() {
        whenever(humanMoveCorpusService.runCorpus(any())).thenReturn(runResponse())

        mockMvc.post(base) {
            contentType = MediaType.APPLICATION_JSON
            content = createBody(mapOf("maxQualifyingGames" to 10, "excludedPlayers" to listOf("x")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.runId") { value(runId.toString()) }
            jsonPath("$.ratingBand") { value("1000-1200") }
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.committedFrontier") { value(10) }
            jsonPath("$.rejectedGameCount") { value(1) }
            jsonPath("$.archiveFetchFailureCount") { value(0) }
            jsonPath("$.stopReason") { value("MAX_QUALIFYING_GAMES") }
            jsonPath("$.algorithmVersion") { value("human-move-corpus-v1") }
            jsonPath("$.sourceRevision") { value("abc123") }
            jsonPath("$.sourceRevisionProvenance") { value("SELF_REPORTED") }
            jsonPath("$.requestSha256") { value("a".repeat(64)) }
            jsonPath("$.seedPlayers[0]") { value("p1") }
            jsonPath("$.excludedPlayers[0]") { value("x") }
            jsonPath("$.maxQualifyingGames") { value(10) }
        }
    }

    @Test
    fun `POST corpus-runs rejects a missing sourceRevision without invoking the service`() {
        mockMvc.post(base) {
            contentType = MediaType.APPLICATION_JSON
            content =
                objectMapper.writeValueAsString(
                    mapOf("ratingBand" to "1000-1200", "seedPlayers" to listOf("p1"), "maxDepth" to 1),
                )
        }.andExpect { status { isBadRequest() } }

        verify(humanMoveCorpusService, never()).runCorpus(any())
    }

    @Test
    fun `POST corpus-runs maps service validation failures such as all-omitted bounds to 400`() {
        whenever(humanMoveCorpusService.runCorpus(any()))
            .thenThrow(IllegalArgumentException("At least one BFS bound must be supplied"))

        mockMvc.post(base) {
            contentType = MediaType.APPLICATION_JSON
            content = createBody(emptyMap())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.details[0]") { value("At least one BFS bound must be supplied") }
        }
    }

    @Test
    fun `GET corpus-runs lists runs and GET by id returns one run`() {
        whenever(humanMoveCorpusService.listRuns()).thenReturn(listOf(runResponse(HumanMoveCorpusRunStatus.RUNNING)))
        whenever(humanMoveCorpusService.getRun(runId)).thenReturn(runResponse(HumanMoveCorpusRunStatus.FAILED))

        mockMvc.get(base).andExpect {
            status { isOk() }
            jsonPath("$[0].runId") { value(runId.toString()) }
            jsonPath("$[0].status") { value("RUNNING") }
        }
        mockMvc.get("$base/$runId").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("FAILED") }
        }
    }

    @Test
    fun `GET unknown corpus run returns 404`() {
        val unknown = UUID.randomUUID()
        whenever(humanMoveCorpusService.getRun(unknown)).thenThrow(NoSuchElementException("Corpus run $unknown not found"))

        mockMvc.get("$base/$unknown").andExpect { status { isNotFound() } }
    }

    @Test
    fun `POST checkpoints binds N, defaults minObservations to 5, and returns the evidence shape`() {
        whenever(humanMoveCorpusCheckpointService.calculate(eq(runId), any())).thenReturn(checkpointResponse())

        mockMvc.post("$base/$runId/checkpoints") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"qualifyingGames": 7}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.runId") { value(runId.toString()) }
            jsonPath("$.ratingBand") { value("1000-1200") }
            jsonPath("$.runStatus") { value("RUNNING") }
            jsonPath("$.requestedN") { value(7) }
            jsonPath("$.committedFrontier") { value(9) }
            jsonPath("$.minObservations") { value(5) }
            jsonPath("$.positionsEvaluated") { value(3) }
            jsonPath("$.positionsRemoved") { value(2) }
            jsonPath("$.positionsRetained") { value(1) }
            jsonPath("$.rowsRetained") { value(1) }
            jsonPath("$.observationsRetained") { value(6) }
            jsonPath("$.rows[0].positionHash") { value("hash-1") }
            jsonPath("$.rows[0].movePlayed") { value("e4") }
            jsonPath("$.rows[0].observationCount") { value(6) }
            jsonPath("$.distributionSha256") { value("b".repeat(64)) }
            jsonPath("$.calculationVersion") { value("human-move-corpus-checkpoint-v1") }
            jsonPath("$.calculatedAt") { exists() }
        }

        val captor = argumentCaptor<HumanMoveCorpusCheckpointRequest>()
        verify(humanMoveCorpusCheckpointService).calculate(eq(runId), captor.capture())
        assertEquals(7, captor.firstValue.qualifyingGames)
        assertEquals(5, captor.firstValue.minObservations)
    }

    @Test
    fun `POST checkpoints rejects missing N without invoking the service`() {
        mockMvc.post("$base/$runId/checkpoints") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"minObservations": 5}"""
        }.andExpect { status { isBadRequest() } }

        verifyNoInteractions(humanMoveCorpusCheckpointService)
    }

    @Test
    fun `POST checkpoints maps invalid N and beyond-frontier requests to 400`() {
        whenever(humanMoveCorpusCheckpointService.calculate(eq(runId), any()))
            .thenThrow(IllegalArgumentException("qualifyingGames (11) exceeds committed frontier (10)"))

        mockMvc.post("$base/$runId/checkpoints") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"qualifyingGames": 11}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.details[0]") { value("qualifyingGames (11) exceeds committed frontier (10)") }
        }
    }

    @Test
    fun `POST checkpoints for an unknown run returns 404`() {
        whenever(humanMoveCorpusCheckpointService.calculate(eq(runId), any()))
            .thenThrow(NoSuchElementException("Corpus run $runId not found"))

        mockMvc.post("$base/$runId/checkpoints") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"qualifyingGames": 1}"""
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `POST checkpoints maps a failed integrity check to 409 CORPUS_INTEGRITY_VIOLATION`() {
        whenever(humanMoveCorpusCheckpointService.calculate(eq(runId), any()))
            .thenThrow(HumanMoveCorpusIntegrityException("ordinal gap in prefix 1..3"))

        mockMvc.post("$base/$runId/checkpoints") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"qualifyingGames": 3}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error") { value("CORPUS_INTEGRITY_VIOLATION") }
            jsonPath("$.details[0]") { value("ordinal gap in prefix 1..3") }
        }
    }

    @Test
    fun `corpus endpoints never invoke legacy BFS or destructive finalization`() {
        whenever(humanMoveCorpusService.runCorpus(any())).thenReturn(runResponse())
        whenever(humanMoveCorpusCheckpointService.calculate(eq(runId), any())).thenReturn(checkpointResponse())

        mockMvc.post(base) {
            contentType = MediaType.APPLICATION_JSON
            content = createBody(mapOf("maxDepth" to 1))
        }.andExpect { status { isOk() } }
        mockMvc.post("$base/$runId/checkpoints") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"qualifyingGames": 7}"""
        }.andExpect { status { isOk() } }

        verifyNoInteractions(humanMoveBfsService)
        verifyNoInteractions(humanMoveDistributionFinalizationService)
    }
}
