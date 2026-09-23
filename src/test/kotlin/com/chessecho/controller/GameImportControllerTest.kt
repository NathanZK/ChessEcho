package com.chessecho.controller

import com.chessecho.domain.ArchiveDerivedStatus
import com.chessecho.domain.AsyncJob
import com.chessecho.domain.ChessAccount
import com.chessecho.repository.ArchiveDerivedProcessingRepository
import com.chessecho.repository.ArchiveDerivedStatusView
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.service.ActiveImportJobException
import com.chessecho.service.GameImportService
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.service.auth.IdentitySessionService
import com.chessecho.web.SessionAuthenticationFilter
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@WebMvcTest(GameImportController::class)
@Import(
    SessionAuthenticationFilter::class,
)
@EnableConfigurationProperties(com.chessecho.config.SessionCookieProperties::class)
class GameImportControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockBean
    lateinit var gameImportService: GameImportService

    @MockBean
    lateinit var asyncJobRepository: AsyncJobRepository

    @MockBean
    lateinit var archiveDerivedProcessingRepository: ArchiveDerivedProcessingRepository

    @MockBean
    lateinit var identitySessionService: IdentitySessionService

    private val validRequest =
        mapOf(
            "username" to "hikaru",
            "platform" to "CHESS_COM",
            "timeControls" to listOf("RAPID", "BLITZ"),
            "playerColor" to "BOTH",
        )

    @Test
    fun `POST guest import with a valid session is rejected before lookup with ACCOUNT_SELECTION_REQUIRED`() {
        whenever(identitySessionService.resolveSession("good-secret"))
            .thenReturn(AuthenticatedPrincipal(UUID.randomUUID(), devPrincipal = false))

        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"platform":"CHESS_COM","username":"caseplayer","timeControls":["BLITZ"],"playerColor":"WHITE"}"""
            cookie(Cookie("CHESSECHO_SESSION", "good-secret"), Cookie("XSRF-TOKEN", "csrf-1"))
            header("X-XSRF-TOKEN", "csrf-1")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("ACCOUNT_SELECTION_REQUIRED") }
        }
    }

    @Test
    fun `POST games import returns 202 with jobId on valid request`() {
        val job = AsyncJob(username = "hikaru", platform = "CHESS_COM")
        whenever(gameImportService.createImportJob(any())).thenReturn(job)

        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(validRequest)
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.jobId") { exists() }
            jsonPath("$.status") { value("QUEUED") }
        }
    }

    @Test
    fun `POST games import returns 400 when username is blank`() {
        val request = validRequest + ("username" to "")

        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("VALIDATION_ERROR") }
            jsonPath("$.details[0]") { exists() }
        }
    }

    @Test
    fun `POST games import returns 400 when timeControls is empty`() {
        val request = validRequest + ("timeControls" to emptyList<String>())

        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("VALIDATION_ERROR") }
        }
    }

    @Test
    fun `POST games import accepts positive multiPv and exposes it in the queued job`() {
        val constructor = requireNotNull(AsyncJob::class.primaryConstructor)
        val multiPvParameter =
            requireNotNull(constructor.parameters.singleOrNull { it.name == "analysisMultiPv" }) {
                "AsyncJob must expose analysisMultiPv as durable configuration"
            }
        val job =
            constructor.callBy(
                mapOf(
                    requireNotNull(constructor.parameters.single { it.name == "username" }) to "hikaru",
                    requireNotNull(constructor.parameters.single { it.name == "platform" }) to "CHESS_COM",
                    multiPvParameter to 1,
                ),
            )
        whenever(gameImportService.createImportJob(any())).thenReturn(job)

        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(validRequest + ("multiPv" to 1))
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.multiPv") { value(1) }
        }
    }

    @Test
    fun `POST games import rejects non-positive multiPv`() {
        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(validRequest + ("multiPv" to 0))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("VALIDATION_ERROR") }
        }
    }

    @Test
    fun `POST games import accepts positive maxEligibleGames and exposes it in the queued job`() {
        val constructor = requireNotNull(AsyncJob::class.primaryConstructor)
        val maxEligibleGamesParameter =
            requireNotNull(constructor.parameters.singleOrNull { it.name == "maxEligibleGames" }) {
                "AsyncJob must expose maxEligibleGames as durable configuration"
            }
        val job =
            constructor.callBy(
                mapOf(
                    requireNotNull(constructor.parameters.single { it.name == "username" }) to "hikaru",
                    requireNotNull(constructor.parameters.single { it.name == "platform" }) to "CHESS_COM",
                    maxEligibleGamesParameter to 500,
                ),
            )
        whenever(gameImportService.createImportJob(any())).thenReturn(job)

        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(validRequest + ("maxEligibleGames" to 500))
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.maxEligibleGames") { value(500) }
        }
    }

    @Test
    fun `POST games import omits maxEligibleGames from the response when not supplied`() {
        val job = AsyncJob(username = "hikaru", platform = "CHESS_COM")
        whenever(gameImportService.createImportJob(any())).thenReturn(job)

        val result =
            mockMvc.post("/api/games/import") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(validRequest)
            }.andExpect {
                status { isAccepted() }
            }.andReturn()

        val response = objectMapper.readTree(result.response.contentAsString)
        assertTrue(response.path("maxEligibleGames").isMissingNode)
    }

    @Test
    fun `POST games import rejects non-positive maxEligibleGames`() {
        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(validRequest + ("maxEligibleGames" to 0))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("VALIDATION_ERROR") }
        }
    }

    @Test
    fun `POST games import returns 400 when playerColor is invalid`() {
        val request = validRequest + ("playerColor" to "invalid_color")

        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("VALIDATION_ERROR") }
        }
    }

    @Test
    fun `POST games import rejects guest-shaped payloads when authenticated selection is required`() {
        val request =
            mapOf(
                "platform" to "CHESS_COM",
                "username" to "hikaru",
                "timeControls" to listOf("BLITZ"),
                "playerColor" to "WHITE",
            )

        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("ACCOUNT_SELECTION_REQUIRED") }
        }
    }

    @Test
    fun `POST games import rejects mismatched authenticated account snapshots with ACCOUNT_SELECTION_MISMATCH`() {
        val request =
            mapOf(
                "accountId" to UUID.randomUUID().toString(),
                "platform" to "CHESS_COM",
                "username" to "caseplayer",
                "timeControls" to listOf("BLITZ"),
                "playerColor" to "WHITE",
            )

        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("ACCOUNT_SELECTION_MISMATCH") }
        }
    }

    @Test
    fun `POST games import returns 409 when active job exists for username`() {
        doThrow(ActiveImportJobException("Active job exists for hikaru"))
            .whenever(gameImportService).createImportJob(any())

        mockMvc.post("/api/games/import") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(validRequest)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.error") { value("CONFLICT") }
        }
    }

    @Test
    fun `GET jobs id returns job status with counts`() {
        val jobId = UUID.randomUUID()
        val job =
            AsyncJob(
                id = jobId,
                username = "hikaru",
                platform = "CHESS_COM",
                status = "COMPLETED",
                gamesImported = 142,
                gamesSkipped = 30,
                gamesProcessed = 200,
                analysisStatus = "FAILED",
            )
        whenever(asyncJobRepository.findById(eq(jobId))).thenReturn(Optional.of(job))

        val result =
            mockMvc.get("/api/jobs/$jobId")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.jobId") { value(jobId.toString()) }
                    jsonPath("$.status") { value("COMPLETED") }
                    jsonPath("$.gamesImported") { value(142) }
                    jsonPath("$.gamesSkipped") { value(30) }
                    jsonPath("$.gamesProcessed") { value(200) }
                    jsonPath("$.analysisStatus") { value("FAILED") }
                }
                .andReturn()

        val response = objectMapper.readTree(result.response.contentAsString)
        assertTrue(response.path("errorMessage").isNull)
        val responseFields = response.fieldNames().asSequence().toSet()
        assertEquals(
            setOf(
                "jobId",
                "status",
                "gamesImported",
                "gamesSkipped",
                "gamesProcessed",
                "errorMessage",
                "analysisStatus",
            ),
            responseFields,
        )
    }

    @Test
    fun `GET jobs id derives archive status only from requested job range`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "scoped")
        val jobId = stubJob(account, fromDate = "2024-02", toDate = "2024-02")
        stubDerivedStatuses(
            account,
            // In scope for this job.
            "2024-02" to ArchiveDerivedStatus.COMPLETED,
            // Older archives from earlier, differently scoped imports. A failure
            // there says nothing about this job and must not be reported.
            "2023-05" to ArchiveDerivedStatus.FAILED,
            "2024-01" to ArchiveDerivedStatus.PROCESSING,
            // Newer than the requested range.
            "2024-03" to ArchiveDerivedStatus.PENDING,
        )

        mockMvc.get("/api/jobs/$jobId")
            .andExpect {
                status { isOk() }
                jsonPath("$.derivedStatus") { value("COMPLETED") }
            }
    }

    @Test
    fun `GET jobs id reports the least complete status among in-range archives`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "scoped")
        val jobId = stubJob(account, fromDate = "2024-01", toDate = "2024-02")
        stubDerivedStatuses(
            account,
            "2024-01" to ArchiveDerivedStatus.COMPLETED,
            "2024-02" to ArchiveDerivedStatus.FAILED,
        )

        mockMvc.get("/api/jobs/$jobId")
            .andExpect {
                status { isOk() }
                jsonPath("$.derivedStatus") { value("FAILED") }
            }
    }

    @Test
    fun `GET jobs id omits derived status for a current-month-only import`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "scoped")
        val currentMonth = YearMonth.now(ZoneOffset.UTC).toString()
        val jobId = stubJob(account, fromDate = currentMonth, toDate = currentMonth)
        stubDerivedStatuses(
            account,
            // A durable archive exists, but it belongs to an earlier month than
            // the one this job requested.
            "2024-02" to ArchiveDerivedStatus.FAILED,
        )

        mockMvc.get("/api/jobs/$jobId")
            .andExpect {
                status { isOk() }
                jsonPath("$.derivedStatus") { doesNotExist() }
            }
    }

    @Test
    fun `GET jobs id omits derived status when an unbounded import has no archives yet`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "scoped")
        val jobId = stubJob(account, fromDate = null, toDate = null)
        stubDerivedStatuses(account)

        mockMvc.get("/api/jobs/$jobId")
            .andExpect {
                status { isOk() }
                jsonPath("$.derivedStatus") { doesNotExist() }
            }
    }

    @Test
    fun `GET jobs id reports every archive of an unbounded import`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "scoped")
        val jobId = stubJob(account, fromDate = null, toDate = null)
        stubDerivedStatuses(
            account,
            "2023-05" to ArchiveDerivedStatus.COMPLETED,
            "2024-02" to ArchiveDerivedStatus.PENDING,
        )

        mockMvc.get("/api/jobs/$jobId")
            .andExpect {
                status { isOk() }
                jsonPath("$.derivedStatus") { value("PENDING") }
            }
    }

    @Test
    fun `GET jobs id omits derived status for a job that has not started`() {
        val account = ChessAccount(platform = "CHESS_COM", username = "scoped")
        val jobId = stubJob(account, fromDate = "2024-02", toDate = "2024-02", status = "QUEUED")
        stubDerivedStatuses(account, "2024-02" to ArchiveDerivedStatus.COMPLETED)

        mockMvc.get("/api/jobs/$jobId")
            .andExpect {
                status { isOk() }
                jsonPath("$.derivedStatus") { doesNotExist() }
            }
    }

    private fun stubJob(
        account: ChessAccount,
        fromDate: String?,
        toDate: String?,
        status: String = "COMPLETED",
    ): UUID {
        val jobId = UUID.randomUUID()
        val job =
            AsyncJob(
                id = jobId,
                chessAccount = account,
                username = account.username,
                platform = account.platform,
                status = status,
                fromDate = fromDate,
                toDate = toDate,
                timeControlsCsv = "BLITZ",
                playerColor = "BOTH",
                configurationState = AsyncJob.CONFIGURATION_READY,
            )
        whenever(asyncJobRepository.findByIdWithAccount(eq(jobId))).thenReturn(job)
        return jobId
    }

    private fun stubDerivedStatuses(
        account: ChessAccount,
        vararg archives: Pair<String, ArchiveDerivedStatus>,
    ) {
        whenever(archiveDerivedProcessingRepository.findDerivedStatusesByChessAccountId(eq(account.id)))
            .thenReturn(archives.map { (yearMonth, status) -> ArchiveDerivedStatusView(yearMonth, status) })
    }

    @Test
    fun `GET jobs id exposes the eligible-game cap and selection progress`() {
        val jobId = UUID.randomUUID()
        val job =
            AsyncJob(
                id = jobId,
                username = "hikaru",
                platform = "CHESS_COM",
                status = "COMPLETED",
                maxEligibleGames = 500,
                eligibleGamesSelected = 120,
            )
        whenever(asyncJobRepository.findById(eq(jobId))).thenReturn(Optional.of(job))

        mockMvc.get("/api/jobs/$jobId")
            .andExpect {
                status { isOk() }
                jsonPath("$.maxEligibleGames") { value(500) }
                jsonPath("$.eligibleGamesSelected") { value(120) }
            }
    }

    @Test
    fun `GET jobs id omits maxEligibleGames when the job has no cap`() {
        val jobId = UUID.randomUUID()
        val job =
            AsyncJob(
                id = jobId,
                username = "hikaru",
                platform = "CHESS_COM",
                status = "COMPLETED",
            )
        whenever(asyncJobRepository.findById(eq(jobId))).thenReturn(Optional.of(job))

        val result =
            mockMvc.get("/api/jobs/$jobId")
                .andExpect { status { isOk() } }
                .andReturn()

        val response = objectMapper.readTree(result.response.contentAsString)
        assertTrue(response.path("maxEligibleGames").isMissingNode)
        assertEquals(0, response.path("eligibleGamesSelected").asInt())
    }

    @Test
    fun `GET jobs id returns 404 when job does not exist`() {
        val jobId = UUID.randomUUID()
        whenever(asyncJobRepository.findById(eq(jobId))).thenReturn(Optional.empty())

        mockMvc.get("/api/jobs/$jobId")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.error") { value("NOT_FOUND") }
            }
    }
}
