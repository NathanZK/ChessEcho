package com.chessecho.integration.controller

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.ContinuationMode
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.UserPositionStats
import com.chessecho.dto.ContinuationResponse
import com.chessecho.dto.PuzzleResponse
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.UserPositionStatsRepository
import com.chessecho.service.EngineAnalysisService
import com.chessecho.service.EngineCandidate
import com.chessecho.service.EvalScore
import com.chessecho.service.PositionAnalysis
import com.chessecho.service.StockfishService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PuzzleControllerIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var userPositionStatsRepository: UserPositionStatsRepository

    @Autowired
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    private lateinit var engineAnalysisRepository: EngineAnalysisRepository

    @Autowired
    private lateinit var engineAnalysisService: EngineAnalysisService

    @MockBean
    private lateinit var stockfishService: StockfishService

    private lateinit var account: ChessAccount

    @BeforeEach
    fun setup() {
        val user = appUserRepository.save(AppUser(email = "puzzle_integration@test.com"))
        account = chessAccountRepository.save(ChessAccount(user = user, platform = "CHESS_COM", username = "puzzleuser"))

        val game =
            gameRepository.save(
                Game(
                    chessAccount = account,
                    platformGameId = "game1",
                    timeControl = "blitz",
                    pgn = "test pgn",
                    result = "win",
                ),
            )

        // Position 1: Big mistake (evalLoss = 2.0). 3 mistakes of Qh5, 2 good moves of e4
        val position1 =
            positionRepository.save(
                Position(hash = "puzzlehash1", fen = "rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 2"),
            )
        userPositionStatsRepository.save(
            UserPositionStats(
                chessAccount = account,
                position = position1,
                playerColor = "WHITE",
                timesReached = 5,
            ),
        )
        for (i in 1..5) {
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = position1,
                    chessAccount = account,
                    plyNumber = 2,
                    movePlayed = if (i <= 3) "Qh5" else "e4",
                    playerColor = "WHITE",
                ),
            )
        }
        val analysis1 =
            engineAnalysisRepository.save(
                EngineAnalysis(
                    position = position1,
                    depth = 16,
                    baselineEvalCp = 50,
                    bestMove = "e4",
                    bestMoveEvalCp = 50,
                    analyzedAt = Instant.now(),
                ),
            )
        analysis1.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis1, move = "e4", evalCp = 45, evalLossFromBest = 0.05))
        analysis1.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis1, move = "Qh5", evalCp = -150, evalLossFromBest = 2.0))
        engineAnalysisRepository.save(analysis1)

        // Position 2: Medium mistake (evalLoss = 0.5). 3 mistakes of Bc4, 2 good moves of d4
        val position2 =
            positionRepository.save(
                Position(hash = "puzzlehash2", fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"),
            )
        userPositionStatsRepository.save(
            UserPositionStats(
                chessAccount = account,
                position = position2,
                playerColor = "WHITE",
                timesReached = 5,
            ),
        )
        for (i in 1..5) {
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = position2,
                    chessAccount = account,
                    plyNumber = 2,
                    movePlayed = if (i <= 3) "Bc4" else "d4",
                    playerColor = "WHITE",
                ),
            )
        }
        val analysis2 =
            engineAnalysisRepository.save(
                EngineAnalysis(
                    position = position2,
                    depth = 16,
                    baselineEvalCp = 20,
                    bestMove = "d4",
                    bestMoveEvalCp = 20,
                    analyzedAt = Instant.now(),
                ),
            )
        analysis2.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis2, move = "d4", evalCp = 20, evalLossFromBest = 0.0))
        analysis2.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis2, move = "Bc4", evalCp = -30, evalLossFromBest = 0.5))
        engineAnalysisRepository.save(analysis2)
    }

    @AfterEach
    fun tearDown() {
        engineAnalysisRepository.deleteAll()
        positionOccurrenceRepository.deleteAll()
        userPositionStatsRepository.deleteAll()
        positionRepository.deleteAll()
        gameRepository.deleteAll()
        chessAccountRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `dynamic minEvalLoss alters qualifying puzzles end to end`() {
        // Query with minEvalLoss=0.3 -> Both Position 1 (loss 2.0) and Position 2 (loss 0.5) qualify
        val response03 =
            restTemplate.exchange(
                "/api/puzzles?platform=CHESS_COM&username=puzzleuser&playerColor=white&minEvalLoss=0.3",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<PuzzleResponse>>() {},
            )
        assertEquals(HttpStatus.OK, response03.statusCode)
        val puzzles03 = response03.body
        assertNotNull(puzzles03)
        assertEquals(2, puzzles03.size)

        // Query with minEvalLoss=0.8 -> Position 2 (loss 0.5 < 0.8) filtered out; only Position 1 qualifies
        val response08 =
            restTemplate.exchange(
                "/api/puzzles?platform=CHESS_COM&username=puzzleuser&playerColor=white&minEvalLoss=0.8",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<PuzzleResponse>>() {},
            )
        assertEquals(HttpStatus.OK, response08.statusCode)
        val puzzles08 = response08.body
        assertNotNull(puzzles08)
        assertEquals(1, puzzles08.size)
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 2", puzzles08[0].fen)
    }

    @Test
    fun `minMistakeCount filters puzzles correctly`() {
        // Both positions have 3 mistakes. Query with minMistakeCount=4 -> 0 puzzles returned
        val response =
            restTemplate.exchange(
                "/api/puzzles?platform=CHESS_COM&username=puzzleuser&playerColor=white&minEvalLoss=0.8&minMistakeCount=4",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<PuzzleResponse>>() {},
            )
        assertEquals(HttpStatus.OK, response.statusCode)
        val puzzles = response.body
        assertNotNull(puzzles)
        assertEquals(0, puzzles.size)
    }

    @Test
    fun `pagination limit and page parameters offset puzzle results`() {
        // Query with minEvalLoss=0.3 (2 puzzles returned total)
        // Page 0, limit 1 -> First puzzle
        val page0 =
            restTemplate.exchange(
                "/api/puzzles?platform=CHESS_COM&username=puzzleuser&playerColor=white&minEvalLoss=0.3&limit=1&page=0",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<PuzzleResponse>>() {},
            )
        assertEquals(HttpStatus.OK, page0.statusCode)
        val puzzlesPage0 = page0.body
        assertNotNull(puzzlesPage0)
        assertEquals(1, puzzlesPage0.size)

        // Page 1, limit 1 -> Second puzzle
        val page1 =
            restTemplate.exchange(
                "/api/puzzles?platform=CHESS_COM&username=puzzleuser&playerColor=white&minEvalLoss=0.3&limit=1&page=1",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<PuzzleResponse>>() {},
            )
        assertEquals(HttpStatus.OK, page1.statusCode)
        val puzzlesPage1 = page1.body
        assertNotNull(puzzlesPage1)
        assertEquals(1, puzzlesPage1.size)
        assertTrue(puzzlesPage0[0].puzzleId != puzzlesPage1[0].puzzleId)

        // Page 2, limit 1 -> Empty
        val page2 =
            restTemplate.exchange(
                "/api/puzzles?platform=CHESS_COM&username=puzzleuser&playerColor=white&minEvalLoss=0.3&limit=1&page=2",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<PuzzleResponse>>() {},
            )
        assertEquals(HttpStatus.OK, page2.statusCode)
        val puzzlesPage2 = page2.body
        assertNotNull(puzzlesPage2)
        assertEquals(0, puzzlesPage2.size)
    }

    @Test
    fun `verify puzzle response payload contains required mapped fields`() {
        val response =
            restTemplate.exchange(
                "/api/puzzles?platform=CHESS_COM&username=puzzleuser&playerColor=white&minEvalLoss=0.8",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<PuzzleResponse>>() {},
            )
        assertEquals(HttpStatus.OK, response.statusCode)
        val puzzle = response.body?.firstOrNull()
        assertNotNull(puzzle)

        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 2", puzzle.fen)
        assertEquals("WHITE", puzzle.playerColor)
        assertEquals("e4", puzzle.targetMove)
        assertEquals(5, puzzle.timesReached)
        assertEquals(3, puzzle.mistakeCount)
        assertEquals(60.0, puzzle.mistakeRate, 0.01)
        assertEquals(50, puzzle.evalCp)
        assertTrue(puzzle.priority > 0.0)
        assertEquals(1, puzzle.acceptableMoves.size)
        assertEquals("e4", puzzle.acceptableMoves[0].move)
        assertEquals(1, puzzle.movesPlayed.size)
        assertEquals("Qh5", puzzle.movesPlayed[0].move)
    }

    @Test
    fun `empty qualifying set returns HTTP 200 with empty list`() {
        val response =
            restTemplate.exchange(
                "/api/puzzles?platform=CHESS_COM&username=puzzleuser&playerColor=black&minEvalLoss=0.8",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<PuzzleResponse>>() {},
            )
        assertEquals(HttpStatus.OK, response.statusCode)
        val puzzles = response.body
        assertNotNull(puzzles)
        assertEquals(0, puzzles.size)
    }

    @Test
    fun `negative minEvalLoss rejects request with error response`() {
        val response =
            restTemplate.exchange(
                "/api/puzzles?platform=CHESS_COM&username=puzzleuser&playerColor=white&minEvalLoss=-0.5",
                HttpMethod.GET,
                null,
                String::class.java,
            )
        assertTrue(response.statusCode.isError)
    }

    @Test
    fun `verify 0 stockfish calls executed during puzzle read`() {
        restTemplate.exchange(
            "/api/puzzles?platform=CHESS_COM&username=puzzleuser&playerColor=white&minEvalLoss=0.8",
            HttpMethod.GET,
            null,
            object : ParameterizedTypeReference<List<PuzzleResponse>>() {},
        )
        verifyNoInteractions(stockfishService)
    }

    @Test
    fun `nonexistent account produces controlled 404 Not Found response`() {
        val response =
            restTemplate.exchange(
                "/api/puzzles?platform=CHESS_COM&username=nonexistent_puzzle_user&playerColor=WHITE",
                HttpMethod.GET,
                null,
                String::class.java,
            )
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertTrue(response.body!!.contains("NOT_FOUND"))
    }

    @Test
    fun `test puzzles endpoint for username gothamchess as white with minEvalLoss 0,3 returns non-empty result`() {
        val user = appUserRepository.save(AppUser(email = "gotham@test.com"))
        val gothamAccount = chessAccountRepository.save(ChessAccount(user = user, platform = "CHESS_COM", username = "gothamchess"))

        val game =
            gameRepository.save(
                Game(
                    chessAccount = gothamAccount,
                    platformGameId = "gotham_game_1",
                    timeControl = "blitz",
                    pgn = "1. e4 c5",
                    result = "win",
                ),
            )
        val position =
            positionRepository.save(
                Position(hash = "gothamhash", fen = "rnbqkbnr/pp1pppp1/7p/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 3"),
            )

        userPositionStatsRepository.save(
            UserPositionStats(chessAccount = gothamAccount, position = position, playerColor = "WHITE", timesReached = 5),
        )

        for (i in 1..5) {
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = gothamAccount,
                    plyNumber = 5,
                    movePlayed = if (i <= 3) "h3" else "d4",
                    playerColor = "WHITE",
                ),
            )
        }

        val analysis =
            engineAnalysisRepository.save(
                EngineAnalysis(
                    position = position,
                    depth = 16,
                    baselineEvalCp = 40,
                    bestMove = "d4",
                    bestMoveEvalCp = 40,
                    analyzedAt = Instant.now(),
                ),
            )
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "d4", evalCp = 40, evalLossFromBest = 0.0))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "h3", evalCp = -10, evalLossFromBest = 0.5))
        engineAnalysisRepository.save(analysis)

        val response =
            restTemplate.exchange(
                "/api/puzzles?platform=CHESS_COM&username=gothamchess&playerColor=WHITE&minEvalLoss=0.3",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<PuzzleResponse>>() {},
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        val puzzles = response.body
        assertNotNull(puzzles)
        assertTrue(puzzles.isNotEmpty(), "Puzzles endpoint should NOT return empty for gothamchess")
        assertEquals(1, puzzles.size)
        assertEquals("rnbqkbnr/pp1pppp1/7p/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 0 3", puzzles[0].fen)
        assertEquals("WHITE", puzzles[0].playerColor)
        assertEquals("d4", puzzles[0].targetMove)
        assertEquals(5, puzzles[0].timesReached)
        assertEquals(3, puzzles[0].mistakeCount)
        assertNotNull(puzzles[0].gameUrls)
        assertTrue(puzzles[0].gameUrls.isNotEmpty(), "PuzzleResponse should include non-empty gameUrls")
    }

    @Test
    fun `end to end acceptableMoves includes MultiPV engine candidates while movesPlayed contains only user history`() {
        val user = appUserRepository.save(AppUser(email = "multipv_e2e@test.com"))
        val account = chessAccountRepository.save(ChessAccount(user = user, platform = "CHESS_COM", username = "multipvuser"))

        val game =
            gameRepository.save(
                Game(
                    chessAccount = account,
                    platformGameId = "multipv_game",
                    timeControl = "blitz",
                    pgn = "1. e4 e5 2. Nf3 Nc6",
                    result = "win",
                ),
            )

        val position =
            positionRepository.save(
                Position(hash = "multipv_hash", fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"),
            )

        userPositionStatsRepository.save(
            UserPositionStats(chessAccount = account, position = position, playerColor = "WHITE", timesReached = 5),
        )

        // User played: 1 time Bc4 (good), 1 time Nf6 (acceptable historical move outside MultiPV top 3), 3 times Qh5 (blunder)
        // User played 0 times Bb5 (engine only candidate)
        for (i in 1..5) {
            val move =
                when (i) {
                    1 -> "Bc4"
                    2 -> "Nf6"
                    else -> "Qh5"
                }
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = account,
                    plyNumber = 5,
                    movePlayed = move,
                    playerColor = "WHITE",
                ),
            )
        }

        // Mock stockfishService.analyzeMultiPv to return top-3 engine candidates: Bc4 (rank 1 best), Bb5 (rank 2 engine-only), d4 (rank 3 engine-only)
        val engineCandidates =
            listOf(
                EngineCandidate("Bc4", EvalScore(cp = 40, mate = null)),
                EngineCandidate("Bb5", EvalScore(cp = 35, mate = null)),
                EngineCandidate("d4", EvalScore(cp = 30, mate = null)),
            )
        whenever(stockfishService.analyzeMultiPv(position.fen, 16, 5)).thenReturn(engineCandidates)

        // Secondary search mock for remaining historical moves "Nf6" and "Qh5"
        val remainingMap =
            mapOf(
                "Qh5" to PositionAnalysis(bestMove = "g6", score = EvalScore(cp = -160, mate = null)),
                "Nf6" to PositionAnalysis(bestMove = "d6", score = EvalScore(cp = 20, mate = null)),
            )
        whenever(
            stockfishService.analyze(
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
            ),
        ).thenReturn(remainingMap)

        // 1. Execute normal engine analysis pipeline
        engineAnalysisService.analyzePosition(position)

        // 2. Fetch puzzle via GET /api/puzzles
        val response =
            restTemplate.exchange(
                "/api/puzzles?platform=CHESS_COM&username=multipvuser&playerColor=WHITE&minEvalLoss=0.5",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<PuzzleResponse>>() {},
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        val puzzles = response.body
        assertNotNull(puzzles)
        assertEquals(1, puzzles.size)

        val puzzle = puzzles[0]
        val acceptableMoveNames = puzzle.acceptableMoves.map { it.move }.toSet()
        val movesPlayedNames = puzzle.movesPlayed.map { it.move }.toSet()

        // 3. Assert engine-only MultiPV candidate (Bb5) appears in acceptableMoves
        assertTrue(acceptableMoveNames.contains("Bb5"), "acceptableMoves must contain engine candidate Bb5")
        assertTrue(acceptableMoveNames.contains("Bc4"), "acceptableMoves must contain engine best move Bc4")

        // 4. Assert historical move outside MultiPV (Nf6 with evalLoss 0.20 < 0.50) also appears in acceptableMoves
        assertTrue(acceptableMoveNames.contains("Nf6"), "acceptableMoves must contain acceptable historical move Nf6")

        // 5. Assert movesPlayed contains ONLY user historical mistakes (Qh5)
        assertEquals(setOf("Qh5"), movesPlayedNames, "movesPlayed must contain user historical mistake Qh5")

        // 6. Assert engine-only moves (Bb5, d4) NEVER bleed into movesPlayed
        assertTrue(!movesPlayedNames.contains("Bb5"), "movesPlayed must NOT contain engine candidate Bb5")
        assertTrue(!movesPlayedNames.contains("d4"), "movesPlayed must NOT contain engine candidate d4")
    }

    @Test
    fun `getContinuation endpoint with mode ENGINE returns requestedMode ENGINE and effectiveProvider ENGINE`() {
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val engineCandidates =
            listOf(
                EngineCandidate("Bb5", EvalScore(cp = 40, mate = null)),
                EngineCandidate("Bc4", EvalScore(cp = 35, mate = null)),
            )
        whenever(stockfishService.analyzeMultiPv(fen, 16, 5)).thenReturn(engineCandidates)

        val response =
            restTemplate.exchange(
                "/api/puzzles/continuation?fen={fen}&mode=ENGINE",
                HttpMethod.GET,
                null,
                ContinuationResponse::class.java,
                fen,
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body
        assertNotNull(body)
        assertEquals(ContinuationMode.ENGINE, body.requestedMode)
        assertEquals("ENGINE", body.effectiveProvider)
        assertEquals(2, body.candidates.size)
        assertEquals("Bb5", body.candidates[0].move)
        assertEquals("ENGINE", body.candidates[0].providerType)
        assertEquals(40, body.candidates[0].evalCp)
        assertEquals(0.0, body.candidates[0].evalLoss)
        assertEquals("r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 3", body.candidates[0].resultingFen)

        assertEquals("Bc4", body.candidates[1].move)
        assertEquals("ENGINE", body.candidates[1].providerType)
        assertEquals(35, body.candidates[1].evalCp)
        assertEquals(0.05, body.candidates[1].evalLoss!!, 0.001)
    }

    @Test
    fun `getContinuation endpoint with mode HUMAN falls back to ENGINE and sets requestedMode HUMAN and effectiveProvider ENGINE`() {
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val engineCandidates = listOf(EngineCandidate("Bc4", EvalScore(cp = 35, mate = null)))
        whenever(stockfishService.analyzeMultiPv(fen, 16, 5)).thenReturn(engineCandidates)

        val response =
            restTemplate.exchange(
                "/api/puzzles/continuation?fen={fen}&mode=HUMAN",
                HttpMethod.GET,
                null,
                ContinuationResponse::class.java,
                fen,
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body
        assertNotNull(body)
        assertEquals(ContinuationMode.HUMAN, body.requestedMode)
        assertEquals("ENGINE", body.effectiveProvider)
        assertEquals(1, body.candidates.size)
        assertEquals("Bc4", body.candidates[0].move)
        assertEquals("ENGINE", body.candidates[0].providerType)
    }
}
