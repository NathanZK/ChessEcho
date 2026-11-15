package com.chessprobe.isolation

import com.chessprobe.AbstractIntegrationTest
import com.chessprobe.domain.AccountConnection
import com.chessprobe.domain.AppUser
import com.chessprobe.domain.ArchiveDerivedProcessing
import com.chessprobe.domain.ArchiveDerivedStatus
import com.chessprobe.domain.AsyncJob
import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.Game
import com.chessprobe.domain.ImportedArchive
import com.chessprobe.domain.Position
import com.chessprobe.domain.PositionOccurrence
import com.chessprobe.domain.PuzzleSchedulingEvent
import com.chessprobe.domain.SchedulingEventType
import com.chessprobe.domain.TrainingAttempt
import com.chessprobe.domain.TrainingAttemptMode
import com.chessprobe.domain.TrainingAttemptOutcome
import com.chessprobe.domain.UserPositionStats
import com.chessprobe.domain.UserPositionWeakness
import com.chessprobe.repository.ArchiveDerivedProcessingRepository
import com.chessprobe.repository.GameRepository
import com.chessprobe.repository.ImportedArchiveRepository
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.repository.PositionRepository
import com.chessprobe.repository.PuzzleSchedulingEventRepository
import com.chessprobe.repository.TrainingAttemptRepository
import com.chessprobe.repository.UserPositionStatsRepository
import com.chessprobe.repository.UserPositionWeaknessRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class DualTenantRepositoryIsolationTest : AbstractIntegrationTest() {
    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    private lateinit var userPositionWeaknessRepository: UserPositionWeaknessRepository

    @Autowired
    private lateinit var userPositionStatsRepository: UserPositionStatsRepository

    @Autowired
    private lateinit var trainingAttemptRepository: TrainingAttemptRepository

    @Autowired
    private lateinit var puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository

    @Autowired
    private lateinit var importedArchiveRepository: ImportedArchiveRepository

    @Autowired
    private lateinit var archiveDerivedProcessingRepository: ArchiveDerivedProcessingRepository

    private val tenantsToClean = mutableListOf<Pair<AppUser, ChessAccount>>()

    @AfterEach
    fun teardownTenants() {
        tenantsToClean.forEach { (user, account) ->
            val context = cleanTenantData(userId = user.id, accountId = account.id)
            assertTenantQuarantined(context)
        }
        tenantsToClean.clear()
    }

    private data class TenantFixture(
        val user: AppUser,
        val account: ChessAccount,
        val connection: AccountConnection,
    )

    private fun createTenantFixture(prefix: String): TenantFixture {
        val user = appUserRepository.saveAndFlush(AppUser(email = "$prefix-${UUID.randomUUID()}@example.com"))
        val account =
            chessAccountRepository.saveAndFlush(
                ChessAccount(
                    platform = "CHESS_COM",
                    username = "$prefix-${UUID.randomUUID().toString().take(8)}",
                ),
            )
        val connection =
            accountConnectionRepository.saveAndFlush(
                AccountConnection(
                    appUser = user,
                    chessAccount = account,
                ),
            )
        tenantsToClean.add(user to account)
        return TenantFixture(user, account, connection)
    }

    private fun createPosition(): Position =
        positionRepository.saveAndFlush(
            Position(
                hash = "hash-${UUID.randomUUID()}",
                fen = "8/8/8/8/8/8/8/8 w - - 0 1",
            ),
        )

    @Test
    fun `game repository isolates tenant queries and mutations`() {
        val tenantA = createTenantFixture("tenantA")
        val tenantB = createTenantFixture("tenantB")

        val gameA =
            gameRepository.saveAndFlush(
                Game(
                    chessAccount = tenantA.account,
                    platformGameId = "game-A-1",
                    pgn = "1. e4 e5",
                    timeControl = "RAPID",
                ),
            )
        val gameB =
            gameRepository.saveAndFlush(
                Game(
                    chessAccount = tenantB.account,
                    platformGameId = "game-B-1",
                    pgn = "1. d4 d5",
                    timeControl = "RAPID",
                ),
            )

        val gamesA = gameRepository.findAllByChessAccountOrderByPlayedAtDesc(tenantA.account)
        val gamesB = gameRepository.findAllByChessAccountOrderByPlayedAtDesc(tenantB.account)

        assertEquals(listOf(gameA.id), gamesA.map { it.id })
        assertEquals(listOf(gameB.id), gamesB.map { it.id })
        assertFalse(gameRepository.existsByChessAccountAndPlatformGameId(tenantA.account, gameB.platformGameId))
    }

    @Test
    fun `position occurrence repository isolates tenant occurrences`() {
        val tenantA = createTenantFixture("tenantA")
        val tenantB = createTenantFixture("tenantB")
        val position = createPosition()

        val gameA = gameRepository.saveAndFlush(Game(chessAccount = tenantA.account, platformGameId = "g-A", pgn = "1. e4"))
        val gameB = gameRepository.saveAndFlush(Game(chessAccount = tenantB.account, platformGameId = "g-B", pgn = "1. e4"))

        val occA =
            positionOccurrenceRepository.saveAndFlush(
                PositionOccurrence(
                    game = gameA,
                    position = position,
                    chessAccount = tenantA.account,
                    plyNumber = 1,
                    movePlayed = "e4",
                    playerColor = "WHITE",
                    decisionTimeMs = 5000L,
                ),
            )
        val occB =
            positionOccurrenceRepository.saveAndFlush(
                PositionOccurrence(
                    game = gameB,
                    position = position,
                    chessAccount = tenantB.account,
                    plyNumber = 1,
                    movePlayed = "e4",
                    playerColor = "WHITE",
                    decisionTimeMs = 3000L,
                ),
            )

        val tenantAOccurrences = positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(tenantA.account.id, "WHITE")
        val tenantBOccurrences = positionOccurrenceRepository.findByChessAccountIdAndPlayerColor(tenantB.account.id, "WHITE")

        assertEquals(listOf(occA.id), tenantAOccurrences.map { it.id })
        assertEquals(listOf(occB.id), tenantBOccurrences.map { it.id })
    }

    @Test
    fun `weakness and position stats repositories isolate tenant state`() {
        val tenantA = createTenantFixture("tenantA")
        val tenantB = createTenantFixture("tenantB")
        val position = createPosition()

        val weaknessA =
            userPositionWeaknessRepository.saveAndFlush(
                UserPositionWeakness(
                    chessAccount = tenantA.account,
                    position = position,
                    playerColor = "WHITE",
                    mistakeCount = 3,
                ),
            )
        val statsA =
            userPositionStatsRepository.saveAndFlush(
                UserPositionStats(
                    chessAccount = tenantA.account,
                    position = position,
                    playerColor = "WHITE",
                    timesReached = 5,
                ),
            )

        val weaknessB =
            userPositionWeaknessRepository.saveAndFlush(
                UserPositionWeakness(
                    chessAccount = tenantB.account,
                    position = position,
                    playerColor = "WHITE",
                    mistakeCount = 1,
                ),
            )
        val statsB =
            userPositionStatsRepository.saveAndFlush(
                UserPositionStats(
                    chessAccount = tenantB.account,
                    position = position,
                    playerColor = "WHITE",
                    timesReached = 2,
                ),
            )

        val retrievedWeaknessesA =
            userPositionWeaknessRepository
                .findByChessAccountIdAndPlayerColorAndMistakeCountGreaterThanEqualOrderByPriorityDesc(
                    tenantA.account.id,
                    "WHITE",
                    1,
                )
        val retrievedStatsA =
            userPositionStatsRepository.findByChessAccountIdAndPlayerColor(tenantA.account.id, "WHITE")

        assertEquals(listOf(weaknessA.id), retrievedWeaknessesA.map { it.id })
        assertEquals(listOf(statsA.id), retrievedStatsA.map { it.id })
        assertFalse(retrievedWeaknessesA.any { it.chessAccount.id == tenantB.account.id })
        assertFalse(retrievedStatsA.any { it.chessAccount.id == tenantB.account.id })
        assertEquals(1, userPositionWeaknessRepository.findAllById(listOf(weaknessB.id)).size)
        assertEquals(1, userPositionStatsRepository.findAllById(listOf(statsB.id)).size)
    }

    @Test
    fun `training attempt repository isolates tenant queries`() {
        val tenantA = createTenantFixture("tenantA")
        val tenantB = createTenantFixture("tenantB")

        val attemptA =
            trainingAttemptRepository.saveAndFlush(
                TrainingAttempt(
                    puzzleId = "puzzle-100",
                    mode = TrainingAttemptMode.STOPWATCH,
                    elapsedMs = 4500L,
                    outcome = TrainingAttemptOutcome.SUBMITTED,
                    chessAccount = tenantA.account,
                    appUser = tenantA.user,
                ),
            )
        val attemptB =
            trainingAttemptRepository.saveAndFlush(
                TrainingAttempt(
                    puzzleId = "puzzle-200",
                    mode = TrainingAttemptMode.STOPWATCH,
                    elapsedMs = 6200L,
                    outcome = TrainingAttemptOutcome.SUBMITTED,
                    chessAccount = tenantB.account,
                    appUser = tenantB.user,
                ),
            )

        assertNotNull(trainingAttemptRepository.findByIdAndPuzzleId(attemptA.id, "puzzle-100"))
        assertNull(trainingAttemptRepository.findByIdAndPuzzleId(attemptA.id, "puzzle-200"))
        assertNotNull(trainingAttemptRepository.findByIdAndPuzzleId(attemptB.id, "puzzle-200"))
        assertEquals(attemptA.appUser?.id, tenantA.user.id)
        assertEquals(attemptB.appUser?.id, tenantB.user.id)
    }

    @Test
    fun `puzzle scheduling event repository isolates tenant queries`() {
        val tenantA = createTenantFixture("tenantA")
        val tenantB = createTenantFixture("tenantB")
        val position = createPosition()
        val submissionA = UUID.randomUUID()
        val submissionB = UUID.randomUUID()

        val eventA =
            puzzleSchedulingEventRepository.saveAndFlush(
                PuzzleSchedulingEvent(
                    appUser = tenantA.user,
                    chessAccount = tenantA.account,
                    position = position,
                    playerColor = "WHITE",
                    eventType = SchedulingEventType.SOLVED,
                    submissionId = submissionA,
                    submittedMove = "e4",
                ),
            )
        val eventB =
            puzzleSchedulingEventRepository.saveAndFlush(
                PuzzleSchedulingEvent(
                    appUser = tenantB.user,
                    chessAccount = tenantB.account,
                    position = position,
                    playerColor = "WHITE",
                    eventType = SchedulingEventType.FAILED,
                    submissionId = submissionB,
                    submittedMove = "d4",
                ),
            )

        assertNotNull(puzzleSchedulingEventRepository.findByAppUserIdAndSubmissionId(tenantA.user.id, submissionA))
        assertNull(puzzleSchedulingEventRepository.findByAppUserIdAndSubmissionId(tenantA.user.id, submissionB))
        assertNotNull(puzzleSchedulingEventRepository.findByAppUserIdAndSubmissionId(tenantB.user.id, submissionB))
        assertEquals(eventA.appUser?.id, tenantA.user.id)
        assertEquals(eventB.appUser?.id, tenantB.user.id)
    }

    @Test
    fun `async job repository isolates tenant records`() {
        val tenantA = createTenantFixture("tenantA")
        val tenantB = createTenantFixture("tenantB")

        val jobA =
            asyncJobRepository.saveAndFlush(
                AsyncJob(
                    username = tenantA.account.username,
                    platform = "CHESS_COM",
                    chessAccount = tenantA.account,
                    timeControlsCsv = "RAPID",
                    status = "COMPLETED",
                ),
            )
        val jobB =
            asyncJobRepository.saveAndFlush(
                AsyncJob(
                    username = tenantB.account.username,
                    platform = "CHESS_COM",
                    chessAccount = tenantB.account,
                    timeControlsCsv = "RAPID",
                    status = "COMPLETED",
                ),
            )

        val foundJobA = asyncJobRepository.findByChessAccountIdAndStatusIn(tenantA.account.id, listOf("COMPLETED"))
        val foundJobB = asyncJobRepository.findByChessAccountIdAndStatusIn(tenantB.account.id, listOf("COMPLETED"))
        assertEquals(jobA.id, foundJobA?.id)
        assertEquals(jobB.id, foundJobB?.id)
    }

    @Test
    fun `imported archive and derived processing repositories isolate tenant records`() {
        val tenantA = createTenantFixture("tenantA")
        val tenantB = createTenantFixture("tenantB")

        val archiveA =
            importedArchiveRepository.saveAndFlush(
                ImportedArchive(
                    chessAccount = tenantA.account,
                    archiveUrl = "https://api.chess.com/pub/player/a/games/2024/01",
                    yearMonth = "2024-01",
                ),
            )
        val archiveB =
            importedArchiveRepository.saveAndFlush(
                ImportedArchive(
                    chessAccount = tenantB.account,
                    archiveUrl = "https://api.chess.com/pub/player/b/games/2024/01",
                    yearMonth = "2024-01",
                ),
            )

        val procA =
            archiveDerivedProcessingRepository.saveAndFlush(
                ArchiveDerivedProcessing(
                    importedArchive = archiveA,
                    status = ArchiveDerivedStatus.COMPLETED,
                ),
            )
        val procB =
            archiveDerivedProcessingRepository.saveAndFlush(
                ArchiveDerivedProcessing(
                    importedArchive = archiveB,
                    status = ArchiveDerivedStatus.COMPLETED,
                ),
            )

        val archivesA = importedArchiveRepository.findByChessAccount(tenantA.account)
        val archivesB = importedArchiveRepository.findByChessAccount(tenantB.account)
        assertEquals(listOf(archiveA.id), archivesA.map { it.id })
        assertEquals(listOf(archiveB.id), archivesB.map { it.id })

        assertEquals(procA.id, archiveDerivedProcessingRepository.findByImportedArchive(archiveA)?.id)
        assertEquals(procB.id, archiveDerivedProcessingRepository.findByImportedArchive(archiveB)?.id)
    }

    @Test
    fun `account connection repository isolates tenant ownership and mutation does not affect other tenant`() {
        val tenantA = createTenantFixture("tenantA")
        val tenantB = createTenantFixture("tenantB")

        assertTrue(accountConnectionRepository.existsByAppUserIdAndChessAccountId(tenantA.user.id, tenantA.account.id))
        assertFalse(accountConnectionRepository.existsByAppUserIdAndChessAccountId(tenantA.user.id, tenantB.account.id))
        assertTrue(accountConnectionRepository.existsByAppUserIdAndChessAccountId(tenantB.user.id, tenantB.account.id))
        assertFalse(accountConnectionRepository.existsByAppUserIdAndChessAccountId(tenantB.user.id, tenantA.account.id))

        accountConnectionRepository.delete(tenantA.connection)
        accountConnectionRepository.flush()

        assertFalse(accountConnectionRepository.existsByAppUserIdAndChessAccountId(tenantA.user.id, tenantA.account.id))
        assertTrue(accountConnectionRepository.existsByAppUserIdAndChessAccountId(tenantB.user.id, tenantB.account.id))
    }
}
