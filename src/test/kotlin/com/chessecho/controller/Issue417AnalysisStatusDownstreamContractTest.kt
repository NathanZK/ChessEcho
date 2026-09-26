package com.chessecho.controller

import com.chessecho.domain.AsyncJob
import com.chessecho.repository.ArchiveDerivedProcessingRepository
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.service.GameImportService
import com.chessecho.service.auth.IdentitySessionService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #417: Downstream API contract for analysis status.
 *
 * Consumers must distinguish COMPLETED from FAILED and ANALYZING.
 * If partial analysis data exists after failure, semantics must be explicit.
 *
 * This test validates that the API properly exposes analysisStatus and
 * that downstream code can distinguish the different analysis phases.
 */
@WebMvcTest(GameImportController::class)
@Import(
    com.chessecho.web.SessionAuthenticationFilter::class,
)
@EnableConfigurationProperties(com.chessecho.config.SessionCookieProperties::class)
class Issue417AnalysisStatusDownstreamContractTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockBean
    private lateinit var gameImportService: GameImportService

    @MockBean
    private lateinit var asyncJobRepository: AsyncJobRepository

    @MockBean
    private lateinit var archiveDerivedProcessingRepository: ArchiveDerivedProcessingRepository

    @MockBean
    private lateinit var identitySessionService: IdentitySessionService

    /**
     * T1: analysisStatus=COMPLETED is properly exposed in API response.
     *
     * Clients must be able to query the job status and see that analysis
     * completed successfully.
     */
    @Test
    fun `analysisStatus=COMPLETED is properly exposed in job status API response`() {
        val jobId = UUID.randomUUID()
        val job =
            AsyncJob(
                id = jobId,
                username = "hikaru",
                platform = "CHESS_COM",
                status = "COMPLETED",
                gamesImported = 100,
                gamesSkipped = 10,
                gamesProcessed = 110,
                analysisStatus = "COMPLETED",
            )
        whenever(asyncJobRepository.findById(jobId)).thenReturn(Optional.of(job))

        val result =
            mockMvc.get("/api/jobs/$jobId")
                .andExpect { status { isOk() } }
                .andReturn()

        val response = objectMapper.readTree(result.response.contentAsString)
        assertEquals(
            "COMPLETED",
            response.path("analysisStatus").asText(),
            "API must expose analysisStatus field with value COMPLETED",
        )
        assertEquals(
            "COMPLETED",
            response.path("status").asText(),
            "status must be COMPLETED when import succeeds",
        )
    }

    /**
     * T2: analysisStatus=FAILED is properly exposed and distinguishable from COMPLETED.
     *
     * Clients must be able to identify that analysis failed, which means
     * the analysis corpus is incomplete/partial.
     */
    @Test
    fun `analysisStatus=FAILED is properly exposed and distinguishable from COMPLETED`() {
        val jobId = UUID.randomUUID()
        val job =
            AsyncJob(
                id = jobId,
                username = "hikaru",
                platform = "CHESS_COM",
                status = "COMPLETED",
                gamesImported = 100,
                gamesSkipped = 10,
                gamesProcessed = 110,
                analysisStatus = "FAILED",
            )
        whenever(asyncJobRepository.findById(jobId)).thenReturn(Optional.of(job))

        val result =
            mockMvc.get("/api/jobs/$jobId")
                .andExpect { status { isOk() } }
                .andReturn()

        val response = objectMapper.readTree(result.response.contentAsString)
        assertEquals(
            "FAILED",
            response.path("analysisStatus").asText(),
            "API must expose analysisStatus=FAILED when analysis failed",
        )
        assertEquals(
            "COMPLETED",
            response.path("status").asText(),
            "status must be COMPLETED even when analysis failed (import succeeded)",
        )
        assertEquals(
            100,
            response.path("gamesImported").asInt(),
            "Import counters must be preserved after analysis failure",
        )
        assertTrue(
            response.path("errorMessage").isNull,
            "errorMessage must be null (import succeeded)",
        )
    }

    /**
     * T3: analysisStatus=ANALYZING is properly exposed and distinguishable.
     *
     * Clients must be able to identify when analysis is still in progress,
     * which means the analysis corpus is definitely incomplete.
     */
    @Test
    fun `analysisStatus=ANALYZING is properly exposed and distinguishable`() {
        val jobId = UUID.randomUUID()
        val job =
            AsyncJob(
                id = jobId,
                username = "hikaru",
                platform = "CHESS_COM",
                status = "COMPLETED",
                gamesImported = 100,
                gamesSkipped = 10,
                gamesProcessed = 110,
                analysisStatus = "ANALYZING",
            )
        whenever(asyncJobRepository.findById(jobId)).thenReturn(Optional.of(job))

        val result =
            mockMvc.get("/api/jobs/$jobId")
                .andExpect { status { isOk() } }
                .andReturn()

        val response = objectMapper.readTree(result.response.contentAsString)
        assertEquals(
            "ANALYZING",
            response.path("analysisStatus").asText(),
            "API must expose analysisStatus=ANALYZING",
        )
        assertEquals(
            "COMPLETED",
            response.path("status").asText(),
            "status must be COMPLETED (import finished before analysis started)",
        )
    }

    /**
     * T4: analysisStatus=NOT_STARTED is properly exposed.
     *
     * Clients must be able to identify that analysis has not yet started.
     */
    @Test
    fun `analysisStatus=NOT_STARTED is properly exposed`() {
        val jobId = UUID.randomUUID()
        val job =
            AsyncJob(
                id = jobId,
                username = "hikaru",
                platform = "CHESS_COM",
                status = "QUEUED",
                analysisStatus = "NOT_STARTED",
            )
        whenever(asyncJobRepository.findById(jobId)).thenReturn(Optional.of(job))

        val result =
            mockMvc.get("/api/jobs/$jobId")
                .andExpect { status { isOk() } }
                .andReturn()

        val response = objectMapper.readTree(result.response.contentAsString)
        assertEquals(
            "NOT_STARTED",
            response.path("analysisStatus").asText(),
            "API must expose analysisStatus=NOT_STARTED for queued jobs",
        )
    }

    /**
     * T5: Downstream must not treat FAILED or ANALYZING as complete.
     *
     * This test documents the contract: clients receiving analysisStatus=FAILED
     * or analysisStatus=ANALYZING must understand that the analysis corpus is
     * incomplete and may contain only partial data.
     */
    @Test
    fun `downstream contract - FAILED and ANALYZING mean incomplete analysis corpus`() {
        // This is validated through frontend test behavior and documented
        // in the API response structure.

        val failedJob =
            AsyncJob(
                id = UUID.randomUUID(),
                username = "player1",
                platform = "CHESS_COM",
                status = "COMPLETED",
                analysisStatus = "FAILED",
            )
        val analyzingJob =
            AsyncJob(
                id = UUID.randomUUID(),
                username = "player2",
                platform = "CHESS_COM",
                status = "COMPLETED",
                analysisStatus = "ANALYZING",
            )
        whenever(asyncJobRepository.findById(failedJob.id))
            .thenReturn(Optional.of(failedJob))
        whenever(asyncJobRepository.findById(analyzingJob.id))
            .thenReturn(Optional.of(analyzingJob))

        // Verify both jobs expose their incomplete state
        mockMvc.get("/api/jobs/${failedJob.id}")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.analysisStatus") { value("FAILED") } }

        mockMvc.get("/api/jobs/${analyzingJob.id}")
            .andExpect { status { isOk() } }
            .andExpect { jsonPath("$.analysisStatus") { value("ANALYZING") } }

        // Clients must implement logic to distinguish COMPLETED from FAILED/ANALYZING
        // and adjust their behavior (e.g., suppress weakness/puzzle features,
        // show warning messages, etc.)
    }

    /**
     * Regression test: Import failure status is still exposed.
     *
     * Verify that when import fails, both status and analysisStatus are
     * properly exposed.
     */
    @Test
    fun `regression - import failure exposes status=FAILED with error message`() {
        val jobId = UUID.randomUUID()
        val job =
            AsyncJob(
                id = jobId,
                username = "hikaru",
                platform = "CHESS_COM",
                status = "FAILED",
                analysisStatus = "NOT_STARTED",
                errorMessage = "Chess.com API error: rate limited",
            )
        whenever(asyncJobRepository.findById(jobId)).thenReturn(Optional.of(job))

        val result =
            mockMvc.get("/api/jobs/$jobId")
                .andExpect { status { isOk() } }
                .andReturn()

        val response = objectMapper.readTree(result.response.contentAsString)
        assertEquals(
            "FAILED",
            response.path("status").asText(),
            "status must be FAILED",
        )
        assertEquals(
            "NOT_STARTED",
            response.path("analysisStatus").asText(),
            "analysisStatus must be NOT_STARTED",
        )
        assertTrue(
            response.path("errorMessage").asText().contains("API error"),
            "error message must be present",
        )
    }
}
