package com.chessecho.integration.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ArchiveDerivedStatus
import com.chessecho.domain.AsyncJob
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.TimeControl
import com.chessecho.domain.UserPositionWeakness
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ArchiveDerivedProcessingRepository
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.ImportedArchiveRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.UserPositionWeaknessRepository
import com.chessecho.service.ChessComClient
import com.chessecho.service.EngineAnalysisOrchestrator
import com.chessecho.service.GameImportService
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #457. Authoritative coverage for guest import-replay deduplication of source-linked
 * scheduling events, exercised through the real [GameImportService] claim/recovery path.
 *
 * This lives on PostgreSQL rather than in [GameImportServiceIntegrationTest] because the
 * invariant depends on the PostgreSQL-only `NULLS NOT DISTINCT` semantics of
 * `uk_puzzle_event_source_type`. Guest events carry `app_user_id = NULL`, and H2 — which backs
 * the `test` profile — treats NULLs in a unique index as distinct, so H2 cannot reject the
 * duplicate at all and therefore cannot prove this behavior.
 *
 * The test runs against the real Flyway baseline with `ddl-auto: validate`, so it additionally
 * proves the JPA mappings agree with the schema that enforces the invariant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GuestSchedulingClaimReplayPostgresIntegrationTest {
    @Autowired
    private lateinit var gameImportService: GameImportService

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var asyncJobRepository: AsyncJobRepository

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    private lateinit var puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository

    @Autowired
    private lateinit var userPositionWeaknessRepository: UserPositionWeaknessRepository

    @Autowired
    private lateinit var engineAnalysisRepository: EngineAnalysisRepository

    @Autowired
    private lateinit var importedArchiveRepository: ImportedArchiveRepository

    @Autowired
    private lateinit var archiveDerivedProcessingRepository: ArchiveDerivedProcessingRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @MockBean
    private lateinit var chessComClient: ChessComClient

    @MockBean
    private lateinit var engineAnalysisOrchestrator: EngineAnalysisOrchestrator

    @Test
    fun `a replayed guest import recovers the existing claim instead of duplicating it`() {
        stubChessComArchive()

        // 1. First guest import materializes the games and occurrences.
        val first = gameImportService.createImportJob(importRequest())
        gameImportService.executeImportJob(first.id)
        assertEquals("COMPLETED", awaitTerminalJob(first.id).status)

        val account =
            assertNotNull(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "hikaru"))
        assertNull(account.user, "an import with no signed-in principal must produce a guest account")

        val occurrences = positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE")
        assertFalse(occurrences.isEmpty(), "the fixture archive must yield occurrences to claim")

        // Scheduling events are only emitted for occurrences that carry a weakness and an
        // analysis, so seed those before forcing the claim-emitting replays.
        occurrences.forEach { occurrence ->
            userPositionWeaknessRepository.save(
                UserPositionWeakness(
                    chessAccount = account,
                    position = occurrence.position,
                    playerColor = occurrence.playerColor,
                    mistakeCount = 1,
                ),
            )
            val analysis =
                EngineAnalysis(
                    position = occurrence.position,
                    depth = 12,
                    baselineEvalCp = 20,
                    bestMove = occurrence.movePlayed,
                    bestMoveEvalCp = 20,
                )
            analysis.moveEvaluations.add(
                MoveEvaluation(
                    engineAnalysis = analysis,
                    move = occurrence.movePlayed,
                    evalCp = 20,
                    evalLossFromBest = 0.0,
                ),
            )
            engineAnalysisRepository.save(analysis)
        }

        forceArchiveRederivation(account.id)
        val seeding = gameImportService.createImportJob(importRequest())
        gameImportService.executeImportJob(seeding.id)
        assertEquals("COMPLETED", awaitTerminalJob(seeding.id).status)

        val claimed = puzzleSchedulingEventRepository.findAll().filter { it.sourceOccurrence != null }
        assertFalse(claimed.isEmpty(), "the seeding replay must have emitted source-linked claims")
        assertTrue(
            claimed.all { it.appUser == null },
            "a guest import must leave its scheduling events unattributed",
        )
        val claimedIds = claimed.map { it.id }.toSet()
        val claimedKeys = claimed.map { it.sourceOccurrence?.id to it.eventType }.toSet()

        // 2-5. Replay the same derived unit. Every re-emitted event now collides with an existing
        // row, so GameImportService.claimSchedulingEvent must catch the
        // DataIntegrityViolationException and resolve it through findExistingClaim. If that
        // recovery failed, the exception would propagate and the job would end FAILED.
        forceArchiveRederivation(account.id)
        val replay = gameImportService.createImportJob(importRequest())
        gameImportService.executeImportJob(replay.id)
        assertEquals(
            "COMPLETED",
            awaitTerminalJob(replay.id).status,
            "the replay must recover from the claim conflict rather than failing the job",
        )

        // 6. Exactly the original rows survive - not replacements silently written by the replay.
        val afterReplay = puzzleSchedulingEventRepository.findAll().filter { it.sourceOccurrence != null }
        assertEquals(
            claimedKeys,
            afterReplay.map { it.sourceOccurrence?.id to it.eventType }.toSet(),
        )
        assertEquals(
            claimedIds,
            afterReplay.map { it.id }.toSet(),
            "recovery must preserve the original claims rather than creating new ones",
        )
        assertEquals(
            claimed.size,
            afterReplay.size,
            "a replayed guest import must not duplicate source-linked scheduling events",
        )

        // 7. Distinct event types for the same occurrence remain independently representable.
        val perOccurrence = afterReplay.groupBy { it.sourceOccurrence?.id }
        assertTrue(
            perOccurrence.values.any { it.map(PuzzleSchedulingEvent::eventType).toSet().size > 1 },
            "the import emits more than one event type per occurrence; they must coexist",
        )
        perOccurrence.forEach { (occurrenceId, events) ->
            assertEquals(
                events.size,
                events.map { it.eventType }.toSet().size,
                "occurrence $occurrenceId must hold at most one event per type",
            )
        }

        val initiatingUser = appUserRepository.save(AppUser(email = "replay-${UUID.randomUUID()}@example.com"))
        val authenticatedAccount = chessAccountRepository.findById(account.id).orElseThrow()
        authenticatedAccount.user = initiatingUser
        chessAccountRepository.saveAndFlush(authenticatedAccount)
        val principal = AuthenticatedPrincipal(initiatingUser.id, devPrincipal = false)

        // Authenticated claims occupy a separate `(app_user_id, occurrence, event_type)` namespace.
        // Exercise that namespace through the import worker too, including conflict recovery on replay.
        forceArchiveRederivation(account.id)
        val authenticatedFirst = gameImportService.createImportJob(importRequest(account.id), principal)
        gameImportService.executeImportJob(authenticatedFirst.id)
        assertEquals("COMPLETED", awaitTerminalJob(authenticatedFirst.id).status)

        val authenticatedClaims =
            puzzleSchedulingEventRepository
                .findAll()
                .filter { it.sourceOccurrence != null && it.appUser?.id == initiatingUser.id }
        assertFalse(authenticatedClaims.isEmpty(), "the authenticated import must create source-linked claims")
        assertTrue(authenticatedClaims.all { it.appUser?.id == initiatingUser.id })
        val authenticatedClaimIds = authenticatedClaims.map { it.id }.toSet()
        val authenticatedClaimKeys =
            authenticatedClaims.map { it.sourceOccurrence?.id to it.eventType }.toSet()

        forceArchiveRederivation(account.id)
        val authenticatedReplay = gameImportService.createImportJob(importRequest(account.id), principal)
        gameImportService.executeImportJob(authenticatedReplay.id)
        assertEquals(
            "COMPLETED",
            awaitTerminalJob(authenticatedReplay.id).status,
            "an authenticated replay must recover its own existing claims instead of failing",
        )

        val authenticatedClaimsAfterReplay =
            puzzleSchedulingEventRepository
                .findAll()
                .filter { it.sourceOccurrence != null && it.appUser?.id == initiatingUser.id }
        assertEquals(
            authenticatedClaimKeys,
            authenticatedClaimsAfterReplay.map { it.sourceOccurrence?.id to it.eventType }.toSet(),
        )
        assertEquals(authenticatedClaimIds, authenticatedClaimsAfterReplay.map { it.id }.toSet())
        assertEquals(authenticatedClaims.size, authenticatedClaimsAfterReplay.size)
    }

    /**
     * Returns the archive's derived unit to FAILED - the state a crash in the stats/scheduling
     * window leaves behind, and the only way to make the coordinator recompute an already
     * imported archive.
     */
    private fun forceArchiveRederivation(accountId: UUID) {
        val account = chessAccountRepository.findById(accountId).orElseThrow()
        val archive = importedArchiveRepository.findByChessAccount(account).single()
        transactionTemplate.executeWithoutResult {
            val record = assertNotNull(archiveDerivedProcessingRepository.findByImportedArchive(archive))
            record.status = ArchiveDerivedStatus.FAILED
            record.workerToken = null
            record.leaseExpiresAt = null
            record.completedAt = null
            archiveDerivedProcessingRepository.saveAndFlush(record)
        }
    }

    private fun stubChessComArchive() {
        whenever(chessComClient.fetchArchiveUrls(any())).thenAnswer { invocation ->
            val username = invocation.getArgument<String>(0)
            listOf("https://api.chess.com/pub/player/$username/games/2024/01")
        }
        whenever(chessComClient.fetchMonthlyGames(any())).thenAnswer { invocation ->
            val uriString = invocation.getArgument<String>(0)
            if (uriString.endsWith("/2024/01")) {
                val pgn =
                    """
                    [Event "Live Chess"]
                    [Site "Chess.com"]
                    [Date "2024.01.01"]
                    [White "hikaru"]
                    [Black "opponent1"]
                    [Result "1-0"]

                    1. e4 e5 2. Nf3 Nc6 3. Bb5 1-0
                    """.trimIndent()
                listOf(
                    mapOf(
                        "url" to "https://www.chess.com/game/live/10001",
                        "pgn" to pgn,
                        "time_class" to "blitz",
                        "rules" to "chess",
                        "end_time" to 1704067200L,
                        "white" to mapOf("username" to "hikaru", "result" to "win"),
                        "black" to mapOf("username" to "opponent1", "result" to "checkmated"),
                    ),
                )
            } else {
                null
            }
        }
    }

    private fun importRequest(accountId: UUID? = null): ImportGamesRequest =
        ImportGamesRequest(
            username = "hikaru",
            platform = Platform.CHESS_COM,
            accountId = accountId,
            timeControls = listOf(TimeControl.BLITZ),
            playerColor = PlayerColor.BOTH,
        )

    private fun awaitTerminalJob(jobId: UUID): AsyncJob {
        repeat(50) {
            val current = asyncJobRepository.findById(jobId).orElse(null)
            if (current != null && current.status in listOf("COMPLETED", "FAILED")) {
                return current
            }
            Thread.sleep(100)
        }
        error("Import job $jobId did not complete within timeout")
    }

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer<Nothing> = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
