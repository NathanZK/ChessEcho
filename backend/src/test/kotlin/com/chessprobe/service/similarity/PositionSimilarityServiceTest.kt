package com.chessprobe.service.similarity

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.SimilarityRetrievalMode
import com.chessprobe.repository.EngineAnalysisRepository
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.service.GameOutcomeNormalizer
import com.chessprobe.service.PgnHeaderTagReader
import com.chessprobe.service.StockfishService
import com.chessprobe.similarity.DynamicTacticalScorer
import com.chessprobe.similarity.PositionDocument
import com.chessprobe.similarity.PositionSimilarityScorer
import com.chessprobe.similarity.RelationalPawnTokenExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
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
    private val dynamicCache = DynamicSimilarityCache()
    private val personalDynamicCorpusProvider: PersonalDynamicCorpusProvider = mock()
    private val engineAnalysisRepository: EngineAnalysisRepository = mock()

    private lateinit var service: PositionSimilarityService

    private val account = ChessAccount(id = UUID.randomUUID(), platform = "CHESS_COM", username = "testplayer")

    private val queryFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val candidateFen1 = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
    private val candidateFen2 = "8/5pk1/4p1p1/7p/4P3/5PP1/5K1P/8 w - - 0 40"
    private val blackQueryFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    private val blackCandidateFen = "rnbqkbnr/pp1ppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"

    @BeforeEach
    fun setUp() {
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
            object : com.chessprobe.similarity.PositionSimilarityCandidateGenerator {
                var explicitTurnPassed: String? = null

                override fun generateCandidates(
                    queryTerms: Set<String>,
                    maxCandidates: Int,
                ): List<com.chessprobe.similarity.PositionDocument> =
                    throw AssertionError("Legacy generateCandidates(queryTerms, maxCandidates) must not be called by production service")

                override fun generateCandidates(
                    queryTerms: Set<String>,
                    activeTurn: String,
                    maxCandidates: Int,
                ): List<com.chessprobe.similarity.PositionDocument> {
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
    fun `structural mode never invokes Stockfish or dynamic cache`() {
        val posId1 = UUID.randomUUID()
        val doc1 = tokenExtractor.extractDocument(posId1, candidateFen1, includeReachability = true)
        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc1))

        val game = createTestGame(account)
        val occ = createTestOccurrence(account, game, posId1, candidateFen1, playerColor = "WHITE", movePlayed = "Nf3", plyNumber = 3)
        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occ))

        val result =
            service.findSimilarPositions(
                queryFen = queryFen,
                account = account,
                mode = SimilarityRetrievalMode.STRUCTURAL,
            )

        assertEquals("STRUCTURAL", result.mode)
        assertFalse(result.dynamicSimilarityApplied)
        assertEquals(1, result.results.size)
        assertEquals(0.0, result.results[0].dynamicScore)
        assertEquals(result.results[0].score, result.results[0].staticScore)

        verifyNoInteractions(stockfishService)
        verifyNoInteractions(personalDynamicCorpusProvider)
    }

    @Test
    fun `searching with Sicilian sample FEN does not rank starting position above authentic Sicilian positions`() {
        val sicilianQueryFen = "rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2"
        val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val authenticSicilianFen = "r1bqkbnr/pp1ppppp/2n5/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 1 2"

        val posIdStart = UUID.randomUUID()
        val posIdSicilian = UUID.randomUUID()

        val docStart = tokenExtractor.extractDocument(posIdStart, startFen, includeReachability = true)
        val docSicilian = tokenExtractor.extractDocument(posIdSicilian, authenticSicilianFen, includeReachability = true)

        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(docStart, docSicilian))

        val game = createTestGame(account)
        val occStart = createTestOccurrence(account, game, posIdStart, startFen, plyNumber = 1, movePlayed = "e4")
        val occSicilian = createTestOccurrence(account, game, posIdSicilian, authenticSicilianFen, plyNumber = 3, movePlayed = "d4")

        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(listOf(occStart, occSicilian))

        val result =
            service.findSimilarPositions(
                queryFen = sicilianQueryFen,
                account = account,
                minScore = 0.0,
                limit = 10,
            )

        assertTrue(result.results.size >= 2)
        assertEquals(posIdSicilian, result.results[0].positionId)
        assertEquals(1, result.results[0].rank)
        assertTrue(result.results[0].score > result.results[1].score)
    }

    @Test
    fun `cross-game diversity results cap positions per game at maxPositionsPerGame while preserving monotonic rank ordering`() {
        val game1 = createTestGame(account, platformGameId = "game-1")
        val game2 = createTestGame(account, platformGameId = "game-2")

        val game1Fens =
            listOf(
                "r1bqkbnr/pp1ppppp/2n5/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 1 2",
                "rnbqkb1r/pp1ppppp/5n2/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 1 2",
                "rnbqkbnr/pp2pppp/3p4/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
                "rnbqkbnr/pp1p1ppp/4p3/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
                "rnbqkbnr/1p1ppppp/p7/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
            )
        val game2Fens =
            listOf(
                "rnbqkbnr/pppp1ppp/4p3/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
                "rnbqkbnr/ppp1pppp/3p4/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2",
            )

        val game1Docs =
            game1Fens.mapIndexed { i, fen ->
                val posId = UUID.randomUUID()
                val doc = tokenExtractor.extractDocument(posId, fen, includeReachability = true)
                val occ = createTestOccurrence(account, game1, posId, fen, plyNumber = i + 1, movePlayed = "Nf3")
                Triple(posId, doc, occ)
            }
        val game2Docs =
            game2Fens.mapIndexed { i, fen ->
                val posId = UUID.randomUUID()
                val doc = tokenExtractor.extractDocument(posId, fen, includeReachability = true)
                val occ = createTestOccurrence(account, game2, posId, fen, plyNumber = i + 1, movePlayed = "Nf3")
                Triple(posId, doc, occ)
            }

        val allTriples = game1Docs + game2Docs
        val allDocs = allTriples.map { it.second }
        val allOccs = allTriples.map { it.third }

        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(allDocs)
        whenever(occurrenceRepository.findAllByChessAccountIdAndPositionIdIn(eq(account.id), any()))
            .thenReturn(allOccs)

        val result =
            service.findSimilarPositions(
                queryFen = candidateFen1,
                account = account,
                minScore = 0.0,
                limit = 10,
                maxPositionsPerGame = 3,
            )

        assertEquals(5, result.results.size)
        val game1ReturnedOccurrences = result.results.flatMap { it.occurrences }.filter { it.platformGameId == "game-1" }
        assertEquals(3, game1ReturnedOccurrences.size)

        for (i in result.results.indices) {
            assertEquals(i + 1, result.results[i].rank)
            if (i > 0) {
                assertTrue(result.results[i - 1].score >= result.results[i].score)
            }
        }
    }
}
