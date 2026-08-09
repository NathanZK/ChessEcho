package com.chessecho.integration.controller

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.UserPositionStats
import com.chessecho.dto.WeaknessResponse
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
class WeaknessControllerIntegrationTest {
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
        val user = appUserRepository.save(AppUser(email = "integration@test.com"))
        val account = chessAccountRepository.save(ChessAccount(user = user, platform = "CHESS_COM", username = "integrationuser"))

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
                Position(hash = "testhash", fen = "rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 2"),
            )

        userPositionStatsRepository.save(
            UserPositionStats(
                chessAccount = account,
                position = position,
                playerColor = "WHITE",
                timesReached = 5,
            ),
        )

        // Create occurrences: 3 mistakes (Qh5), 2 good moves (e4)
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

        // Good move (evalLossFromBest = 0.05)
        val evalGood =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e4",
                evalCp = 45,
                evalLossFromBest = 0.05,
            )
        // Blunder (evalLossFromBest = 2.0)
        val evalBad =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "Qh5",
                evalCp = -150,
                evalLossFromBest = 2.0,
            )

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
    fun `test get weaknesses end to end with dynamic mistakeThreshold`() {
        val response =
            restTemplate.exchange(
                "/api/positions/weaknesses?platform=CHESS_COM&username=integrationuser&playerColor=white&mistakeThreshold=0.8",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<WeaknessResponse>>() {},
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        val weaknesses = response.body
        assertNotNull(weaknesses)
        assertEquals(1, weaknesses.size)

        val weakness = weaknesses[0]
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 2", weakness.fen)
        assertEquals(5, weakness.timesReached)
        assertEquals(3, weakness.mistakeCount)
        assertEquals(60.0, weakness.mistakeRate, 0.01)
        assertEquals(2.0, weakness.averageLoss)
    }

    @Test
    fun `test asymmetric occurrence play frequencies 20 total occurrences 15 for move A and 5 for move B`() {
        val user = appUserRepository.save(AppUser(email = "asymmetric@test.com"))
        val account = chessAccountRepository.save(ChessAccount(user = user, platform = "CHESS_COM", username = "asymmetricuser"))

        val game =
            gameRepository.save(
                Game(
                    chessAccount = account,
                    platformGameId = "game20",
                    timeControl = "blitz",
                    pgn = "test pgn",
                    result = "win",
                ),
            )

        val position =
            positionRepository.save(
                Position(hash = "asymmetrichash", fen = "rnbqkbnr/pppp1ppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"),
            )

        userPositionStatsRepository.save(
            UserPositionStats(
                chessAccount = account,
                position = position,
                playerColor = "WHITE",
                timesReached = 20,
            ),
        )

        // Move A played 15 times, Move B played 5 times
        for (i in 1..15) {
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "moveA",
                    playerColor = "WHITE",
                ),
            )
        }
        for (i in 1..5) {
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "moveB",
                    playerColor = "WHITE",
                ),
            )
        }

        val analysis =
            engineAnalysisRepository.save(
                EngineAnalysis(
                    position = position,
                    depth = 16,
                    baselineEvalCp = 100,
                    bestMove = "moveA",
                    bestMoveEvalCp = 100,
                    analyzedAt = Instant.now(),
                ),
            )

        // Move A: evalLoss = 0.1
        val evalA = MoveEvaluation(engineAnalysis = analysis, move = "moveA", evalCp = 90, evalLossFromBest = 0.1)
        // Move B: evalLoss = 0.9
        val evalB = MoveEvaluation(engineAnalysis = analysis, move = "moveB", evalCp = 10, evalLossFromBest = 0.9)

        analysis.moveEvaluations.add(evalA)
        analysis.moveEvaluations.add(evalB)
        engineAnalysisRepository.save(analysis)

        val response =
            restTemplate.exchange(
                "/api/positions/weaknesses?platform=CHESS_COM&username=asymmetricuser&playerColor=white&mistakeThreshold=0.8",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<WeaknessResponse>>() {},
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        val weaknesses = response.body
        assertNotNull(weaknesses)
        assertEquals(1, weaknesses.size)

        val weakness = weaknesses[0]
        assertEquals(20, weakness.timesReached)
        assertEquals(5, weakness.mistakeCount)
        assertEquals(25.0, weakness.mistakeRate, 0.01)
        assertEquals(0.9, weakness.averageLoss, 0.01)
    }
}
