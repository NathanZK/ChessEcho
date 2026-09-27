package com.chessecho.service

import com.chessecho.domain.HumanMoveCorpusRunStatus
import com.chessecho.domain.HumanMoveDistribution
import com.chessecho.domain.Position
import com.chessecho.dto.HumanMoveBfsRequest
import com.chessecho.dto.HumanMoveCorpusRunRequest
import com.chessecho.dto.HumanMoveCorpusRunResponse
import com.chessecho.repository.HumanMoveBfsSeenGameClaimer
import com.chessecho.repository.HumanMoveBfsSeenGameRepository
import com.chessecho.repository.HumanMoveCorpusRunRepository
import com.chessecho.repository.HumanMoveDistributionRepository
import com.chessecho.repository.PositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.TransientDataAccessResourceException
import java.time.Instant
import java.util.UUID

/**
 * Unit coverage for the Issue #423 corpus path: traversal parity with the
 * legacy `/bfs` path, qualification provenance, rejected-contribution policy,
 * bounded per-game commit retry, and terminal run status selection.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HumanMoveCorpusServiceTest {
    @Mock
    private lateinit var chessComClient: ChessComClient

    @Mock
    private lateinit var positionRepository: PositionRepository

    @Mock
    private lateinit var humanMoveDistributionRepository: HumanMoveDistributionRepository

    @Mock
    private lateinit var humanMoveBfsSeenGameRepository: HumanMoveBfsSeenGameRepository

    @Mock
    private lateinit var humanMoveBfsSeenGameClaimer: HumanMoveBfsSeenGameClaimer

    @Mock
    private lateinit var gameWriter: HumanMoveCorpusGameWriter

    @Mock
    private lateinit var runRepository: HumanMoveCorpusRunRepository

    private lateinit var corpusService: HumanMoveCorpusService

    private val runId: UUID = UUID.randomUUID()
    private val committedCandidates = mutableListOf<HumanMoveCorpusCandidate>()
    private val finishOutcomes = mutableListOf<HumanMoveCorpusRunOutcome>()
    private val legacyClaimedUrls = mutableListOf<String>()
    private val legacyPositionHashById = mutableMapOf<UUID, String>()
    private val legacyDistributionRows = mutableListOf<HumanMoveDistribution>()

    @BeforeEach
    fun setUp() {
        corpusService = HumanMoveCorpusService(chessComClient, gameWriter, runRepository)

        whenever(gameWriter.createRun(any())).thenReturn(runId)
        whenever(gameWriter.commitGame(any(), any())).thenAnswer {
            val candidate = it.arguments[1] as HumanMoveCorpusCandidate
            committedCandidates += candidate
            HumanMoveCorpusCommitResult(HumanMoveCorpusCommitOutcome.COMMITTED, committedCandidates.size)
        }
        whenever(gameWriter.finishRun(any(), any())).thenAnswer {
            val outcome = it.arguments[1] as HumanMoveCorpusRunOutcome
            finishOutcomes += outcome
            runResponse(outcome)
        }

        whenever(humanMoveBfsSeenGameRepository.findExistingGameUrls(any())).thenReturn(emptyList())
        doAnswer {
            @Suppress("UNCHECKED_CAST")
            legacyClaimedUrls += (it.arguments[0] as Collection<String>)
            null
        }.whenever(humanMoveBfsSeenGameClaimer).claimGameUrls(any())
        whenever(positionRepository.saveAll(any<Iterable<Position>>())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            val saved = (it.arguments[0] as Iterable<Position>).toList()
            saved.forEach { position -> legacyPositionHashById[position.id] = position.hash }
            saved
        }
        whenever(humanMoveDistributionRepository.saveAll(any<Iterable<HumanMoveDistribution>>())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            val saved = (it.arguments[0] as Iterable<HumanMoveDistribution>).toList()
            legacyDistributionRows += saved
            saved
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private val standardPgn = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 e5 2. Nf3 Nc6 1-0"

    private fun game(
        url: String,
        white: String,
        whiteRating: Int,
        black: String,
        blackRating: Int,
        timeClass: String = "rapid",
        rules: String = "chess",
        pgn: String = standardPgn,
    ): Map<String, Any> =
        mapOf(
            "url" to url,
            "rules" to rules,
            "time_class" to timeClass,
            "white" to mapOf("username" to white, "rating" to whiteRating),
            "black" to mapOf("username" to black, "rating" to blackRating),
            "pgn" to pgn,
        )

    private fun archive(player: String) = "https://api.chess.com/pub/player/$player/games/2024/01"

    private fun archiveOlder(player: String) = "https://api.chess.com/pub/player/$player/games/2023/12"

    /**
     * A traversal world that exercises exclusions, out-of-band opponents,
     * non-RAPID and non-standard-rules games, duplicate discovery through a
     * second player, multiple depths, and multiple archives per player.
     * Chess.com returns games oldest-first; traversal reads newest-first.
     */
    private fun stubTraversalWorld() {
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archiveOlder("p1"), archive("p1")))
        whenever(chessComClient.fetchMonthlyGames(archiveOlder("p1"))).thenReturn(
            listOf(
                game("http://g-old-1", "p1", 1300, "p8", 1120),
                game("http://g-old-2", "P9", 1010, "p1", 1300),
            ),
        )
        whenever(chessComClient.fetchMonthlyGames(archive("p1"))).thenReturn(
            listOf(
                game("http://g6", "p1", 1300, "p10", 1100, rules = "chess960"),
                game("http://g5", "p1", 1300, "excluded", 1100),
                game("http://g4", "p1", 1300, "p5", 1500),
                game("http://g3", "p1", 1300, "p4", 1100, timeClass = "blitz"),
                game("http://g2", "P3", 1150, "p1", 1300),
                game("http://g1", "p1", 1300, "p2", 1100),
            ),
        )
        whenever(chessComClient.fetchArchiveUrls("p2")).thenReturn(listOf(archive("p2")))
        whenever(chessComClient.fetchMonthlyGames(archive("p2"))).thenReturn(
            listOf(
                game("http://g7", "p2", 1100, "p6", 1050),
                game("http://g1", "p1", 1300, "p2", 1100),
            ),
        )
        whenever(chessComClient.fetchArchiveUrls("p3")).thenReturn(emptyList())
        whenever(chessComClient.fetchArchiveUrls("p5")).thenReturn(listOf(archive("p5")))
        whenever(chessComClient.fetchMonthlyGames(archive("p5"))).thenReturn(
            listOf(game("http://g8", "p7", 1199, "p5", 1500)),
        )
        listOf("p4", "p6", "p7", "p8", "p9", "p10").forEach {
            whenever(chessComClient.fetchArchiveUrls(it)).thenReturn(emptyList())
        }
    }

    private data class Bounds(
        val maxQualifyingGames: Int? = null,
        val maxGamesPerPlayer: Int = 100,
        val maxPlayers: Int? = null,
        val maxDepth: Int? = null,
    )

    private fun corpusRequest(
        bounds: Bounds,
        seeds: List<String> = listOf("p1"),
        excluded: List<String> = listOf("excluded"),
        sourceRevision: String = "rev-test",
        ratingBand: String = "1000-1200",
    ) = HumanMoveCorpusRunRequest(
        ratingBand = ratingBand,
        seedPlayers = seeds,
        excludedPlayers = excluded,
        maxQualifyingGames = bounds.maxQualifyingGames,
        maxGamesPerPlayer = bounds.maxGamesPerPlayer,
        maxPlayers = bounds.maxPlayers,
        maxDepth = bounds.maxDepth,
        sourceRevision = sourceRevision,
    )

    private fun legacyRequest(
        bounds: Bounds,
        seeds: List<String> = listOf("p1"),
        excluded: List<String> = listOf("excluded"),
    ) = HumanMoveBfsRequest(
        ratingBand = "1000-1200",
        seedPlayers = seeds,
        excludedPlayers = excluded,
        maxQualifyingGames = bounds.maxQualifyingGames,
        maxGamesPerPlayer = bounds.maxGamesPerPlayer,
        maxPlayers = bounds.maxPlayers,
        maxDepth = bounds.maxDepth,
    )

    private fun legacyService() =
        HumanMoveBfsService(
            chessComClient,
            positionRepository,
            humanMoveDistributionRepository,
            humanMoveBfsSeenGameRepository,
            humanMoveBfsSeenGameClaimer,
        )

    private fun runResponse(outcome: HumanMoveCorpusRunOutcome) =
        HumanMoveCorpusRunResponse(
            runId = runId,
            ratingBand = "1000-1200",
            status = outcome.status,
            seedPlayers = listOf("p1"),
            excludedPlayers = emptyList(),
            maxQualifyingGames = null,
            maxGamesPerPlayer = 100,
            maxPlayers = null,
            maxDepth = null,
            batchSize = 5000,
            algorithmVersion = HumanMoveCorpusService.ALGORITHM_VERSION,
            sourceRevision = "rev-test",
            sourceRevisionProvenance = "SELF_REPORTED",
            requestSha256 = "0".repeat(64),
            committedFrontier = committedCandidates.size,
            rejectedGameCount = outcome.rejectedGameCount,
            archiveFetchFailureCount = outcome.archiveFetchFailureCount,
            stopReason = outcome.stopReason,
            failureDetails = outcome.failureDetails,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            finishedAt = Instant.EPOCH,
        )

    private fun legacyAggregate(): Map<Pair<String, String>, Int> =
        legacyDistributionRows
            .groupBy { Pair(legacyPositionHashById.getValue(it.positionId), it.movePlayed) }
            .mapValues { (_, rows) -> rows.sumOf { it.observationCount } }

    private fun corpusAggregate(): Map<Pair<String, String>, Int> =
        committedCandidates
            .flatMap { candidate -> candidate.observations }
            .groupBy { Pair(it.positionHash, it.movePlayed) }
            .mapValues { (_, rows) -> rows.sumOf { it.observationCount } }

    private fun resetRecorders() {
        committedCandidates.clear()
        finishOutcomes.clear()
        legacyClaimedUrls.clear()
        legacyPositionHashById.clear()
        legacyDistributionRows.clear()
    }

    // ── Traversal parity ────────────────────────────────────────────────────

    @Test
    fun `corpus traversal matches legacy qualifying order, aggregate, and stop reason for every bound shape`() {
        stubTraversalWorld()
        val cases =
            listOf(
                Bounds(maxDepth = 5),
                Bounds(maxDepth = 0),
                Bounds(maxDepth = 1),
                Bounds(maxPlayers = 1),
                Bounds(maxPlayers = 2),
                Bounds(maxPlayers = 50),
                Bounds(maxQualifyingGames = 1),
                Bounds(maxQualifyingGames = 2),
                Bounds(maxQualifyingGames = 4),
                Bounds(maxQualifyingGames = 50),
                Bounds(maxQualifyingGames = 3, maxGamesPerPlayer = 1),
                Bounds(maxDepth = 5, maxGamesPerPlayer = 2),
                Bounds(maxDepth = 5, maxGamesPerPlayer = 5),
                Bounds(maxPlayers = 3, maxDepth = 1, maxQualifyingGames = 3),
            )

        cases.forEach { bounds ->
            resetRecorders()
            val legacy = legacyService().runBfs(legacyRequest(bounds))
            val corpus = corpusService.runCorpus(corpusRequest(bounds))

            assertEquals(legacyClaimedUrls, committedCandidates.map { it.providerGameId }, "qualifying order for $bounds")
            assertEquals(legacy.qualifyingGames, committedCandidates.size, "qualifying count for $bounds")
            assertEquals(legacyAggregate(), corpusAggregate(), "pre-threshold aggregate for $bounds")
            assertEquals(legacy.stopReason, corpus.stopReason, "stop reason for $bounds")
            assertEquals(HumanMoveCorpusRunStatus.COMPLETED, corpus.status, "status for $bounds")
            assertEquals(1, finishOutcomes.size, "run finished exactly once for $bounds")
        }
    }

    @Test
    fun `duplicate discovery, exclusions, out-of-band, non-rapid and non-chess games never become members`() {
        stubTraversalWorld()

        corpusService.runCorpus(corpusRequest(Bounds(maxDepth = 5)))

        val urls = committedCandidates.map { it.providerGameId }
        assertEquals(urls.distinct(), urls, "a game is committed at most once per run")
        assertTrue("http://g1" in urls)
        listOf("http://g3", "http://g4", "http://g5", "http://g6").forEach {
            assertTrue(it !in urls, "$it must not be a corpus member")
        }
        assertTrue(committedCandidates.none { it.opponent == "excluded" })
    }

    @Test
    fun `run-scoped maxGamesPerPlayer continues into an older archive like the legacy new-game budget`() {
        stubTraversalWorld()

        corpusService.runCorpus(corpusRequest(Bounds(maxPlayers = 1, maxGamesPerPlayer = 5)))

        // The newest archive consumes 4 of the 5-game new-RAPID budget (g1, g2, g4, and
        // the excluded-opponent g5, exactly as in the legacy path); the older archive
        // supplies the fifth game.
        val urls = committedCandidates.map { it.providerGameId }
        assertEquals(listOf("http://g1", "http://g2", "http://g-old-2"), urls)
    }

    // ── Qualification provenance ────────────────────────────────────────────

    @Test
    fun `provenance records traversed player, opponent, side, rating, rules, time class, depth, and source pgn`() {
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archive("p1")))
        whenever(chessComClient.fetchMonthlyGames(archive("p1"))).thenReturn(
            listOf(
                game("http://as-black", "Opp-White", 1150, "p1", 1300),
                game("http://as-white", "p1", 1300, "Opp-Black", 1100),
            ),
        )
        whenever(chessComClient.fetchArchiveUrls("opp-white")).thenReturn(emptyList())
        whenever(chessComClient.fetchArchiveUrls("opp-black")).thenReturn(emptyList())

        corpusService.runCorpus(corpusRequest(Bounds(maxDepth = 0), excluded = emptyList()))

        assertEquals(listOf("http://as-white", "http://as-black"), committedCandidates.map { it.providerGameId })
        val black = committedCandidates[0]
        assertEquals("p1", black.traversedPlayer)
        assertEquals("opp-black", black.opponent)
        assertEquals(HumanMoveCorpusSide.BLACK, black.opponentSide)
        assertEquals(1100, black.opponentRating)
        assertEquals("chess", black.rules)
        assertEquals("rapid", black.timeClass)
        assertEquals(0, black.bfsDepth)
        assertEquals(standardPgn, black.pgn)
        assertEquals(setOf("e5", "Nc6"), black.observations.map { it.movePlayed }.toSet())

        val white = committedCandidates[1]
        assertEquals("opp-white", white.opponent)
        assertEquals(HumanMoveCorpusSide.WHITE, white.opponentSide)
        assertEquals(1150, white.opponentRating)
        assertEquals(setOf("e4", "Nf3"), white.observations.map { it.movePlayed }.toSet())
        committedCandidates.forEach { candidate ->
            assertTrue(candidate.observations.all { it.observationCount >= 1 && it.fen.isNotBlank() })
            assertEquals(2, candidate.observations.sumOf { it.observationCount })
        }
    }

    @Test
    fun `depth is recorded as the traversed player's BFS depth`() {
        stubTraversalWorld()

        corpusService.runCorpus(corpusRequest(Bounds(maxDepth = 5)))

        val byUrl = committedCandidates.associateBy { it.providerGameId }
        assertEquals(0, byUrl.getValue("http://g1").bfsDepth)
        assertEquals(1, byUrl.getValue("http://g7").bfsDepth)
        assertEquals("p2", byUrl.getValue("http://g7").traversedPlayer)
    }

    // ── Rejected contributions ──────────────────────────────────────────────

    @Test
    fun `malformed, non-standard-start, and zero-observation games are rejected and do not count toward the cap`() {
        val nonStandard =
            "[Event \"Live Chess\"]\n[SetUp \"1\"]\n[FEN \"4k3/8/8/8/8/8/8/4K3 w - - 0 1\"]\n[Result \"*\"]\n\n1. Kd2 Kd7 *"
        val zeroOpponentMoves = "[Event \"Live Chess\"]\n[Result \"1-0\"]\n\n1. e4 1-0"
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archive("p1")))
        whenever(chessComClient.fetchMonthlyGames(archive("p1"))).thenReturn(
            listOf(
                game("http://good", "p1", 1300, "p2", 1100),
                game("http://zero", "p1", 1300, "p3", 1100, pgn = zeroOpponentMoves),
                game("http://nonstandard", "p1", 1300, "p4", 1100, pgn = nonStandard),
                game("http://malformed", "p1", 1300, "p5", 1100, pgn = "this is not a pgn {{{"),
            ),
        )
        listOf("p2", "p3", "p4", "p5").forEach { whenever(chessComClient.fetchArchiveUrls(it)).thenReturn(emptyList()) }

        val legacy = legacyService().runBfs(legacyRequest(Bounds(maxQualifyingGames = 1), excluded = emptyList()))
        val corpus = corpusService.runCorpus(corpusRequest(Bounds(maxQualifyingGames = 1), excluded = emptyList()))

        assertEquals(1, legacy.qualifyingGames, "legacy still counts the rejected game (unchanged)")
        assertEquals(
            emptyList<String>(),
            legacyClaimedUrls,
            "legacy behaviour unchanged: an observation-free batch is never flushed or claimed",
        )

        assertEquals(listOf("http://good"), committedCandidates.map { it.providerGameId })
        val outcome = finishOutcomes.single()
        assertEquals(3, outcome.rejectedGameCount)
        assertEquals(HumanMoveCorpusRunStatus.COMPLETED, outcome.status)
        assertEquals("MAX_QUALIFYING_GAMES", corpus.stopReason)
    }

    // ── Bounded retry and terminal status ───────────────────────────────────

    private fun stubThreeQualifyingGames() {
        whenever(chessComClient.fetchArchiveUrls("p1")).thenReturn(listOf(archive("p1")))
        whenever(chessComClient.fetchMonthlyGames(archive("p1"))).thenReturn(
            listOf(
                game("http://c", "p1", 1300, "p4", 1100),
                game("http://b", "p1", 1300, "p3", 1100),
                game("http://a", "p1", 1300, "p2", 1100),
            ),
        )
        listOf("p2", "p3", "p4").forEach { whenever(chessComClient.fetchArchiveUrls(it)).thenReturn(emptyList()) }
    }

    @Test
    fun `two transient commit failures followed by success yield exactly one contribution`() {
        stubThreeQualifyingGames()
        val attemptsByUrl = mutableMapOf<String, Int>()
        whenever(gameWriter.commitGame(any(), any())).thenAnswer {
            val candidate = it.arguments[1] as HumanMoveCorpusCandidate
            val attempt = (attemptsByUrl[candidate.providerGameId] ?: 0) + 1
            attemptsByUrl[candidate.providerGameId] = attempt
            if (candidate.providerGameId == "http://b" && attempt <= 2) {
                throw TransientDataAccessResourceException("transient failure $attempt")
            }
            committedCandidates += candidate
            HumanMoveCorpusCommitResult(HumanMoveCorpusCommitOutcome.COMMITTED, committedCandidates.size)
        }

        val response = corpusService.runCorpus(corpusRequest(Bounds(maxDepth = 0), excluded = emptyList()))

        assertEquals(3, attemptsByUrl["http://b"])
        assertEquals(listOf("http://a", "http://b", "http://c"), committedCandidates.map { it.providerGameId })
        assertEquals(HumanMoveCorpusRunStatus.COMPLETED, response.status)
    }

    @Test
    fun `lost acknowledgment resolved as already committed counts once and continues`() {
        stubThreeQualifyingGames()
        var bAttempts = 0
        whenever(gameWriter.commitGame(any(), any())).thenAnswer {
            val candidate = it.arguments[1] as HumanMoveCorpusCandidate
            if (candidate.providerGameId == "http://b") {
                bAttempts++
                if (bAttempts == 1) {
                    committedCandidates += candidate
                    throw TransientDataAccessResourceException("ack lost after commit")
                }
                return@thenAnswer HumanMoveCorpusCommitResult(
                    HumanMoveCorpusCommitOutcome.ALREADY_COMMITTED,
                    committedCandidates.indexOf(candidate) + 1,
                )
            }
            committedCandidates += candidate
            HumanMoveCorpusCommitResult(HumanMoveCorpusCommitOutcome.COMMITTED, committedCandidates.size)
        }

        val response =
            corpusService.runCorpus(
                corpusRequest(Bounds(maxQualifyingGames = 2), excluded = emptyList()),
            )

        assertEquals(2, bAttempts)
        assertEquals(listOf("http://a", "http://b"), committedCandidates.map { it.providerGameId })
        assertEquals("MAX_QUALIFYING_GAMES", response.stopReason)
        assertEquals(HumanMoveCorpusRunStatus.COMPLETED, response.status)
    }

    @Test
    fun `exhausted transient retries mark the run FAILED and stop traversal`() {
        stubThreeQualifyingGames()
        var bAttempts = 0
        whenever(gameWriter.commitGame(any(), any())).thenAnswer {
            val candidate = it.arguments[1] as HumanMoveCorpusCandidate
            if (candidate.providerGameId == "http://b") {
                bAttempts++
                throw TransientDataAccessResourceException("persistent transient failure")
            }
            committedCandidates += candidate
            HumanMoveCorpusCommitResult(HumanMoveCorpusCommitOutcome.COMMITTED, committedCandidates.size)
        }

        val response = corpusService.runCorpus(corpusRequest(Bounds(maxDepth = 0), excluded = emptyList()))

        assertEquals(HumanMoveCorpusService.MAX_COMMIT_ATTEMPTS, bAttempts)
        assertEquals(3, HumanMoveCorpusService.MAX_COMMIT_ATTEMPTS)
        assertEquals(listOf("http://a"), committedCandidates.map { it.providerGameId }, "traversal stops at the failed game")
        assertEquals(HumanMoveCorpusRunStatus.FAILED, response.status)
        assertTrue(response.failureDetails!!.contains("http://b"), response.failureDetails)
    }

    @Test
    fun `non-transient commit failures are not retried and mark the run FAILED`() {
        val failures =
            listOf(
                DataIntegrityViolationException("duplicate"),
                HumanMoveCorpusContributionMismatchException("mismatch"),
                HumanMoveCorpusRunNotRunningException("not running"),
                IllegalStateException("unexpected"),
            )
        failures.forEach { failure ->
            resetRecorders()
            stubThreeQualifyingGames()
            var attempts = 0
            whenever(gameWriter.commitGame(any(), any())).thenAnswer {
                attempts++
                throw failure
            }

            val response = corpusService.runCorpus(corpusRequest(Bounds(maxDepth = 0), excluded = emptyList()))

            assertEquals(1, attempts, "no retry for ${failure.javaClass.simpleName}")
            assertEquals(HumanMoveCorpusRunStatus.FAILED, response.status)
            assertEquals(1, finishOutcomes.size)
        }
    }

    @Test
    fun `archive fetch failure yields INCOMPLETE, never COMPLETED, and traversal continues`() {
        stubTraversalWorld()
        whenever(chessComClient.fetchArchiveUrls("p2")).thenThrow(RuntimeException("archive list unavailable"))

        val response = corpusService.runCorpus(corpusRequest(Bounds(maxDepth = 5)))

        assertEquals(HumanMoveCorpusRunStatus.INCOMPLETE, response.status)
        assertEquals(1, response.archiveFetchFailureCount)
        assertTrue(response.failureDetails!!.contains("p2"), response.failureDetails)
        assertTrue("http://g8" in committedCandidates.map { it.providerGameId }, "traversal continued past failure")
        assertEquals("EMPTY_FRONTIER", response.stopReason)
    }

    @Test
    fun `monthly game fetch failure yields INCOMPLETE with the failed archive recorded`() {
        stubTraversalWorld()
        whenever(chessComClient.fetchMonthlyGames(archiveOlder("p1"))).thenThrow(RuntimeException("month unavailable"))

        val response = corpusService.runCorpus(corpusRequest(Bounds(maxDepth = 5)))

        assertEquals(HumanMoveCorpusRunStatus.INCOMPLETE, response.status)
        assertEquals(1, response.archiveFetchFailureCount)
        assertTrue(response.failureDetails!!.contains(archiveOlder("p1")), response.failureDetails)
    }

    @Test
    fun `run is created before traversal and finished exactly once`() {
        stubThreeQualifyingGames()
        val order = mutableListOf<String>()
        whenever(gameWriter.createRun(any())).thenAnswer {
            order += "create"
            runId
        }
        whenever(chessComClient.fetchArchiveUrls("p1")).thenAnswer {
            order += "fetch"
            listOf(archive("p1"))
        }

        corpusService.runCorpus(corpusRequest(Bounds(maxDepth = 0), excluded = emptyList()))

        assertEquals("create", order.first())
        assertEquals(1, finishOutcomes.size)
    }

    // ── Request validation ──────────────────────────────────────────────────

    @Test
    fun `invalid requests are rejected before any run is created or traversal starts`() {
        val invalid =
            listOf(
                corpusRequest(Bounds()),
                corpusRequest(Bounds(maxDepth = 1), sourceRevision = " "),
                corpusRequest(Bounds(maxDepth = 1), ratingBand = "not-a-band"),
            )
        invalid.forEach { request ->
            assertThrows(IllegalArgumentException::class.java) { corpusService.runCorpus(request) }
        }
        verify(gameWriter, never()).createRun(any())
        verifyNoInteractions(chessComClient)
    }
}
