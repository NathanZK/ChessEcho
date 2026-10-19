package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.AsyncJob
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import com.chessecho.domain.TimeControl
import com.chessecho.domain.UserPositionWeakness
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class GameImportServiceTest {
    private val asyncJobRepository: AsyncJobRepository = mock()
    private val appUserRepository: AppUserRepository = mock()
    private val chessAccountRepository: ChessAccountRepository = mock()
    private val gameRepository: GameRepository = mock()
    private val importedArchiveRepository: ImportedArchiveRepository = mock()
    private val archiveDerivedProcessingRepository: ArchiveDerivedProcessingRepository = mock()
    private val chessComClient: ChessComClient = mock()
    private val gameParserService: GameParserService = mock()
    private val userPositionStatsRepository: UserPositionStatsRepository = mock()
    private val positionOccurrenceRepository: PositionOccurrenceRepository = mock()
    private val positionRepository: PositionRepository = mock()
    private val engineAnalysisOrchestrator: EngineAnalysisOrchestrator = mock()
    private val accountOwnershipService: AccountOwnershipService = mock()
    private val engineAnalysisRepository: EngineAnalysisRepository = mock()
    private val userPositionWeaknessRepository: UserPositionWeaknessRepository = mock()
    private val puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository = mock()
    private val dataSource: DataSource = mock()

    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var service: GameImportService
    private val gameImportService: GameImportService get() = service

    private val user = AppUser(email = "user@example.com")
    private val account = ChessAccount(platform = "CHESS_COM", username = "player")
    private val position = Position(hash = "pos_hash", fen = "6k1/5ppp/8/8/8/8/5PPP/4R1K1 w - - 0 1")

    @BeforeEach
    fun setup() {
        val transactionManager = mock<PlatformTransactionManager>()
        whenever(transactionManager.getTransaction(any())).thenReturn(SimpleTransactionStatus())
        transactionTemplate = TransactionTemplate(transactionManager)

        val metadata = mock<java.sql.DatabaseMetaData>()
        whenever(metadata.databaseProductName).thenReturn("H2")
        val connection = mock<java.sql.Connection>()
        whenever(connection.metaData).thenReturn(metadata)
        whenever(dataSource.connection).thenReturn(connection)

        service =
            GameImportService(
                asyncJobRepository = asyncJobRepository,
                appUserRepository = appUserRepository,
                chessAccountRepository = chessAccountRepository,
                gameRepository = gameRepository,
                importedArchiveRepository = importedArchiveRepository,
                archiveDerivedProcessingRepository = archiveDerivedProcessingRepository,
                chessComClient = chessComClient,
                gameParserService = gameParserService,
                userPositionStatsRepository = userPositionStatsRepository,
                positionOccurrenceRepository = positionOccurrenceRepository,
                positionRepository = positionRepository,
                engineAnalysisOrchestrator = engineAnalysisOrchestrator,
                transactionTemplate = transactionTemplate,
                accountOwnershipService = accountOwnershipService,
                engineAnalysisRepository = engineAnalysisRepository,
                userPositionWeaknessRepository = userPositionWeaknessRepository,
                puzzleSchedulingEventRepository = puzzleSchedulingEventRepository,
                dataSource = dataSource,
            )
    }

    @Test
    fun `emitSchedulingEvents emits GAME_MISTAKE for checkmate blunders`() {
        val game =
            Game(
                id = UUID.randomUUID(),
                chessAccount = account,
                platformGameId = "game_1",
                pgn = "1. e4",
                playedAt = Instant.now(),
            )
        val occurrence =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "f3",
                playerColor = "WHITE",
            )

        val weakness = UserPositionWeakness(chessAccount = account, position = position, playerColor = "WHITE")
        whenever(userPositionWeaknessRepository.findByChessAccountIdAndPositionIdAndPlayerColor(account.id, position.id, "WHITE"))
            .thenReturn(weakness)

        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalMate = 1,
                bestMove = "Re8#",
                bestMoveEvalMate = 1,
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "f3", evalMate = -1, evalLossFromBest = 19.5),
        )
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(position.id)).thenReturn(analysis)

        whenever(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                setOf(position.id),
            ),
        ).thenReturn(listOf(occurrence))
        whenever(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "BLACK",
                setOf(position.id),
            ),
        ).thenReturn(emptyList())

        invokeEmitSchedulingEvents(listOf(game), user, setOf(position.id))

        val captor = argumentCaptor<PuzzleSchedulingEvent>()
        verify(puzzleSchedulingEventRepository, times(2)).saveAndFlush(captor.capture())

        val eventTypes = captor.allValues.map { it.eventType }
        assertTrue(eventTypes.contains(SchedulingEventType.GAME_REENCOUNTERED))
        assertTrue(eventTypes.contains(SchedulingEventType.GAME_MISTAKE))
    }

    @Test
    fun `emitSchedulingEvents emits GAME_HANDLED_SUCCESSFULLY for evaluated non-mistake`() {
        val game =
            Game(
                id = UUID.randomUUID(),
                chessAccount = account,
                platformGameId = "game_2",
                pgn = "1. Re8#",
                playedAt = Instant.now(),
            )
        val occurrence =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "Re8#",
                playerColor = "WHITE",
            )

        val weakness = UserPositionWeakness(chessAccount = account, position = position, playerColor = "WHITE")
        whenever(userPositionWeaknessRepository.findByChessAccountIdAndPositionIdAndPlayerColor(account.id, position.id, "WHITE"))
            .thenReturn(weakness)

        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalMate = 1,
                bestMove = "Re8#",
                bestMoveEvalMate = 1,
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "Re8#", evalMate = 1, evalLossFromBest = 0.0),
        )
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(position.id)).thenReturn(analysis)

        whenever(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                setOf(position.id),
            ),
        ).thenReturn(listOf(occurrence))
        whenever(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "BLACK",
                setOf(position.id),
            ),
        ).thenReturn(emptyList())

        invokeEmitSchedulingEvents(listOf(game), user, setOf(position.id))

        val captor = argumentCaptor<PuzzleSchedulingEvent>()
        verify(puzzleSchedulingEventRepository, times(2)).saveAndFlush(captor.capture())

        val eventTypes = captor.allValues.map { it.eventType }
        assertTrue(eventTypes.contains(SchedulingEventType.GAME_REENCOUNTERED))
        assertTrue(eventTypes.contains(SchedulingEventType.GAME_HANDLED_SUCCESSFULLY))
    }

    @Test
    fun `emitSchedulingEvents suppresses GAME_HANDLED_SUCCESSFULLY when evaluation is missing or inconclusive`() {
        val game =
            Game(
                id = UUID.randomUUID(),
                chessAccount = account,
                platformGameId = "game_3",
                pgn = "1. a3",
                playedAt = Instant.now(),
            )
        val occurrence =
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "a3",
                playerColor = "WHITE",
            )

        val weakness = UserPositionWeakness(chessAccount = account, position = position, playerColor = "WHITE")
        whenever(userPositionWeaknessRepository.findByChessAccountIdAndPositionIdAndPlayerColor(account.id, position.id, "WHITE"))
            .thenReturn(weakness)

        // Position analysis exists, but candidate move has no evaluation (evalLoss is null)
        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalMate = 1,
                bestMove = "Re8#",
                bestMoveEvalMate = 1,
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "a3", evalLossFromBest = null),
        )
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(position.id)).thenReturn(analysis)

        whenever(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "WHITE",
                setOf(position.id),
            ),
        ).thenReturn(listOf(occurrence))
        whenever(
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                account.id,
                "BLACK",
                setOf(position.id),
            ),
        ).thenReturn(emptyList())

        invokeEmitSchedulingEvents(listOf(game), user, setOf(position.id))

        val captor = argumentCaptor<PuzzleSchedulingEvent>()
        // Only 1 event emitted: GAME_REENCOUNTERED. Outcome event is suppressed!
        verify(puzzleSchedulingEventRepository, times(1)).saveAndFlush(captor.capture())

        assertEquals(SchedulingEventType.GAME_REENCOUNTERED, captor.firstValue.eventType)
    }

    private fun invokeEmitSchedulingEvents(
        savedGames: List<Game>,
        initiatingUser: AppUser?,
        affectedPositionIds: Set<UUID>,
    ) {
        val method =
            GameImportService::class.java.getDeclaredMethod(
                "emitSchedulingEvents",
                List::class.java,
                AppUser::class.java,
                Set::class.java,
            ).apply { isAccessible = true }
        method.invoke(gameImportService, savedGames, initiatingUser, affectedPositionIds)
    }

    @Test
    fun `isAsyncJobLeaseStale correctly distinguishes expired from live leases`() {
        val method =
            GameImportService::class.java
                .getDeclaredMethod("isAsyncJobLeaseStale", AsyncJob::class.java, Instant::class.java)
                .apply { isAccessible = true }

        val now = Instant.parse("2026-01-01T12:00:00Z")
        val job = AsyncJob(username = "tester", platform = "CHESS_COM")

        job.leaseExpiresAt = null
        assertTrue(method.invoke(service, job, now) as Boolean, "null lease must be stale")

        job.leaseExpiresAt = now.minusSeconds(1)
        assertTrue(method.invoke(service, job, now) as Boolean, "past lease must be stale")

        job.leaseExpiresAt = now
        assertTrue(method.invoke(service, job, now) as Boolean, "boundary at now must be stale")

        job.leaseExpiresAt = now.plusSeconds(1)
        assertFalse(method.invoke(service, job, now) as Boolean, "future lease must not be stale")
    }

    @Test
    fun `createImportJob reclaims stale processing job and fails active processing job`() {
        val account = ChessAccount(id = UUID.randomUUID(), platform = "CHESS_COM", username = "tester")
        val request =
            ImportGamesRequest(
                accountId = account.id,
                platform = Platform.CHESS_COM,
                username = "tester",
                timeControls = listOf(TimeControl.RAPID),
                playerColor = PlayerColor.BOTH,
            )

        whenever(accountOwnershipService.resolveImportAccount(request, null)).thenReturn(account)
        whenever(chessAccountRepository.findByIdForUpdate(account.id)).thenReturn(account)

        // Scenario 1: Active job with valid unexpired lease throws ActiveImportJobException
        val activeJob =
            AsyncJob(
                id = UUID.randomUUID(),
                chessAccount = account,
                username = "tester",
                platform = "CHESS_COM",
                status = "PROCESSING",
                workerToken = UUID.randomUUID(),
                leaseExpiresAt = Instant.now().plus(Duration.ofMinutes(10)),
            )
        whenever(asyncJobRepository.findByChessAccountIdAndStatusIn(account.id, listOf("QUEUED", "PROCESSING"))).thenReturn(activeJob)

        val ex =
            assertThrows(ActiveImportJobException::class.java) {
                service.createImportJob(request)
            }
        assertTrue(ex.message!!.contains("An active import job already exists"))

        // Scenario 2: Stale lease job is marked FAILED and new job is created
        val staleJob =
            AsyncJob(
                id = UUID.randomUUID(),
                chessAccount = account,
                username = "tester",
                platform = "CHESS_COM",
                status = "PROCESSING",
                workerToken = UUID.randomUUID(),
                leaseExpiresAt = Instant.now().minus(Duration.ofMinutes(10)),
            )
        whenever(asyncJobRepository.findByChessAccountIdAndStatusIn(account.id, listOf("QUEUED", "PROCESSING"))).thenReturn(staleJob)
        whenever(asyncJobRepository.saveAndFlush(any<AsyncJob>())).thenAnswer { it.arguments[0] as AsyncJob }

        val createdJob = service.createImportJob(request)
        assertEquals("FAILED", staleJob.status)
        assertEquals("STALE_PROCESSING_JOB_RECLAIMED", staleJob.errorMessage)
        assertEquals("QUEUED", createdJob.status)
        assertEquals(AsyncJob.CONFIGURATION_READY, createdJob.configurationState)
        verify(asyncJobRepository).saveAndFlush(staleJob)
        verify(asyncJobRepository).saveAndFlush(createdJob)
    }

    @Test
    fun `claimForExecution reclaims stale processing job and executes import`() {
        val jobId = UUID.randomUUID()
        val account = ChessAccount(id = UUID.randomUUID(), platform = "CHESS_COM", username = "tester")
        val staleJob =
            AsyncJob(
                id = jobId,
                chessAccount = account,
                username = "tester",
                platform = "CHESS_COM",
                status = "PROCESSING",
                workerToken = UUID.randomUUID(),
                leaseExpiresAt = Instant.now().minus(Duration.ofMinutes(10)),
                timeControlsCsv = "RAPID",
                playerColor = "BOTH",
                configurationState = AsyncJob.CONFIGURATION_READY,
            )

        whenever(asyncJobRepository.findByIdForUpdate(jobId)).thenReturn(staleJob)
        whenever(asyncJobRepository.findAnyByIdForUpdate(jobId)).thenReturn(staleJob)
        whenever(asyncJobRepository.saveAndFlush(any<AsyncJob>())).thenAnswer { it.arguments[0] as AsyncJob }
        whenever(asyncJobRepository.save(any<AsyncJob>())).thenAnswer { it.arguments[0] as AsyncJob }
        whenever(importedArchiveRepository.findByChessAccount(account)).thenReturn(emptyList())
        whenever(chessComClient.fetchArchiveUrls("tester")).thenReturn(emptyList())
        whenever(engineAnalysisOrchestrator.analyzeAffectedPositions(any<Set<UUID>>())).thenReturn(AnalysisOutcome())

        service.executeImportJob(jobId)

        assertEquals("COMPLETED", staleJob.status)
        assertEquals("COMPLETED", staleJob.analysisStatus)
        assertNull(staleJob.workerToken)
        assertNull(staleJob.leaseExpiresAt)
        verify(asyncJobRepository, times(1)).saveAndFlush(staleJob)
        verify(asyncJobRepository, times(2)).save(staleJob)
    }

    @Test
    fun `claimForExecution marks job FAILED with INVALID_READY_JOB_CONFIGURATION when readiness validation fails`() {
        val jobId = UUID.randomUUID()
        val account = ChessAccount(id = UUID.randomUUID(), platform = "CHESS_COM", username = "tester")
        val invalidJob =
            AsyncJob(
                id = jobId,
                chessAccount = account,
                username = "tester",
                platform = "CHESS_COM",
                status = "QUEUED",
                timeControlsCsv = "",
                playerColor = "BOTH",
                configurationState = AsyncJob.CONFIGURATION_READY,
            )

        whenever(asyncJobRepository.findByIdForUpdate(jobId)).thenReturn(invalidJob)
        whenever(asyncJobRepository.saveAndFlush(any<AsyncJob>())).thenAnswer { it.arguments[0] as AsyncJob }

        service.executeImportJob(jobId)

        assertEquals("FAILED", invalidJob.status)
        assertEquals("INVALID_READY_JOB_CONFIGURATION", invalidJob.errorMessage)
        assertNull(invalidJob.workerToken)
        assertNull(invalidJob.leaseExpiresAt)
        verify(asyncJobRepository).saveAndFlush(invalidJob)
        verify(chessComClient, never()).fetchArchiveUrls(any())
    }

    @Test
    fun `claimForExecution protects job with live lease and skips execution`() {
        val jobId = UUID.randomUUID()
        val account = ChessAccount(id = UUID.randomUUID(), platform = "CHESS_COM", username = "tester")
        val liveJob =
            AsyncJob(
                id = jobId,
                chessAccount = account,
                username = "tester",
                platform = "CHESS_COM",
                status = "PROCESSING",
                workerToken = UUID.randomUUID(),
                leaseExpiresAt = Instant.now().plus(Duration.ofMinutes(20)),
                timeControlsCsv = "RAPID",
                playerColor = "BOTH",
                configurationState = AsyncJob.CONFIGURATION_READY,
            )

        whenever(asyncJobRepository.findByIdForUpdate(jobId)).thenReturn(liveJob)

        service.executeImportJob(jobId)

        verify(asyncJobRepository, never()).saveAndFlush(any())
        verify(chessComClient, never()).fetchArchiveUrls(any())
    }

    @Test
    fun `executeImportJob safely records partial batch failure without marking job completed`() {
        val jobId = UUID.randomUUID()
        val account = ChessAccount(id = UUID.randomUUID(), platform = "CHESS_COM", username = "tester")
        val job =
            AsyncJob(
                id = jobId,
                chessAccount = account,
                username = "tester",
                platform = "CHESS_COM",
                status = "QUEUED",
                timeControlsCsv = "RAPID",
                playerColor = "BOTH",
                configurationState = AsyncJob.CONFIGURATION_READY,
            )

        whenever(asyncJobRepository.findByIdForUpdate(jobId)).thenReturn(job)
        whenever(asyncJobRepository.findAnyByIdForUpdate(jobId)).thenReturn(job)
        whenever(asyncJobRepository.saveAndFlush(any<AsyncJob>())).thenAnswer { it.arguments[0] as AsyncJob }
        whenever(asyncJobRepository.save(any<AsyncJob>())).thenAnswer { it.arguments[0] as AsyncJob }
        whenever(importedArchiveRepository.findByChessAccount(account)).thenReturn(emptyList())

        val archiveUrls =
            listOf(
                "https://api.chess.com/pub/player/tester/games/2024/01",
                "https://api.chess.com/pub/player/tester/games/2024/02",
            )
        whenever(chessComClient.fetchArchiveUrls("tester")).thenReturn(archiveUrls)

        // First month succeeds with 0 games
        whenever(chessComClient.fetchMonthlyGames(archiveUrls[0])).thenReturn(emptyList())
        // Second month fails abruptly with a network exception
        whenever(chessComClient.fetchMonthlyGames(archiveUrls[1]))
            .thenThrow(org.springframework.web.client.ResourceAccessException("Connection reset by peer"))

        service.executeImportJob(jobId)

        assertEquals("FAILED", job.status, "Job must transition to FAILED on batch error, never COMPLETED")
        assertEquals("NOT_STARTED", job.analysisStatus)
        assertEquals("Connection reset by peer", job.errorMessage)
        verify(engineAnalysisOrchestrator, never()).analyzeAffectedPositions(any())
    }
}
