package com.chessecho.controller

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.dto.WeaknessResponse
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
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

        // Create occurrences
        for (i in 1..5) {
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = account,
                    plyNumber = 2,
                    // 3 mistakes, 2 good moves
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
                    baselineEvalMate = null,
                    bestMove = "e4",
                    analyzedAt = Instant.now(),
                ),
            )

        // Good move
        val evalGood =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "e4",
                // -0.05 loss
                evalCp = 45,
                evalMate = null,
            )
        // Blunder
        val evalBad =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "Qh5",
                // -2.0 loss
                evalCp = -150,
                evalMate = null,
            )

        analysis.moveEvaluations.add(evalGood)
        analysis.moveEvaluations.add(evalBad)
        engineAnalysisRepository.save(analysis)
    }

    @AfterEach
    fun tearDown() {
        engineAnalysisRepository.deleteAll()
        positionOccurrenceRepository.deleteAll()
        positionRepository.deleteAll()
        gameRepository.deleteAll()
        chessAccountRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `test get weaknesses end to end`() {
        val response =
            restTemplate.exchange(
                "/api/positions/weaknesses?platform=CHESS_COM&username=integrationuser&playerColor=white&minEvalLoss=0.8",
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
        // baseline is 50, Qh5 is -150 -> diff = 200cp = 2.0
        assertEquals(2.0, weakness.averageLoss)
    }
}
