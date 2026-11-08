package com.chessecho.service.similarity

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Position
import com.chessecho.domain.SimilarityRetrievalMode
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.service.BatchContinuationResult
import com.chessecho.service.ContinuationResult
import com.chessecho.service.EngineCandidate
import com.chessecho.service.EvalScore
import com.chessecho.service.GameOutcomeNormalizer
import com.chessecho.service.PgnHeaderTagReader
import com.chessecho.service.PrincipalVariation
import com.chessecho.service.SearchStatus
import com.chessecho.service.StockfishService
import com.chessecho.similarity.DynamicTacticalScorer
import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.PositionSimilarityScorer
import com.chessecho.similarity.RelationalPawnTokenExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

class PositionSimilarityServiceDynamicTest {
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
    private val dynamicCache = DynamicSimilarityCache()
    private val personalDynamicCorpusProvider: PersonalDynamicCorpusProvider = mock()
    private val engineAnalysisRepository: EngineAnalysisRepository = mock()

    private lateinit var service: PositionSimilarityService

    private val account = ChessAccount(id = UUID.randomUUID(), platform = "CHESS_COM", username = "testplayer")

    private val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val candidateFen1 = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"

    @BeforeEach
    fun setUp() {
        whenever(stockfishService.batchResolveContinuations(any(), any(), any(), anyOrNull())).thenAnswer { invocation ->
            val candsArg = invocation.getArgument<List<Pair<UUID, String>>>(1)
            BatchContinuationResult(
                queryResult =
                    ContinuationResult(
                        status = SearchStatus.COMPLETED,
                        achievedDepth = 16,
                        principalVariations = listOf(PrincipalVariation(1, listOf("g1f3"), 16)),
                    ),
                candidateResults =
                    candsArg.associate { (id, _) ->
                        id to
                            ContinuationResult(
                                status = SearchStatus.COMPLETED,
                                achievedDepth = 16,
                                principalVariations = listOf(PrincipalVariation(1, listOf("g1f3"), 16)),
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
                dynamicCache = dynamicCache,
                personalDynamicCorpusProvider = personalDynamicCorpusProvider,
                engineAnalysisRepository = engineAnalysisRepository,
            )
    }

    @Test
    fun `dynamic mode invokes Stockfish at D10 MultiPV 1, retrieves from DynamicSimilarityCache and qualifies candidates`() {
        val posId = UUID.randomUUID()
        val dynTokens = dynamicScorer.extractDynamicTokens(queryFen, listOf("g1f3"))
        val dynDoc =
            PositionDocument(
                positionId = posId,
                fen = candidateFen1,
                canonicalFen = PositionDocument.canonicalizeFen(candidateFen1),
                sideToMove = "w",
                terms = dynTokens,
            )
        whenever(personalDynamicCorpusProvider.loadPersonalDynamicPositions(account))
            .thenReturn(listOf(dynDoc))

        whenever(stockfishService.analyzeMultiPv(eq(queryFen), eq(16), eq(1)))
            .thenReturn(listOf(EngineCandidate(move = "g1f3", score = EvalScore(cp = 50, mate = null))))

        val game = createTestGame(account)
        val occ = createTestOccurrence(account, game, posId, candidateFen1, playerColor = "WHITE", movePlayed = "e4", plyNumber = 1)
        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occ))

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                mode = SimilarityRetrievalMode.DYNAMIC,
            )

        assertEquals("DYNAMIC", result.mode)
        assertTrue(result.dynamicSimilarityApplied)
        assertEquals(1, result.results.size)
        assertEquals(posId, result.results[0].positionId)
        assertEquals(1, result.results[0].rank)
        assertTrue(result.results[0].dynamicScore > 0.0)
        verify(stockfishService).analyzeMultiPv(eq(queryFen), eq(16), eq(1))
        verify(personalDynamicCorpusProvider).loadPersonalDynamicPositions(account)
    }

    @Test
    fun `dynamic mode candidate qualification filters out mismatched playerColor`() {
        val posId1 = UUID.randomUUID()
        val dynTokens = dynamicScorer.extractDynamicTokens(queryFen, listOf("e2e4"))
        val dynDoc1 =
            PositionDocument(
                positionId = posId1,
                fen = candidateFen1,
                canonicalFen = PositionDocument.canonicalizeFen(candidateFen1),
                sideToMove = "w",
                terms = dynTokens,
            )
        whenever(personalDynamicCorpusProvider.loadPersonalDynamicPositions(account))
            .thenReturn(listOf(dynDoc1))

        whenever(stockfishService.analyzeMultiPv(eq(queryFen), eq(16), eq(1)))
            .thenReturn(listOf(EngineCandidate(move = "e2e4", score = EvalScore(cp = 50, mate = null))))

        val game = createTestGame(account)
        val occ1 = createTestOccurrence(account, game, posId1, candidateFen1, playerColor = "BLACK", movePlayed = "c5", plyNumber = 3)
        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occ1))

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                mode = SimilarityRetrievalMode.DYNAMIC,
            )

        assertEquals("DYNAMIC", result.mode)
        assertTrue(result.results.isEmpty(), "Mismatched playerColor must be filtered out in dynamic mode")
    }

    @Test
    fun `dynamic mode candidate qualification filters out positions without occurrences`() {
        val posId1 = UUID.randomUUID()
        val dynTokens = dynamicScorer.extractDynamicTokens(queryFen, listOf("e2e4"))
        val dynDoc1 =
            PositionDocument(
                positionId = posId1,
                fen = candidateFen1,
                canonicalFen = PositionDocument.canonicalizeFen(candidateFen1),
                sideToMove = "w",
                terms = dynTokens,
            )
        whenever(personalDynamicCorpusProvider.loadPersonalDynamicPositions(account))
            .thenReturn(listOf(dynDoc1))

        whenever(stockfishService.analyzeMultiPv(eq(queryFen), eq(16), eq(1)))
            .thenReturn(listOf(EngineCandidate(move = "e2e4", score = EvalScore(cp = 50, mate = null))))

        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(emptyList())

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                mode = SimilarityRetrievalMode.DYNAMIC,
            )

        assertEquals("DYNAMIC", result.mode)
        assertTrue(result.results.isEmpty(), "Candidates without occurrences must be filtered out")
    }

    @Test
    fun `dynamic mode engine failure returns clean fallbackReason without running structural search`() {
        whenever(stockfishService.analyzeMultiPv(eq(queryFen), any(), eq(1)))
            .thenThrow(RuntimeException("Stockfish process crashed"))

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                mode = SimilarityRetrievalMode.DYNAMIC,
            )

        assertEquals("DYNAMIC", result.mode)
        assertTrue(result.dynamicSimilarityApplied)
        assertEquals("ENGINE_FAILED", result.fallbackReason)
        assertEquals(SearchStatus.FAILED, result.queryStatus)
        assertTrue(result.results.isEmpty())
        verifyNoInteractions(corpusProvider)
    }

    @Test
    fun `dynamic mode concurrency capacity exceeded returns clean fallbackReason without structural fallback`() {
        val saturatingManager = SimilarityConcurrencyManager(maxConcurrentSessions = 1)
        val constrainedService =
            PositionSimilarityService(
                corpusProvider = corpusProvider,
                occurrenceRepository = occurrenceRepository,
                tokenExtractor = tokenExtractor,
                scorer = scorer,
                urlBuilder = urlBuilder,
                validator = validator,
                cache = cache,
                stockfishService = stockfishService,
                concurrencyManager = saturatingManager,
                dynamicScorer = dynamicScorer,
                gameOutcomeNormalizer = gameOutcomeNormalizer,
                dynamicCache = dynamicCache,
                personalDynamicCorpusProvider = personalDynamicCorpusProvider,
                engineAnalysisRepository = engineAnalysisRepository,
            )

        saturatingManager.withPermitOrFallback(
            onPermitAcquired = {
                val result =
                    constrainedService.findSimilarPositions(
                        queryFen = queryFen,
                        account = account,
                        mode = SimilarityRetrievalMode.DYNAMIC,
                    )

                assertEquals("DYNAMIC", result.mode)
                assertTrue(result.dynamicSimilarityApplied)
                assertEquals("CAPACITY_EXCEEDED", result.fallbackReason)
                assertTrue(result.results.isEmpty())
                verifyNoInteractions(stockfishService)
                verifyNoInteractions(corpusProvider)
            },
            onCapacityExceeded = { error("Should acquire first permit") },
        )
    }

    @Test
    fun `dynamic mode cache-first resolution bypasses stockfish batch for candidates present in engineAnalysisRepository with depth 16`() {
        val posId = UUID.randomUUID()
        val dynTokens = dynamicScorer.extractDynamicTokens(queryFen, listOf("e2e4"))
        val dynDoc =
            PositionDocument(
                positionId = posId,
                fen = candidateFen1,
                canonicalFen = PositionDocument.canonicalizeFen(candidateFen1),
                sideToMove = "w",
                terms = dynTokens,
            )
        whenever(personalDynamicCorpusProvider.loadPersonalDynamicPositions(account))
            .thenReturn(listOf(dynDoc))

        whenever(stockfishService.analyzeMultiPv(eq(queryFen), eq(16), eq(1)))
            .thenReturn(listOf(EngineCandidate(move = "e2e4", score = EvalScore(cp = 50, mate = null))))

        val game = createTestGame(account)
        val occ = createTestOccurrence(account, game, posId, candidateFen1, playerColor = "WHITE", movePlayed = "e4", plyNumber = 1)
        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occ))

        val cachedAnalysis =
            EngineAnalysis(
                position = Position(id = posId, fen = candidateFen1, hash = "hash-$posId"),
                depth = 16,
                bestMove = "g1f3",
            )
        whenever(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(posId)))
            .thenReturn(listOf(cachedAnalysis))

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                mode = SimilarityRetrievalMode.DYNAMIC,
            )

        assertEquals("DYNAMIC", result.mode)
        assertEquals(1, result.results.size)
        assertEquals(16, result.results[0].achievedDepth)
        assertEquals(SearchStatus.COMPLETED, result.results[0].searchStatus)
        verify(stockfishService, never()).batchResolveContinuations(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `dynamic mode reanalyzes uncached candidate at depth 16 via stockfish batch`() {
        val posId = UUID.randomUUID()
        val dynTokens = dynamicScorer.extractDynamicTokens(queryFen, listOf("e2e4"))
        val dynDoc =
            PositionDocument(
                positionId = posId,
                fen = candidateFen1,
                canonicalFen = PositionDocument.canonicalizeFen(candidateFen1),
                sideToMove = "w",
                terms = dynTokens,
            )
        whenever(personalDynamicCorpusProvider.loadPersonalDynamicPositions(account))
            .thenReturn(listOf(dynDoc))

        whenever(stockfishService.analyzeMultiPv(eq(queryFen), eq(16), eq(1)))
            .thenReturn(listOf(EngineCandidate(move = "e2e4", score = EvalScore(cp = 50, mate = null))))

        val game = createTestGame(account)
        val occ = createTestOccurrence(account, game, posId, candidateFen1, playerColor = "WHITE", movePlayed = "e4", plyNumber = 1)
        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occ))

        whenever(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(posId)))
            .thenReturn(emptyList())

        whenever(stockfishService.batchResolveContinuations(eq(queryFen), any(), any(), anyOrNull())).thenReturn(
            BatchContinuationResult(
                queryResult = ContinuationResult(SearchStatus.COMPLETED, 16, listOf(PrincipalVariation(1, listOf("e2e4"), 16))),
                candidateResults =
                    mapOf(
                        posId to ContinuationResult(SearchStatus.COMPLETED, 16, listOf(PrincipalVariation(1, listOf("g1f3"), 16))),
                    ),
                fallbackReason = null,
                shortlistedCount = 1,
                engineAttemptedCount = 1,
                engineCompletedCount = 1,
            ),
        )

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                mode = SimilarityRetrievalMode.DYNAMIC,
            )

        assertEquals("DYNAMIC", result.mode)
        assertEquals(1, result.results.size)
        assertEquals(16, result.results[0].achievedDepth)
        verify(stockfishService).batchResolveContinuations(eq(queryFen), eq(listOf(posId to dynDoc.canonicalFen)), any(), anyOrNull())
    }

    @Test
    fun `dynamic mode evaluates query FEN at most once by forwarding pre-resolved query continuation to batchResolveContinuations`() {
        val posId = UUID.randomUUID()
        val dynTokens = dynamicScorer.extractDynamicTokens(queryFen, listOf("e2e4"))
        val dynDoc =
            PositionDocument(
                positionId = posId,
                fen = candidateFen1,
                canonicalFen = PositionDocument.canonicalizeFen(candidateFen1),
                sideToMove = "w",
                terms = dynTokens,
            )
        whenever(personalDynamicCorpusProvider.loadPersonalDynamicPositions(account))
            .thenReturn(listOf(dynDoc))

        whenever(stockfishService.analyzeMultiPv(eq(queryFen), eq(16), eq(1)))
            .thenReturn(listOf(EngineCandidate(move = "e2e4", score = EvalScore(cp = 50, mate = null))))

        val game = createTestGame(account)
        val occ = createTestOccurrence(account, game, posId, candidateFen1, playerColor = "WHITE", movePlayed = "e4", plyNumber = 1)
        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occ))

        whenever(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(posId)))
            .thenReturn(emptyList())

        service.findSimilarPositions(
            queryFen = queryFen,
            account = account,
            mode = SimilarityRetrievalMode.DYNAMIC,
        )

        verify(stockfishService, times(1)).analyzeMultiPv(eq(queryFen), eq(16), eq(1))
        verify(stockfishService).batchResolveContinuations(
            eq(queryFen),
            eq(listOf(posId to dynDoc.canonicalFen)),
            any(),
            check { continuation ->
                assertEquals(SearchStatus.COMPLETED, continuation?.status)
                assertEquals(16, continuation?.achievedDepth)
                assertEquals(listOf("e2e4"), continuation?.principalVariations?.firstOrNull()?.moves)
            },
        )
    }

    @Test
    fun `dynamic mode batch timeout falls back cleanly to stage 1 candidates directly`() {
        val posId = UUID.randomUUID()
        val dynTokens = dynamicScorer.extractDynamicTokens(queryFen, listOf("e2e4"))
        val dynDoc =
            PositionDocument(
                positionId = posId,
                fen = candidateFen1,
                canonicalFen = PositionDocument.canonicalizeFen(candidateFen1),
                sideToMove = "w",
                terms = dynTokens,
            )
        whenever(personalDynamicCorpusProvider.loadPersonalDynamicPositions(account))
            .thenReturn(listOf(dynDoc))

        whenever(stockfishService.analyzeMultiPv(eq(queryFen), eq(16), eq(1)))
            .thenReturn(listOf(EngineCandidate(move = "e2e4", score = EvalScore(cp = 50, mate = null))))

        val game = createTestGame(account)
        val occ = createTestOccurrence(account, game, posId, candidateFen1, playerColor = "WHITE", movePlayed = "e4", plyNumber = 1)
        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occ))

        whenever(engineAnalysisRepository.findByPositionIdInWithMoveEvaluations(setOf(posId)))
            .thenReturn(emptyList())

        whenever(stockfishService.batchResolveContinuations(eq(queryFen), any(), any(), anyOrNull())).thenReturn(
            BatchContinuationResult(
                queryResult = ContinuationResult(SearchStatus.INTERRUPTED, 0, emptyList()),
                candidateResults = emptyMap(),
                fallbackReason = "TIMEOUT",
                shortlistedCount = 1,
                engineAttemptedCount = 1,
                engineCompletedCount = 0,
            ),
        )

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                mode = SimilarityRetrievalMode.DYNAMIC,
            )

        assertEquals("DYNAMIC", result.mode)
        assertEquals("TIMEOUT", result.fallbackReason)
        assertEquals(SearchStatus.INTERRUPTED, result.queryStatus)
        assertEquals(1, result.results.size)
        assertEquals(10, result.results[0].achievedDepth)
        assertEquals(SearchStatus.INTERRUPTED, result.results[0].searchStatus)
    }
}
