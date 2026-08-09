package com.chessecho.integration.controller

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.UserPositionStats
import com.chessecho.dto.PuzzleResponse
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.UserPositionStatsRepository
import com.chessecho.service.StockfishService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

    @MockBean
    private lateinit var stockfishService: StockfishService

    @BeforeEach
    fun setup() {
        val user = appUserRepository.save(AppUser(email = "puzzle_integration@test.com"))
        val account = chessAccountRepository.save(ChessAccount(user = user, platform = "CHESS_COM", username = "puzzleuser"))

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

        val position =
            positionRepository.save(
                Position(hash = "puzzlehash", fen = "rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 2"),
            )

        userPositionStatsRepository.save(
            UserPositionStats(
                chessAccount = account,
                position = position,
                playerColor = "WHITE",
                timesReached = 5,
            ),
        )

        // Create 5 occurrences: 3 mistakes (Qh5), 2 good (e4)
        for (i in 1..5) {
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = account,
                    plyNumber = 2,
                    movePlayed = if (i <= 3) "Qh5" else "e4",
                    playerColor = "WHITE",
                ),
            )
        }

        val analysis =
            engineAnalysisRepository.save(
                EngineAnalysis(
                    position = position,
                    depth = 16,
                    baselineEvalCp = 50,
                    bestMove = "e4",
                    bestMoveEvalCp = 50,
                    analyzedAt = Instant.now(),
                ),
            )

        val evalGood = MoveEvaluation(engineAnalysis = analysis, move = "e4", evalCp = 45, evalLossFromBest = 0.05)
        val evalBad = MoveEvaluation(engineAnalysis = analysis, move = "Qh5", evalCp = -150, evalLossFromBest = 2.0)

        analysis.moveEvaluations.add(evalGood)
        analysis.moveEvaluations.add(evalBad)
        engineAnalysisRepository.save(analysis)
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
    fun `test get puzzles end to end`() {
        val response =
            restTemplate.exchange(
                "/api/puzzles?platform=CHESS_COM&username=puzzleuser&playerColor=white&mistakeThreshold=0.8",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<PuzzleResponse>>() {},
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        val puzzles = response.body
        assertNotNull(puzzles)
        assertEquals(1, puzzles.size)

        val puzzle = puzzles[0]
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 2", puzzle.fen)
        assertEquals("WHITE", puzzle.playerColor)
        assertEquals("e4", puzzle.targetMove)
        assertEquals(5, puzzle.timesReached)
        assertEquals(3, puzzle.mistakeCount)
        assertEquals(60.0, puzzle.mistakeRate, 0.01)
    }

    @Test
    fun `test get puzzles with pagination`() {
        // Request page 1 with limit 1 when only 1 item exists -> should return empty list
        val response =
            restTemplate.exchange(
                "/api/puzzles?platform=CHESS_COM&username=puzzleuser&playerColor=white&mistakeThreshold=0.8&limit=1&page=1",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<PuzzleResponse>>() {},
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        val puzzles = response.body
        assertNotNull(puzzles)
        assertEquals(0, puzzles.size)
    }
}
