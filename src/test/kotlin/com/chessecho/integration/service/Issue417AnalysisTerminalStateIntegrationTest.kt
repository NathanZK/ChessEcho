package com.chessecho.integration.service

import com.chessecho.domain.AsyncJob
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.TimeControl
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.service.ChessComClient
import com.chessecho.service.EngineAnalysisService
import com.chessecho.service.GameImportService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class Issue417AnalysisTerminalStateIntegrationTest {
    @Autowired
    private lateinit var gameImportService: GameImportService

    @Autowired
    private lateinit var asyncJobRepository: AsyncJobRepository

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @MockBean
    private lateinit var chessComClient: ChessComClient

    @MockBean
    private lateinit var engineAnalysisService: EngineAnalysisService

    @Test
    fun `all qualifying positions succeed and complete analysis`() {
        val account = createAccount("success")
        stubQualifyingGames(account.username)

        val terminalJob = execute(account)

        verify(engineAnalysisService, atLeastOnce()).analyzePosition(any())
        assertEquals("COMPLETED", terminalJob.status)
        assertEquals("COMPLETED", terminalJob.analysisStatus)
        assertEquals(null, terminalJob.errorMessage)
    }

    @Test
    fun `a swallowed position failure leaves import complete but fails analysis`() {
        val account = createAccount("single-failure")
        stubQualifyingGames(account.username)
        val failedPositionIds = mutableSetOf<UUID>()
        var calls = 0
        doAnswer { invocation ->
            val position = invocation.getArgument<com.chessecho.domain.Position>(0)
            calls++
            if (calls == 1) {
                failedPositionIds += position.id
                throw IllegalStateException("engine failed for ${position.id}")
            }
        }.whenever(engineAnalysisService).analyzePosition(any())

        val terminalJob = execute(account)

        assertTrue(calls > 1, "The real orchestrator must continue after a per-position failure")
        assertEquals("COMPLETED", terminalJob.status, "Import success is independent of analysis failure")
        assertEquals("FAILED", terminalJob.analysisStatus, "A swallowed per-position failure must fail analysis")
        assertEquals(null, terminalJob.errorMessage)
        assertDurableFailureContract(terminalJob, failedPositionIds)
    }

    @Test
    fun `multiple swallowed failures are aggregated without stopping qualifying work`() {
        val account = createAccount("multiple-failures")
        stubQualifyingGames(account.username)
        val failedPositionIds = mutableSetOf<UUID>()
        var calls = 0
        doAnswer { invocation ->
            val position = invocation.getArgument<com.chessecho.domain.Position>(0)
            calls++
            if (calls <= 2) {
                failedPositionIds += position.id
                throw IllegalStateException("engine failed for ${position.id}")
            }
        }.whenever(engineAnalysisService).analyzePosition(any())

        val terminalJob = execute(account)

        assertTrue(calls > 2, "Continue-on-failure must process positions after multiple failures")
        assertEquals("COMPLETED", terminalJob.status)
        assertEquals("FAILED", terminalJob.analysisStatus)
        assertDurableFailureContract(terminalJob, failedPositionIds)
    }

    @Test
    fun `zero qualifying positions complete as successful no-work`() {
        val account = createAccount("no-work")
        whenever(chessComClient.fetchArchiveUrls(account.username)).thenReturn(emptyList())

        val terminalJob = execute(account)

        verify(engineAnalysisService, times(0)).analyzePosition(any())
        assertEquals("COMPLETED", terminalJob.status)
        assertEquals("COMPLETED", terminalJob.analysisStatus)
    }

    private fun createAccount(suffix: String): ChessAccount =
        chessAccountRepository.save(
            ChessAccount(
                platform = "CHESS_COM",
                username = "issue417_${suffix}_${UUID.randomUUID().toString().take(8)}",
            ),
        )

    private fun stubQualifyingGames(username: String) {
        val archiveUrl = "https://api.chess.com/pub/player/$username/games/2024/01"
        whenever(chessComClient.fetchArchiveUrls(username)).thenReturn(listOf(archiveUrl))
        whenever(chessComClient.fetchMonthlyGames(archiveUrl)).thenReturn(
            (1..5).map { sequence ->
                mapOf(
                    "url" to "https://www.chess.com/game/live/$username-417$sequence",
                    "pgn" to pgn(username, sequence),
                    "time_class" to "blitz",
                    "rules" to "chess",
                    "end_time" to 1704067200L + sequence,
                    "white" to mapOf("username" to username, "result" to "win"),
                    "black" to mapOf("username" to "opponent$sequence", "result" to "resigned"),
                )
            },
        )
    }

    private fun pgn(
        username: String,
        sequence: Int,
    ): String =
        """
        [Event "Live Chess"]
        [Site "Chess.com"]
        [Date "2024.01.0$sequence"]
        [White "$username"]
        [Black "opponent$sequence"]
        [Result "1-0"]

        1. e4 e5 2. Nf3 Nc6 3. Bb5 1-0
        """.trimIndent()

    private fun execute(account: ChessAccount): AsyncJob {
        val request =
            ImportGamesRequest(
                username = account.username,
                platform = Platform.CHESS_COM,
                timeControls = listOf(TimeControl.BLITZ),
                playerColor = PlayerColor.BOTH,
            )
        val job = gameImportService.createImportJob(request)
        gameImportService.executeImportJob(job.id, request)
        repeat(50) {
            val current = asyncJobRepository.findById(job.id).orElse(null)
            if (current?.analysisStatus in setOf("COMPLETED", "FAILED")) {
                return assertNotNull(current)
            }
            Thread.sleep(100)
        }
        error("Import job ${job.id} did not reach a terminal analysis state")
    }

    private fun assertDurableFailureContract(
        job: AsyncJob,
        failedPositionIds: Set<UUID>,
    ) {
        val property =
            AsyncJob::class.memberProperties.singleOrNull { it.name == "failedPositionIds" }
                ?: error(
                    "AsyncJob must durably expose failedPositionIds so a FAILED analysis identifies ${failedPositionIds.joinToString()}",
                )

        @Suppress("UNCHECKED_CAST")
        val persistedFailedPositionIds = property.getter.call(job) as Set<UUID>
        assertEquals(failedPositionIds, persistedFailedPositionIds)
    }
}
