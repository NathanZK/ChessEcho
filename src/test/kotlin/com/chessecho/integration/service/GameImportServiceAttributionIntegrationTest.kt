package com.chessecho.integration.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ArchiveDerivedStatus
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.TimeControl
import com.chessecho.domain.UserPositionWeakness
import com.chessecho.dto.AccountAssociationRequest
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ArchiveDerivedProcessingRepository
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.ImportedArchiveRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.UserPositionStatsRepository
import com.chessecho.repository.UserPositionWeaknessRepository
import com.chessecho.service.AccountOwnershipService
import com.chessecho.service.ChessComClient
import com.chessecho.service.EngineAnalysisOrchestrator
import com.chessecho.service.GameImportService
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #457 T8 — import attribution.
 *
 * An `AsyncJob` is a durable command, not a projection of a later request. It must record the
 * `AppUser` that initiated it, and every piece of per-user state it later writes must be
 * attributed to *that* user — never to whoever happens to connect the account by the time
 * the worker finishes. See plan Sections 3.2 and 3.3.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GameImportServiceAttributionIntegrationTest {
    @Autowired private lateinit var gameImportService: GameImportService

    @Autowired private lateinit var ownership: AccountOwnershipService

    @Autowired private lateinit var asyncJobRepository: AsyncJobRepository

    @Autowired private lateinit var appUserRepository: AppUserRepository

    @Autowired private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired private lateinit var gameRepository: GameRepository

    @Autowired private lateinit var importedArchiveRepository: ImportedArchiveRepository

    @Autowired private lateinit var archiveDerivedProcessingRepository: ArchiveDerivedProcessingRepository

    @Autowired private lateinit var positionRepository: PositionRepository

    @Autowired private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired private lateinit var userPositionStatsRepository: UserPositionStatsRepository

    @Autowired private lateinit var userPositionWeaknessRepository: UserPositionWeaknessRepository

    @Autowired private lateinit var engineAnalysisRepository: EngineAnalysisRepository

    @Autowired private lateinit var puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository

    @MockBean private lateinit var chessComClient: ChessComClient

    @MockBean private lateinit var engineAnalysisOrchestrator: EngineAnalysisOrchestrator

    private lateinit var userA: AppUser
    private lateinit var userB: AppUser

    @BeforeEach
    fun setup() {
        userA = appUserRepository.save(AppUser())
        userB = appUserRepository.save(AppUser())

        whenever(chessComClient.fetchArchiveUrls(any())).thenAnswer { invocation ->
            listOf("https://api.chess.com/pub/player/${invocation.getArgument<String>(0)}/games/2024/01")
        }
        whenever(chessComClient.fetchMonthlyGames(any())).thenAnswer { invocation ->
            if (invocation.getArgument<String>(0).endsWith("/2024/01")) monthlyGames() else null
        }
    }

    @AfterEach
    fun tearDown() {
        puzzleSchedulingEventRepository.deleteAll()
        engineAnalysisRepository.deleteAll()
        userPositionWeaknessRepository.deleteAll()
        userPositionStatsRepository.deleteAll()
        positionOccurrenceRepository.deleteAll()
        positionRepository.deleteAll()
        gameRepository.deleteAll()
        archiveDerivedProcessingRepository.deleteAll()
        importedArchiveRepository.deleteAll()
        asyncJobRepository.deleteAll()
        chessAccountRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `an authenticated import job records the initiating user`() {
        val account = connectedAccount(userA)
        val job =
            gameImportService.createImportJob(
                importRequest(account.id),
                AuthenticatedPrincipal(userA.id, false),
            )

        assertEquals(userA.id, assertNotNull(asyncJobRepository.findById(job.id).orElse(null)).appUser?.id)
    }

    @Test
    fun `a guest import job records no initiating user`() {
        val job = gameImportService.createImportJob(importRequest())

        assertNull(assertNotNull(asyncJobRepository.findById(job.id).orElse(null)).appUser)
    }

    @Test
    fun `scheduling events emitted by a job are attributed to the job's initiating user`() {
        val job = runImport(AuthenticatedPrincipal(userA.id, false))

        assertEquals("COMPLETED", awaitTerminal(job.id).status)
        val events = puzzleSchedulingEventRepository.findAll()
        assertTrue(events.isNotEmpty(), "the seeded weakness must produce source-linked events")
        assertEquals(
            setOf(userA.id),
            events.map { it.appUser?.id }.toSet(),
            "every event the job wrote belongs to the user who started it",
        )
    }

    @Test
    fun `scheduling events follow the initiating user even after the account is reconnected to someone else`() {
        // User A connects the account when the job is created, then disconnects and B connects before
        // the worker runs. Deriving attribution from the current connection at emit time would hand
        // A's in-flight work to B; the job's own stored user must win.
        val account = connectedAccount(userA)
        val principal = AuthenticatedPrincipal(userA.id, false)
        val seedJob = gameImportService.createImportJob(importRequest(account.id), principal)
        gameImportService.executeImportJob(seedJob.id)
        awaitTerminal(seedJob.id)
        seedWeaknessesForOccurrences(account)
        reopenDerivedProcessing(account)

        val job = gameImportService.createImportJob(importRequest(account.id), principal)
        ownership.disconnect(account.id, principal)
        reconnectTo(account, userB)
        gameImportService.executeImportJob(job.id)

        assertEquals("COMPLETED", awaitTerminal(job.id).status)
        val events = puzzleSchedulingEventRepository.findAll()
        assertTrue(events.isNotEmpty(), "the seeded weakness must produce source-linked events")
        assertEquals(
            setOf(userA.id),
            events.map { it.appUser?.id }.toSet(),
            "attribution must come from the job, not from the account's current connection pointer",
        )
    }

    @Test
    fun `a guest job emits unattributed scheduling events`() {
        val job = runImport(null)

        assertEquals("COMPLETED", awaitTerminal(job.id).status)
        val events = puzzleSchedulingEventRepository.findAll()
        assertTrue(events.isNotEmpty())
        assertEquals(setOf<UUID?>(null), events.map { it.appUser?.id }.toSet())
    }

    /**
     * Runs a first import to materialise occurrences, seeds the weakness/analysis rows that
     * `emitSchedulingEvents` requires, then re-runs so events are actually emitted.
     */
    private fun runImport(principal: AuthenticatedPrincipal?): com.chessecho.domain.AsyncJob {
        val account = principal?.let { connectedAccount(userA) }

        fun create() =
            if (principal == null) {
                gameImportService.createImportJob(importRequest())
            } else {
                gameImportService.createImportJob(importRequest(account!!.id), principal)
            }

        val first = create()
        gameImportService.executeImportJob(first.id)
        awaitTerminal(first.id)
        val resolved =
            account
                ?: assertNotNull(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "hikaru"))
        seedWeaknessesForOccurrences(resolved)
        reopenDerivedProcessing(resolved)

        val second = create()
        gameImportService.executeImportJob(second.id)
        return second
    }

    /**
     * Returns the account's derived-processing record to FAILED so the next job re-derives it.
     * Scheduling events are only emitted from derived processing, and a COMPLETED record is
     * skipped outright.
     */
    private fun reopenDerivedProcessing(account: ChessAccount) {
        importedArchiveRepository.findByChessAccount(account).forEach { archive ->
            val record = assertNotNull(archiveDerivedProcessingRepository.findByImportedArchive(archive))
            record.status = ArchiveDerivedStatus.FAILED
            record.workerToken = null
            record.leaseExpiresAt = null
            record.completedAt = null
            archiveDerivedProcessingRepository.saveAndFlush(record)
        }
    }

    private fun connectedAccount(user: AppUser): ChessAccount {
        val result = ownership.associate(AuthenticatedPrincipal(user.id, false), AccountAssociationRequest("CHESS_COM", "hikaru"))
        return chessAccountRepository.findById(result.account.id).orElseThrow()
    }

    private fun reconnectTo(
        account: ChessAccount,
        user: AppUser,
    ) {
        ownership.associate(AuthenticatedPrincipal(user.id, false), AccountAssociationRequest(account.platform, account.username))
    }

    private fun seedWeaknessesForOccurrences(account: ChessAccount) {
        positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(account.id, "WHITE").forEach { occurrence ->
            if (userPositionWeaknessRepository.findByChessAccountIdAndPositionIdAndPlayerColor(
                    account.id,
                    occurrence.position.id,
                    occurrence.playerColor,
                ) == null
            ) {
                userPositionWeaknessRepository.save(
                    UserPositionWeakness(
                        chessAccount = account,
                        position = occurrence.position,
                        playerColor = occurrence.playerColor,
                        mistakeCount = 1,
                    ),
                )
            }
            if (engineAnalysisRepository.findByPositionIdWithMoveEvaluations(occurrence.position.id) != null) {
                return@forEach
            }
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
    }

    private fun awaitTerminal(jobId: UUID): com.chessecho.domain.AsyncJob {
        val deadline = Instant.now().plus(Duration.ofSeconds(30))
        while (Instant.now().isBefore(deadline)) {
            val job = assertNotNull(asyncJobRepository.findById(jobId).orElse(null))
            if (job.status == "COMPLETED" || job.status == "FAILED") return job
            Thread.sleep(50)
        }
        throw AssertionError("job $jobId never reached a terminal status")
    }

    private fun importRequest(accountId: UUID? = null) =
        ImportGamesRequest(
            accountId = accountId,
            platform = Platform.CHESS_COM,
            username = "hikaru",
            timeControls = listOf(TimeControl.BLITZ),
            playerColor = PlayerColor.WHITE,
        )

    private fun monthlyGames(): List<Map<String, Any>> {
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
        return listOf(
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
    }
}
