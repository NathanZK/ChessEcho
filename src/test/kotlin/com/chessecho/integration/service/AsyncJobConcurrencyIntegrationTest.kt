package com.chessecho.integration.service

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.TimeControl
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.service.ActiveImportJobException
import com.chessecho.service.GameImportService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Active-job exclusion is a database-backed invariant, not a check-then-insert
 * convention. A losing transaction must roll back completely, while terminal
 * jobs remain reusable for a later import.
 */
@SpringBootTest
@ActiveProfiles("test")
class AsyncJobConcurrencyIntegrationTest {
    @Autowired
    private lateinit var gameImportService: GameImportService

    @Autowired
    private lateinit var asyncJobRepository: AsyncJobRepository

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @AfterEach
    fun cleanUp() {
        asyncJobRepository.deleteAll()
    }

    @Test
    fun `concurrent creation leaves exactly one active job and no orphan from the loser`() {
        val request = request("job-race-${UUID.randomUUID()}")
        seedAccount(request.username!!)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (1..2).map {
                    pool.submit(
                        Callable {
                            start.await(10, TimeUnit.SECONDS)
                            runCatching { gameImportService.createImportJob(request) }
                        },
                    )
                }
            start.countDown()
            val outcomes = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(1, outcomes.count { it.exceptionOrNull() is ActiveImportJobException })
            val persisted = asyncJobRepository.findAll()
            assertEquals(1, persisted.size, "the rejected transaction must not leave an orphan job")
            assertTrue(persisted.single().status in setOf("QUEUED", "PROCESSING"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `terminal jobs can be reused without violating the one-active-job invariant`() {
        val request = request("terminal-reuse-${UUID.randomUUID()}")
        seedAccount(request.username!!)
        val terminal = gameImportService.createImportJob(request)
        terminal.status = "COMPLETED"
        asyncJobRepository.saveAndFlush(terminal)

        val replacement = gameImportService.createImportJob(request)

        assertNotEquals(terminal.id, replacement.id)
        assertEquals("COMPLETED", asyncJobRepository.findById(terminal.id).orElseThrow().status)
        assertEquals(
            replacement.id,
            asyncJobRepository.findByUsernameAndStatusIn(request.username, listOf("QUEUED", "PROCESSING"))?.id,
        )
    }

    @Test
    fun `a rejected active creation is rolled back while a later terminal retry succeeds`() {
        val request = request("rollback-${UUID.randomUUID()}")
        seedAccount(request.username!!)
        val first = gameImportService.createImportJob(request)

        val rejected =
            runCatching { gameImportService.createImportJob(request) }
        assertTrue(rejected.exceptionOrNull() is ActiveImportJobException)
        assertEquals(1, asyncJobRepository.count())

        first.status = "FAILED"
        asyncJobRepository.saveAndFlush(first)
        val retry = gameImportService.createImportJob(request)
        assertNotEquals(first.id, retry.id)
        assertEquals(2, asyncJobRepository.count())
    }

    private fun request(username: String): ImportGamesRequest =
        ImportGamesRequest(
            username = username,
            platform = com.chessecho.domain.Platform.CHESS_COM,
            timeControls = listOf(TimeControl.BLITZ),
            playerColor = PlayerColor.BOTH,
        )

    private fun seedAccount(username: String) {
        chessAccountRepository.saveAndFlush(
            ChessAccount(
                user = null,
                platform = Platform.CHESS_COM.name,
                username = username,
            ),
        )
    }
}
