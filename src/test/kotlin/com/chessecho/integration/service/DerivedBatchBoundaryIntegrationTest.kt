package com.chessecho.integration.service

import com.chessecho.domain.ArchiveDerivedStatus
import com.chessecho.domain.Game
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.TimeControl
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ArchiveDerivedProcessingRepository
import com.chessecho.repository.AsyncJobRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.ImportedArchiveRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.UserPositionStatsRepository
import com.chessecho.service.ChessComClient
import com.chessecho.service.EngineAnalysisOrchestrator
import com.chessecho.service.GameImportService
import com.chessecho.service.GameParserService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Evidence for the derived-processing batch boundary.
 *
 * The production default is 1,000 games per batch (see
 * `GameImportService.DEFAULT_DERIVED_GAME_BATCH_SIZE` for the repository
 * evidence behind that number). The property asserted here is the boundary
 * itself, which is what actually bounds transaction and lock lifetime, so the
 * batch size is turned down to one game to make the boundary observable with a
 * small deterministic archive:
 *
 *  - the parser is invoked once per batch, never once per archive;
 *  - each batch is committed before the next batch starts;
 *  - a failure in a later batch leaves earlier batches durably committed, which
 *    an archive-wide derived transaction could not do.
 *
 * This bounds derived lock lifetime to one batch; it does not claim to eliminate
 * deadlocks.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["chessecho.import.derived-game-batch-size=1"])
class DerivedBatchBoundaryIntegrationTest {
    @Autowired
    private lateinit var gameImportService: GameImportService

    @Autowired
    private lateinit var asyncJobRepository: AsyncJobRepository

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var importedArchiveRepository: ImportedArchiveRepository

    @Autowired
    private lateinit var archiveDerivedProcessingRepository: ArchiveDerivedProcessingRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    private lateinit var userPositionStatsRepository: UserPositionStatsRepository

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @MockBean
    private lateinit var chessComClient: ChessComClient

    @MockBean
    private lateinit var engineAnalysisOrchestrator: EngineAnalysisOrchestrator

    @SpyBean
    private lateinit var gameParserService: GameParserService

    @BeforeEach
    fun setup() {
        whenever(chessComClient.fetchArchiveUrls(any()))
            .thenReturn(listOf("https://api.chess.com/pub/player/batched/games/2024/01"))
        whenever(chessComClient.fetchMonthlyGames(any())).thenReturn(
            (1..3).map { index ->
                mapOf(
                    "url" to "https://www.chess.com/game/live/3000$index",
                    "pgn" to pgn(index),
                    "time_class" to "blitz",
                    "rules" to "chess",
                    "end_time" to (1704067200L + index),
                    "white" to mapOf("username" to "batched", "result" to "win"),
                    "black" to mapOf("username" to "opponent$index", "result" to "resigned"),
                )
            },
        )
    }

    @AfterEach
    fun tearDown() {
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
    fun `derived processing commits per batch so a later batch failure keeps earlier batches`() {
        val batchSizes = mutableListOf<Int>()
        val committedBeforeBatch = mutableListOf<Long>()
        doAnswer { invocation ->
            val batch = invocation.getArgument<List<Game>>(0)
            batchSizes.add(batch.size)
            // Read outside the parser transaction: whatever is visible here was
            // committed by an earlier batch, not merely buffered.
            committedBeforeBatch.add(positionOccurrenceRepository.count())
            if (batchSizes.size == 3) {
                throw IllegalStateException("simulated failure in the third derived batch")
            }
            invocation.callRealMethod()
        }.whenever(gameParserService).parseAndSavePositions(any())

        val job = gameImportService.createImportJob(importRequest())
        gameImportService.executeImportJob(job.id)
        val terminal = awaitTerminalJob(job.id)

        assertEquals("FAILED", terminal.status, "a derived batch failure must fail the import job")
        assertEquals(
            listOf(1, 1, 1),
            batchSizes,
            "the parser must be invoked once per configured batch, not once per archive",
        )
        assertEquals(0L, committedBeforeBatch[0], "no derived work is committed before the first batch")
        assertTrue(
            committedBeforeBatch[1] > 0,
            "batch one must be committed before batch two begins, so the transaction boundary is per batch",
        )
        assertTrue(
            committedBeforeBatch[2] > committedBeforeBatch[1],
            "batch two must also be committed before batch three begins",
        )

        val account = assertNotNull(chessAccountRepository.findByPlatformAndUsernameIgnoreCase("CHESS_COM", "batched"))
        assertEquals(3, gameRepository.findAllByChessAccountOrderByPlayedAtDesc(account).size, "raw games stay atomic")
        assertEquals(
            committedBeforeBatch[2],
            positionOccurrenceRepository.count(),
            "the failed batch contributes nothing while the earlier batches survive",
        )
        val archive = importedArchiveRepository.findByChessAccount(account).single()
        val processing = assertNotNull(archiveDerivedProcessingRepository.findByImportedArchive(archive))
        assertEquals(ArchiveDerivedStatus.FAILED, processing.status)
    }

    private fun pgn(index: Int): String =
        """
        [Event "Live Chess"]
        [Site "Chess.com"]
        [Date "2024.01.0$index"]
        [White "batched"]
        [Black "opponent$index"]
        [Result "1-0"]

        ${openingFor(index)} 1-0
        """.trimIndent()

    private fun openingFor(index: Int): String =
        when (index) {
            1 -> "1. e4 e5 2. Nf3 Nc6"
            2 -> "1. d4 d5 2. c4 e6"
            else -> "1. c4 c5 2. Nc3 Nc6"
        }

    private fun importRequest(): ImportGamesRequest =
        ImportGamesRequest(
            username = "batched",
            platform = Platform.CHESS_COM,
            timeControls = listOf(TimeControl.BLITZ),
            playerColor = PlayerColor.BOTH,
        )

    private fun awaitTerminalJob(jobId: UUID): com.chessecho.domain.AsyncJob {
        repeat(50) {
            val current = asyncJobRepository.findById(jobId).orElse(null)
            if (current != null && current.status in listOf("COMPLETED", "FAILED")) {
                return current
            }
            Thread.sleep(100)
        }
        error("Import job $jobId did not complete within timeout")
    }
}
