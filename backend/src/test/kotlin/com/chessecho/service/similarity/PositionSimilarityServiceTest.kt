package com.chessecho.service.similarity

import com.chessecho.domain.ChessAccount
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.service.BatchContinuationResult
import com.chessecho.service.ContinuationResult
import com.chessecho.service.GameOutcomeNormalizer
import com.chessecho.service.PgnHeaderTagReader
import com.chessecho.service.PrincipalVariation
import com.chessecho.service.SearchStatus
import com.chessecho.service.StockfishService
import com.chessecho.similarity.DynamicTacticalScorer
import com.chessecho.similarity.PositionSimilarityScorer
import com.chessecho.similarity.RelationalPawnTokenExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.util.UUID

class PositionSimilarityServiceTest {
    private val occurrenceRepository: PositionOccurrenceRepository = mock()
    private val corpusProvider: PersonalPositionCorpusProvider = mock()
    private val tokenExtractor = RelationalPawnTokenExtractor()
    private val scorer = PositionSimilarityScorer()
    private val urlBuilder = ChessComUrlBuilder()
    private val validator = FenPositionValidator()
    private val cache = PositionSimilarityCache()
    private val stockfishService: StockfishService = mock()
    private val concurrencyManager = SimilarityConcurrencyManager(maxConcurrentSessions = 2)
    private val dynamicScorer = DynamicTacticalScorer()
    private val gameOutcomeNormalizer = GameOutcomeNormalizer(PgnHeaderTagReader())

    private lateinit var service: PositionSimilarityService

    private val account = ChessAccount(id = UUID.randomUUID(), platform = "CHESS_COM", username = "testplayer")

    private val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val candidateFen1 = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
    private val candidateFen2 = "8/5pk1/4p1p1/7p/4P3/5PP1/5K1P/8 w - - 0 40"
    private val blackQueryFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    private val blackCandidateFen = "rnbqkbnr/pp1ppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"

    @BeforeEach
    fun setUp() {
        whenever(stockfishService.batchResolveContinuations(any(), any(), any())).thenAnswer { invocation ->
            val candsArg = invocation.getArgument<List<Pair<UUID, String>>>(1)
            BatchContinuationResult(
                queryResult =
                    ContinuationResult(
                        status = SearchStatus.COMPLETED,
                        achievedDepth = 8,
                        principalVariations = listOf(PrincipalVariation(1, emptyList(), 8)),
                    ),
                candidateResults =
                    candsArg.associate { (id, _) ->
                        id to
                            ContinuationResult(
                                status = SearchStatus.COMPLETED,
                                achievedDepth = 8,
                                principalVariations = listOf(PrincipalVariation(1, emptyList(), 8)),
                            )
                    },
                fallbackReason = null,
                shortlistedCount = candsArg.size,
                engineAttemptedCount = candsArg.size,
                engineCompletedCount = candsArg.size,
            )
        }

        service =
            PositionSimilarityService(
                corpusProvider = corpusProvider,
                occurrenceRepository = occurrenceRepository,
                tokenExtractor = tokenExtractor,
                scorer = scorer,
                urlBuilder = urlBuilder,
                validator = validator,
                cache = cache,
                stockfishService = stockfishService,
                concurrencyManager = concurrencyManager,
                dynamicScorer = dynamicScorer,
                gameOutcomeNormalizer = gameOutcomeNormalizer,
            )
    }

    @Test
    fun `findSimilarPositions returns ranked candidates and occurrences for account`() {
        val posId1 = UUID.randomUUID()
        val posId2 = UUID.randomUUID()

        val doc1 = tokenExtractor.extractDocument(posId1, candidateFen1, includeReachability = true)
        val doc2 = tokenExtractor.extractDocument(posId2, candidateFen2, includeReachability = true)

        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc1, doc2))

        val game =
            createTestGame(
                account = account,
                platformGameId = "https://www.chess.com/game/live/12345",
                whiteUsername = "testplayer",
                blackUsername = "opponent1",
            )
        val occurrence =
            createTestOccurrence(
                account = account,
                game = game,
                positionId = posId1,
                fen = candidateFen1,
                plyNumber = 3,
                movePlayed = "Nf3",
            )

        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occurrence))

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                minScore = 0.0,
                limit = 10,
            )

        assertEquals(queryFen, result.queryFen)
        assertFalse(result.results.isEmpty())
        val topMatch = result.results[0]
        assertEquals(posId1, topMatch.positionId)
        assertEquals(1, topMatch.occurrences.size)
        val occDto = topMatch.occurrences[0]
        assertEquals("Nf3", occDto.playedMove)
        assertEquals(3, occDto.plyNumber)
        assertEquals("https://www.chess.com/game/live/12345?move=3", occDto.chessComUrl)
        assertEquals("win", occDto.gameResult)
    }

    @Test
    fun `findSimilarPositions normalizes occurrence gameResult relative to playerColor and checkmate`() {
        val posId = UUID.randomUUID()
        val doc = tokenExtractor.extractDocument(posId, blackCandidateFen, includeReachability = true)
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc))

        // When Black player had an occurrence where White was checkmated:
        // game.result is "checkmated" (White's perspective), but Black won by checkmate!
        val gameCheckmated =
            createTestGame(
                account = account,
                whiteUsername = "opponent",
                blackUsername = "testplayer",
                result = "checkmated",
            )
        val blackOccurrence =
            createTestOccurrence(
                account = account,
                game = gameCheckmated,
                positionId = posId,
                fen = blackCandidateFen,
                playerColor = "BLACK",
                movePlayed = "c5",
                plyNumber = 2,
            )

        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(blackOccurrence))

        val result =
            service.findSimilarPositions(
                queryFen = blackQueryFen,
                account = account,
                minScore = 0.0,
                limit = 10,
            )

        assertEquals(1, result.results.size)
        val match = result.results[0]
        assertEquals(1, match.occurrences.size)
        val occ = match.occurrences[0]
        assertEquals("BLACK", occ.playerColor)
        assertEquals("opponent", occ.opponentUsername)
        assertEquals("Won by checkmate", occ.gameResult)
    }

    @Test
    fun `findSimilarPositions hydrates occurrences before limit truncation preventing crowd-out`() {
        val posId1 = UUID.randomUUID()
        val posId2 = UUID.randomUUID()

        val doc1 = tokenExtractor.extractDocument(posId1, candidateFen1, includeReachability = true)
        val doc2 = tokenExtractor.extractDocument(posId2, candidateFen2, includeReachability = true)

        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc1, doc2))

        val game = createTestGame(account, platformGameId = "https://www.chess.com/game/live/99999")
        val occurrence2 = createTestOccurrence(account, game, posId2, candidateFen2, plyNumber = 40, movePlayed = "f3")

        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occurrence2))

        // Request limit = 1: doc1 must not crowd out doc2!
        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                minScore = 0.0,
                limit = 1,
            )

        assertEquals(
            1,
            result.results.size,
            "Valid lower-ranked candidate must survive when higher candidate has no qualifying occurrences",
        )
        val match = result.results[0]
        assertEquals(posId2, match.positionId)
        assertEquals(1, match.rank, "Surviving match must be re-ranked contiguously starting at 1")
    }

    @Test
    fun `findSimilarPositions excludes candidate when board matches but user played opposite color`() {
        val posId = UUID.randomUUID()
        val doc = tokenExtractor.extractDocument(posId, candidateFen1, includeReachability = true)
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc))

        val game = createTestGame(account, whiteUsername = "opponent", blackUsername = "testplayer", result = "loss")
        val occurrence =
            createTestOccurrence(
                account = account,
                game = game,
                positionId = posId,
                fen = candidateFen1,
                playerColor = "BLACK",
                movePlayed = "c5",
                plyNumber = 3,
            )

        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occurrence))

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                minScore = 0.0,
                limit = 10,
            )

        assertTrue(result.results.isEmpty(), "Candidate where user played opposite color must be excluded")
    }

    @Test
    fun `findSimilarPositions excludes positions with conflicting FEN turn and occurrence color`() {
        val posId = UUID.randomUUID()
        val doc = tokenExtractor.extractDocument(posId, candidateFen1, includeReachability = true)
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc))

        val game = createTestGame(account, whiteUsername = "opponent", blackUsername = "testplayer", result = "loss")
        val conflictingOccurrence =
            createTestOccurrence(
                account = account,
                game = game,
                positionId = posId,
                fen = candidateFen1,
                playerColor = "BLACK",
                movePlayed = "c5",
                plyNumber = 3,
            )

        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(conflictingOccurrence))

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                minScore = 0.0,
                limit = 10,
            )

        assertTrue(
            result.results.isEmpty(),
            "Candidate with conflicting FEN turn and occurrence playerColor must be excluded",
        )
    }

    @Test
    fun `findSimilarPositions preserves symmetry for Black-to-move queries`() {
        val posId = UUID.randomUUID()
        val doc = tokenExtractor.extractDocument(posId, blackCandidateFen, includeReachability = true)
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc))

        val game = createTestGame(account, whiteUsername = "opponent", blackUsername = "testplayer")
        val occurrence =
            createTestOccurrence(
                account = account,
                game = game,
                positionId = posId,
                fen = blackCandidateFen,
                playerColor = "BLACK",
                movePlayed = "c5",
                plyNumber = 2,
            )

        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occurrence))

        val result =
            service.findSimilarPositions(
                queryFen = blackQueryFen,
                account = account,
                minScore = 0.0,
                limit = 10,
            )

        assertEquals(1, result.results.size)
        assertEquals(posId, result.results[0].positionId)
        assertEquals("BLACK", result.results[0].occurrences[0].playerColor)
    }

    @Test
    fun `findSimilarPositions excludes positions with missing or ambiguous player color or empty move`() {
        val posId = UUID.randomUUID()
        val doc = tokenExtractor.extractDocument(posId, candidateFen1, includeReachability = true)
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc))

        val game = createTestGame(account)
        val blankMoveOcc =
            createTestOccurrence(
                account = account,
                game = game,
                positionId = posId,
                fen = candidateFen1,
                playerColor = "WHITE",
                movePlayed = "   ",
                plyNumber = 1,
            )

        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(blankMoveOcc))

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                minScore = 0.0,
                limit = 10,
            )

        assertTrue(result.results.isEmpty(), "Occurrences with blank movePlayed must be excluded")
    }

    @Test
    fun `findSimilarPositions explicitly passes query sideToMove and never delegates to legacy generator default`() {
        val customIndex =
            object : com.chessecho.similarity.PositionSimilarityCandidateGenerator {
                var explicitTurnPassed: String? = null

                override fun generateCandidates(
                    queryTerms: Set<String>,
                    maxCandidates: Int,
                ): List<com.chessecho.similarity.PositionDocument> =
                    throw AssertionError("Legacy generateCandidates(queryTerms, maxCandidates) must not be called by production service")

                override fun generateCandidates(
                    queryTerms: Set<String>,
                    activeTurn: String,
                    maxCandidates: Int,
                ): List<com.chessecho.similarity.PositionDocument> {
                    explicitTurnPassed = activeTurn
                    return emptyList()
                }
            }

        val customCache = PositionSimilarityCache()
        // Preload index in cache
        val fakeDoc = tokenExtractor.extractDocument(UUID.randomUUID(), candidateFen1, includeReachability = true)
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(fakeDoc))

        // We can test that querying Black explicitly requests "b"
        val blackResult =
            service.findSimilarPositions(
                queryFen = blackQueryFen,
                account = account,
                minScore = 0.0,
                limit = 10,
            )
        assertEquals(0, blackResult.results.size)
    }

    @Test
    fun `findSimilarPositions preserves raw BM25 score ordering and contiguous ranks on eligible corpus`() {
        val posId1 = UUID.randomUUID()
        val posId2 = UUID.randomUUID()

        val doc1 = tokenExtractor.extractDocument(posId1, candidateFen1, includeReachability = true)
        val doc2 = tokenExtractor.extractDocument(posId2, candidateFen2, includeReachability = true)

        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc1, doc2))

        val game = createTestGame(account, platformGameId = "https://www.chess.com/game/live/444")
        val occ1 = createTestOccurrence(account, game, posId1, candidateFen1, plyNumber = 3, movePlayed = "Nf3")
        val occ2 = createTestOccurrence(account, game, posId2, candidateFen2, plyNumber = 40, movePlayed = "f3")

        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occ1, occ2))

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                minScore = 0.0,
                limit = 10,
            )

        assertEquals(2, result.results.size)
        assertEquals(1, result.results[0].rank)
        assertEquals(2, result.results[1].rank)
        assertTrue(result.results[0].score >= result.results[1].score)
        assertEquals(2, result.candidatesEvaluated)
    }

    @Test
    fun `findSimilarPositions returns empty results without allocating index for zero-game accounts`() {
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(emptyList())

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                minScore = 0.0,
                limit = 10,
            )

        assertEquals(0, result.candidatesEvaluated)
        assertTrue(result.results.isEmpty())
    }

    @Test
    fun `1 candidates with mismatched playerColor and activeTurn never reach Stockfish`() {
        val posId1 = UUID.randomUUID()
        val posId2 = UUID.randomUUID()

        val doc1 = tokenExtractor.extractDocument(posId1, candidateFen1, includeReachability = true)
        val doc2 = tokenExtractor.extractDocument(posId2, candidateFen2, includeReachability = true)
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc1, doc2))

        val game = createTestGame(account)
        val occ1 = createTestOccurrence(account, game, posId1, candidateFen1, playerColor = "BLACK", movePlayed = "c5", plyNumber = 3)
        val occ2 = createTestOccurrence(account, game, posId2, candidateFen2, playerColor = "WHITE", movePlayed = "f3", plyNumber = 40)
        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occ1, occ2))

        service.findSimilarPositions(queryFen, account)

        val captor = org.mockito.kotlin.argumentCaptor<List<Pair<UUID, String>>>()
        org.mockito.Mockito
            .verify(stockfishService)
            .batchResolveContinuations(eq(queryFen), captor.capture(), any())

        val submittedIds = captor.firstValue.map { it.first }
        assertFalse(submittedIds.contains(posId1), "Mismatched playerColor must never reach Stockfish")
        assertTrue(submittedIds.contains(posId2), "Matching candidate must reach Stockfish")
    }

    @Test
    fun `2 candidates without personal occurrences never reach Stockfish`() {
        val posId1 = UUID.randomUUID()
        val posId2 = UUID.randomUUID()

        val doc1 = tokenExtractor.extractDocument(posId1, candidateFen1, includeReachability = true)
        val doc2 = tokenExtractor.extractDocument(posId2, candidateFen2, includeReachability = true)
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc1, doc2))

        val game = createTestGame(account)
        val occ2 = createTestOccurrence(account, game, posId2, candidateFen2)
        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occ2))

        service.findSimilarPositions(queryFen, account)

        val captor = org.mockito.kotlin.argumentCaptor<List<Pair<UUID, String>>>()
        org.mockito.Mockito
            .verify(stockfishService)
            .batchResolveContinuations(eq(queryFen), captor.capture(), any())

        val submittedIds = captor.firstValue.map { it.first }
        assertFalse(submittedIds.contains(posId1), "Candidate without occurrences must never reach Stockfish")
        assertTrue(submittedIds.contains(posId2), "Candidate with occurrences must reach Stockfish")
    }

    @Test
    fun `3 the qualified engine shortlist never exceeds 100 candidates`() {
        val fens = generateCandidateFens(105)
        val docs =
            fens.map { fen ->
                tokenExtractor.extractDocument(UUID.randomUUID(), fen, includeReachability = true)
            }
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(docs)

        val game = createTestGame(account)
        val occs = docs.map { createTestOccurrence(account, game, it.positionId, it.fen) }
        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(occs)

        val result = service.findSimilarPositions(queryFen, account)

        val captor = org.mockito.kotlin.argumentCaptor<List<Pair<UUID, String>>>()
        org.mockito.Mockito
            .verify(stockfishService)
            .batchResolveContinuations(eq(queryFen), captor.capture(), any())

        assertEquals(100, captor.firstValue.size, "Engine shortlist must never exceed 100")
        assertEquals(100, result.shortlistedCandidatesCount)
    }

    @Test
    fun `4 interrupted candidates receive dynamic score 0 0 and retain their static rank`() {
        val posId1 = UUID.randomUUID()
        val posId2 = UUID.randomUUID()
        val posId3 = UUID.randomUUID()

        val doc1 = tokenExtractor.extractDocument(posId1, candidateFen1, includeReachability = true)
        val doc2 = tokenExtractor.extractDocument(posId2, candidateFen2, includeReachability = true)
        val fen3 = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val doc3 = tokenExtractor.extractDocument(posId3, fen3, includeReachability = true)
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc1, doc2, doc3))

        val game = createTestGame(account)
        val occs = listOf(doc1, doc2, doc3).map { createTestOccurrence(account, game, it.positionId, it.fen) }
        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(occs)

        whenever(stockfishService.batchResolveContinuations(any(), any(), any())).thenReturn(
            BatchContinuationResult(
                queryResult = completedContinuation(8, listOf("e2e4")),
                candidateResults =
                    mapOf(
                        posId1 to completedContinuation(8, listOf("d2d4")),
                        posId2 to interruptedContinuation(4),
                        posId3 to interruptedContinuation(3),
                    ),
                fallbackReason = null,
                shortlistedCount = 3,
                engineAttemptedCount = 3,
                engineCompletedCount = 1,
            ),
        )

        val result = service.findSimilarPositions(queryFen, account)

        assertEquals(3, result.results.size)
        assertEquals(posId1, result.results[0].positionId)
        assertEquals(SearchStatus.COMPLETED, result.results[0].searchStatus)
        assertTrue(result.results[0].dynamicScore > 0.0)

        assertEquals(posId3, result.results[1].positionId)
        assertEquals(SearchStatus.INTERRUPTED, result.results[1].searchStatus)
        assertEquals(0.0, result.results[1].dynamicScore)

        assertEquals(posId2, result.results[2].positionId)
        assertEquals(SearchStatus.INTERRUPTED, result.results[2].searchStatus)
        assertEquals(0.0, result.results[2].dynamicScore)
    }

    @Test
    fun `5 unrecoverable engine failure returns the original static ordering`() {
        val posId1 = UUID.randomUUID()
        val posId2 = UUID.randomUUID()

        val doc1 = tokenExtractor.extractDocument(posId1, candidateFen1, includeReachability = true)
        val doc2 = tokenExtractor.extractDocument(posId2, candidateFen2, includeReachability = true)
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc1, doc2))

        val game = createTestGame(account)
        val occs = listOf(doc1, doc2).map { createTestOccurrence(account, game, it.positionId, it.fen) }
        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(occs)

        whenever(stockfishService.batchResolveContinuations(any(), any(), any())).thenReturn(
            BatchContinuationResult(
                queryResult = ContinuationResult(SearchStatus.FAILED, 0, emptyList()),
                candidateResults = emptyMap(),
                fallbackReason = "ENGINE_UNRESPONSIVE",
                shortlistedCount = 2,
                engineAttemptedCount = 0,
                engineCompletedCount = 0,
            ),
        )

        val result = service.findSimilarPositions(queryFen, account)

        assertFalse(result.dynamicSimilarityApplied)
        assertEquals("ENGINE_UNRESPONSIVE", result.fallbackReason)
        assertEquals(2, result.results.size)
        assertEquals(posId1, result.results[0].positionId)
        assertEquals(1, result.results[0].rank)
        assertEquals(posId2, result.results[1].positionId)
        assertEquals(2, result.results[1].rank)
        result.results.forEachIndexed { i, match ->
            assertEquals(i + 1, match.rank, "Fallback match at index $i must have 1-based rank ${i + 1}")
        }
        assertTrue(result.results[0].score >= result.results[1].score)
        assertEquals(result.results[0].score, result.results[0].staticScore)
    }

    @Test
    fun `6 deterministic tie-breaking uses dynamic score descending static rank ascending then canonical FEN`() {
        val posId1 = UUID.randomUUID()
        val posId2 = UUID.randomUUID()

        val doc1 = tokenExtractor.extractDocument(posId1, candidateFen1, includeReachability = true)
        val doc2 = tokenExtractor.extractDocument(posId2, candidateFen2, includeReachability = true)
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc1, doc2))

        val game = createTestGame(account)
        val occs = listOf(doc1, doc2).map { createTestOccurrence(account, game, it.positionId, it.fen) }
        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(occs)

        whenever(stockfishService.batchResolveContinuations(any(), any(), any())).thenReturn(
            BatchContinuationResult(
                queryResult = completedContinuation(8, listOf("e2e4")),
                candidateResults =
                    mapOf(
                        posId1 to completedContinuation(8, emptyList()),
                        posId2 to completedContinuation(8, emptyList()),
                    ),
                fallbackReason = null,
                shortlistedCount = 2,
                engineAttemptedCount = 2,
                engineCompletedCount = 2,
            ),
        )

        val result = service.findSimilarPositions(queryFen, account)

        assertEquals(2, result.results.size)
        assertEquals(0.0, result.results[0].dynamicScore)
        assertEquals(0.0, result.results[1].dynamicScore)
        assertEquals(posId1, result.results[0].positionId)
        assertEquals(posId2, result.results[1].positionId)
    }
}
