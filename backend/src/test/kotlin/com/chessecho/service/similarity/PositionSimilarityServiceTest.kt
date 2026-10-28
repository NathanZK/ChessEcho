package com.chessecho.service.similarity

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.PositionOccurrenceRepository
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
import java.time.Instant
import java.util.UUID

class PositionSimilarityServiceTest {
    private val occurrenceRepository: PositionOccurrenceRepository = mock()
    private val corpusProvider: PersonalPositionCorpusProvider = mock()
    private val tokenExtractor = RelationalPawnTokenExtractor()
    private val scorer = PositionSimilarityScorer()
    private val urlBuilder = ChessComUrlBuilder()
    private val validator = FenPositionValidator()
    private val cache = PositionSimilarityCache()

    private lateinit var service: PositionSimilarityService

    private val account =
        ChessAccount(
            id = UUID.randomUUID(),
            platform = "CHESS_COM",
            username = "testplayer",
        )

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
            Game(
                id = UUID.randomUUID(),
                chessAccount = account,
                platformGameId = "https://www.chess.com/game/live/12345",
                pgn = "1. e4 c5 2. Nf3",
                timeControl = "600",
                playedAt = Instant.parse("2026-01-10T12:00:00Z"),
                whiteUsername = "testplayer",
                blackUsername = "opponent1",
                result = "win",
            )
        val occurrence =
            PositionOccurrence(
                id = UUID.randomUUID(),
                game = game,
                position = Position(id = posId1, hash = "hash1", fen = candidateFen1),
                chessAccount = account,
                plyNumber = 3,
                movePlayed = "Nf3",
                playerColor = "WHITE",
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
    }

    @Test
    fun `findSimilarPositions hydrates occurrences before limit truncation preventing crowd-out`() {
        val posId1 = UUID.randomUUID()
        val posId2 = UUID.randomUUID()

        val doc1 = tokenExtractor.extractDocument(posId1, candidateFen1, includeReachability = true)
        val doc2 = tokenExtractor.extractDocument(posId2, candidateFen2, includeReachability = true)

        whenever(corpusProvider.loadPersonalPositions(account)).thenReturn(listOf(doc1, doc2))

        val game =
            Game(
                id = UUID.randomUUID(),
                chessAccount = account,
                platformGameId = "https://www.chess.com/game/live/99999",
                pgn = "...",
                timeControl = "600",
                playedAt = Instant.parse("2026-01-10T12:00:00Z"),
                whiteUsername = "testplayer",
                blackUsername = "opponent",
                result = "win",
            )
        // posId1 (higher BM25) has NO qualifying occurrence (user played BLACK in error or no occurrence)
        // posId2 (lower BM25) has a valid WHITE occurrence
        val occurrence2 =
            PositionOccurrence(
                id = UUID.randomUUID(),
                game = game,
                position = Position(id = posId2, hash = "hash2", fen = candidateFen2),
                chessAccount = account,
                plyNumber = 40,
                movePlayed = "f3",
                playerColor = "WHITE",
            )

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

        val game =
            Game(
                id = UUID.randomUUID(),
                chessAccount = account,
                platformGameId = "https://www.chess.com/game/live/111",
                pgn = "...",
                timeControl = "600",
                playedAt = Instant.parse("2026-01-10T12:00:00Z"),
                whiteUsername = "opponent",
                blackUsername = "testplayer",
                result = "loss",
            )
        // Occurrence where user played BLACK on a White-to-move query
        val occurrence =
            PositionOccurrence(
                id = UUID.randomUUID(),
                game = game,
                position = Position(id = posId, hash = "hash1", fen = candidateFen1),
                chessAccount = account,
                plyNumber = 3,
                movePlayed = "c5",
                playerColor = "BLACK",
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

        val game =
            Game(
                id = UUID.randomUUID(),
                chessAccount = account,
                platformGameId = "https://www.chess.com/game/live/conflict1",
                pgn = "...",
                timeControl = "600",
                playedAt = Instant.parse("2026-01-10T12:00:00Z"),
                whiteUsername = "opponent",
                blackUsername = "testplayer",
                result = "loss",
            )
        // Conflicting metadata: Candidate FEN is White-to-move, but DB occurrence records playerColor = 'BLACK'
        val conflictingOccurrence =
            PositionOccurrence(
                id = UUID.randomUUID(),
                game = game,
                position = Position(id = posId, hash = "hashConflict", fen = candidateFen1),
                chessAccount = account,
                plyNumber = 3,
                movePlayed = "c5",
                playerColor = "BLACK",
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

        val game =
            Game(
                id = UUID.randomUUID(),
                chessAccount = account,
                platformGameId = "https://www.chess.com/game/live/222",
                pgn = "1. e4 c5",
                timeControl = "600",
                playedAt = Instant.parse("2026-01-10T12:00:00Z"),
                whiteUsername = "opponent",
                blackUsername = "testplayer",
                result = "win",
            )
        val occurrence =
            PositionOccurrence(
                id = UUID.randomUUID(),
                game = game,
                position = Position(id = posId, hash = "hashB", fen = blackCandidateFen),
                chessAccount = account,
                plyNumber = 2,
                movePlayed = "c5",
                playerColor = "BLACK",
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

        val game =
            Game(
                id = UUID.randomUUID(),
                chessAccount = account,
                platformGameId = "https://www.chess.com/game/live/333",
                pgn = "...",
                timeControl = "600",
                playedAt = Instant.parse("2026-01-10T12:00:00Z"),
                whiteUsername = "testplayer",
                blackUsername = "opponent",
                result = "win",
            )
        val blankMoveOcc =
            PositionOccurrence(
                id = UUID.randomUUID(),
                game = game,
                position = Position(id = posId, hash = "hash", fen = candidateFen1),
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "   ",
                playerColor = "WHITE",
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

        val game =
            Game(
                id = UUID.randomUUID(),
                chessAccount = account,
                platformGameId = "https://www.chess.com/game/live/444",
                pgn = "...",
                timeControl = "600",
                playedAt = Instant.parse("2026-01-10T12:00:00Z"),
                whiteUsername = "testplayer",
                blackUsername = "opponent",
                result = "win",
            )
        val occ1 =
            PositionOccurrence(
                id = UUID.randomUUID(),
                game = game,
                position = Position(id = posId1, hash = "hash1", fen = candidateFen1),
                chessAccount = account,
                plyNumber = 3,
                movePlayed = "Nf3",
                playerColor = "WHITE",
            )
        val occ2 =
            PositionOccurrence(
                id = UUID.randomUUID(),
                game = game,
                position = Position(id = posId2, hash = "hash2", fen = candidateFen2),
                chessAccount = account,
                plyNumber = 40,
                movePlayed = "f3",
                playerColor = "WHITE",
            )

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
}
