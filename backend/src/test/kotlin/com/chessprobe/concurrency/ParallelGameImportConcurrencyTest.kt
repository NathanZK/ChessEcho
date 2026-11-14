package com.chessprobe.concurrency

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.Platform
import com.chessprobe.domain.PlayerColor
import com.chessprobe.domain.Position
import com.chessprobe.dto.ImportGamesRequest
import com.chessprobe.integration.service.GameImportServiceBaseIntegrationTest
import com.chessprobe.service.AnalysisOutcome
import com.chessprobe.service.GameParserService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageRequest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertNotNull

class ParallelGameImportConcurrencyTest : GameImportServiceBaseIntegrationTest() {
    @Test
    fun `parallel import jobs verify primary key uniqueness, complete records, referential integrity, and tenant ownership`() {
        setupMockChessComClient()
        whenever(engineAnalysisOrchestrator.analyzeAffectedPositions(any())).thenReturn(AnalysisOutcome())

        val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        positionRepository.save(Position(hash = GameParserService.generateHash(startFen), fen = startFen))

        val jobA = gameImportService.createImportJob(createImportRequest("hikaru"))
        val jobB = gameImportService.createImportJob(createImportRequest("magnus"))

        executeConcurrentJobs(jobA.id, jobB.id)

        assertEquals("COMPLETED", awaitTerminalJob(jobA.id).status)
        assertEquals("COMPLETED", awaitTerminalJob(jobB.id).status)

        val accountA = assertNotNull(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "hikaru"))
        val accountB = assertNotNull(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "magnus"))

        assertTenantOwnership(accountA, accountB)
        assertUniqueKeysAndReferentialIntegrity()
    }

    private fun createImportRequest(username: String) =
        ImportGamesRequest(
            username = username,
            platform = Platform.CHESS_COM,
            timeControls = listOf(com.chessprobe.domain.TimeControl.BLITZ),
            playerColor = PlayerColor.BOTH,
        )

    private fun executeConcurrentJobs(
        jobAId: UUID,
        jobBId: UUID,
    ) {
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(2)
        val errorA = AtomicReference<Throwable?>()
        val errorB = AtomicReference<Throwable?>()

        Thread.ofVirtual().start {
            startLatch.await()
            try {
                gameImportService.executeImportJob(jobAId)
            } catch (t: Throwable) {
                errorA.set(t)
            } finally {
                doneLatch.countDown()
            }
        }

        Thread.ofVirtual().start {
            startLatch.await()
            try {
                gameImportService.executeImportJob(jobBId)
            } catch (t: Throwable) {
                errorB.set(t)
            } finally {
                doneLatch.countDown()
            }
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS), "Concurrent imports must complete within timeout")

        if (errorA.get() != null) throw AssertionError("Import A failed", errorA.get())
        if (errorB.get() != null) throw AssertionError("Import B failed", errorB.get())
    }

    private fun assertTenantOwnership(
        accountA: ChessAccount,
        accountB: ChessAccount,
    ) {
        val gamesA = gameRepository.findAllByChessAccountOrderByPlayedAtDesc(accountA, PageRequest.of(0, 10)).content
        val gamesB = gameRepository.findAllByChessAccountOrderByPlayedAtDesc(accountB, PageRequest.of(0, 10)).content
        assertEquals(1, gamesA.size, "Account A must have exactly 1 game")
        assertEquals(1, gamesB.size, "Account B must have exactly 1 game")
        assertEquals("https://www.chess.com/game/live/101", gamesA.first().platformGameId)
        assertEquals("https://www.chess.com/game/live/102", gamesB.first().platformGameId)
    }

    private fun assertUniqueKeysAndReferentialIntegrity() {
        val allGames = gameRepository.findAll()
        val gameIds = allGames.map { it.id }.toSet()
        assertEquals(allGames.size, gameIds.size, "All games must have unique primary keys")

        val allOccurrences = positionOccurrenceRepository.findAll()
        val occurrenceIds = allOccurrences.map { it.id }.toSet()
        assertEquals(allOccurrences.size, occurrenceIds.size, "All occurrences must have unique primary keys")

        for (occ in allOccurrences) {
            assertNotNull(occ.game, "Occurrence must reference a valid game")
            assertNotNull(occ.position, "Occurrence must reference a valid position")
            assertNotNull(occ.chessAccount, "Occurrence must reference a valid account")
            assertEquals(occ.game.chessAccount.id, occ.chessAccount.id, "Occurrence account must match game account")
        }
    }

    private fun setupMockChessComClient() {
        val pgnA = "[Event \"A\"]\n[White \"hikaru\"]\n[Black \"oppA\"]\n1. e4 e5 2. Nf3 Nc6 3. Bb5 1-0"
        val pgnB = "[Event \"B\"]\n[White \"magnus\"]\n[Black \"oppB\"]\n1. d4 d5 2. c4 e6 3. Nc3 1-0"

        whenever(chessComClient.fetchMonthlyGames(any())).thenAnswer { invocation ->
            val uri = invocation.getArgument<String>(0)
            when {
                uri.contains("/player/hikaru/") ->
                    listOf(
                        mapOf(
                            "url" to "https://www.chess.com/game/live/101",
                            "pgn" to pgnA,
                            "time_class" to "blitz",
                            "rules" to "chess",
                            "end_time" to 1704067200L,
                            "white" to mapOf("username" to "hikaru", "result" to "win"),
                            "black" to mapOf("username" to "oppA", "result" to "checkmated"),
                        ),
                    )
                uri.contains("/player/magnus/") ->
                    listOf(
                        mapOf(
                            "url" to "https://www.chess.com/game/live/102",
                            "pgn" to pgnB,
                            "time_class" to "blitz",
                            "rules" to "chess",
                            "end_time" to 1704153600L,
                            "white" to mapOf("username" to "magnus", "result" to "win"),
                            "black" to mapOf("username" to "oppB", "result" to "checkmated"),
                        ),
                    )
                else -> null
            }
        }
    }
}
