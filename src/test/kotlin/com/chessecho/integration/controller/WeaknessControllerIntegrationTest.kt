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
import com.chessecho.service.WeaknessCalculationService
import com.fasterxml.jackson.databind.ObjectMapper
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

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockBean
    private lateinit var stockfishService: StockfishService

    private lateinit var defaultPosition: Position

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

        defaultPosition =
            positionRepository.save(
                Position(hash = "testhash", fen = "rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 2"),
            )

        userPositionStatsRepository.save(
            UserPositionStats(
                chessAccount = account,
                position = defaultPosition,
                playerColor = "WHITE",
                timesReached = 5,
            ),
        )

        // Create occurrences: 3 mistakes (Qh5), 2 good moves (e4)
        for (i in 1..5) {
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = defaultPosition,
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
                    position = defaultPosition,
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
    fun `test get weaknesses end to end with dynamic minEvalLoss`() {
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
        assertEquals(60.0, weakness.mistakeRate, 0.01)
        assertEquals(2.0, weakness.averageLoss)
        assertEquals("WHITE", weakness.playerColor)
        assertEquals(weakness.priority, weakness.recommendationPriority)
        assertEquals("INACCURATE", weakness.objectiveEvidenceState.name)
        assertEquals(null, weakness.evidenceCombination)
        assertEquals(false, weakness.practicalEvidence.rankingApplied)
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
                "/api/positions/weaknesses?platform=CHESS_COM&username=asymmetricuser&playerColor=white&minEvalLoss=0.8",
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

    @Test
    fun `test playerColor BOTH considers both WHITE and BLACK occurrences`() {
        val user = appUserRepository.save(AppUser(email = "both@test.com"))
        val account = chessAccountRepository.save(ChessAccount(user = user, platform = "CHESS_COM", username = "bothuser"))

        val position =
            positionRepository.save(
                Position(hash = "bothhash", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"),
            )

        userPositionStatsRepository.save(
            UserPositionStats(
                chessAccount = account,
                position = position,
                playerColor = "WHITE",
                timesReached = WeaknessCalculationService.DEFAULT_MIN_TIMES_REACHED,
            ),
        )
        userPositionStatsRepository.save(
            UserPositionStats(
                chessAccount = account,
                position = position,
                playerColor = "BLACK",
                timesReached = WeaknessCalculationService.DEFAULT_MIN_TIMES_REACHED,
            ),
        )

        // Each color independently meets the real objective floor using distinct games.
        for (i in 1..WeaknessCalculationService.DEFAULT_MIN_TIMES_REACHED) {
            val whiteGame =
                gameRepository.save(
                    Game(
                        chessAccount = account,
                        platformGameId = "gameboth-white-$i",
                        timeControl = "blitz",
                        pgn = "pgn",
                        result = "win",
                        whiteUsername = account.username,
                        blackUsername = "white-opponent-$i",
                    ),
                )
            val blackGame =
                gameRepository.save(
                    Game(
                        chessAccount = account,
                        platformGameId = "gameboth-black-$i",
                        timeControl = "blitz",
                        pgn = "pgn",
                        result = "resigned",
                        whiteUsername = "black-opponent-$i",
                        blackUsername = account.username,
                    ),
                )
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = whiteGame,
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "a3",
                    playerColor = "WHITE",
                ),
            )
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = blackGame,
                    position = position,
                    chessAccount = account,
                    plyNumber = 2,
                    movePlayed = "a6",
                    playerColor = "BLACK",
                ),
            )
        }

        val analysis =
            engineAnalysisRepository.save(
                EngineAnalysis(
                    position = position,
                    depth = 16,
                    baselineEvalCp = 0,
                    bestMove = "e4",
                    bestMoveEvalCp = 30,
                    analyzedAt = Instant.now(),
                ),
            )
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "a3", evalCp = -100, evalLossFromBest = 1.3))
        analysis.moveEvaluations.add(MoveEvaluation(engineAnalysis = analysis, move = "a6", evalCp = -120, evalLossFromBest = 1.5))
        engineAnalysisRepository.save(analysis)

        // Query with playerColor=BOTH
        val response =
            restTemplate.exchange(
                "/api/positions/weaknesses?platform=CHESS_COM&username=bothuser&playerColor=BOTH&minEvalLoss=0.8",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<WeaknessResponse>>() {},
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        val weaknesses = response.body
        assertNotNull(weaknesses)
        assertEquals(2, weaknesses.size)
        assertEquals(setOf("WHITE", "BLACK"), weaknesses.map { it.playerColor }.toSet())
        weaknesses.forEach { weakness ->
            assertEquals(WeaknessCalculationService.DEFAULT_MIN_TIMES_REACHED, weakness.mistakeCount)
            assertEquals(WeaknessCalculationService.DEFAULT_MIN_TIMES_REACHED, weakness.timesReached)
            assertEquals(WeaknessCalculationService.DEFAULT_MIN_TIMES_REACHED, weakness.practicalEvidence.candidateGames)
            assertEquals(WeaknessCalculationService.DEFAULT_MIN_TIMES_REACHED, weakness.practicalEvidence.eligibleGames)
        }
    }

    @Test
    fun `additive practical fields preserve the complete legacy objective JSON projection`() {
        val response =
            restTemplate.getForEntity(
                "/api/positions/weaknesses?platform=CHESS_COM&username=integrationuser&playerColor=WHITE&minEvalLoss=0.8",
                String::class.java,
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        val weakness = objectMapper.readTree(response.body).single()

        assertEquals(defaultPosition.id.toString(), weakness["positionId"].textValue())
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 2", weakness["fen"].textValue())
        assertEquals(5, weakness["timesReached"].intValue())
        assertEquals(3, weakness["mistakeCount"].intValue())
        assertEquals(60.0, weakness["mistakeRate"].doubleValue(), 0.001)
        assertEquals(2.0, weakness["averageLoss"].doubleValue(), 0.001)
        assertEquals(3.6, weakness["priority"].doubleValue(), 0.001)
        assertEquals("e4", weakness["bestMove"].textValue())
        assertEquals(1, weakness["acceptableMoves"].size())
        assertEquals("e4", weakness["acceptableMoves"][0]["move"].textValue())
        assertEquals(0.05, weakness["acceptableMoves"][0]["evalLoss"].doubleValue(), 0.001)
        assertEquals(1, weakness["movesPlayed"].size())
        assertEquals("Qh5", weakness["movesPlayed"][0]["move"].textValue())
        assertEquals(3, weakness["movesPlayed"][0]["timesPlayed"].intValue())
        assertEquals(2.0, weakness["movesPlayed"][0]["averageLoss"].doubleValue(), 0.001)
        assertEquals(listOf("https://www.chess.com/game/live/game1"), weakness["gameUrls"].map { it.textValue() })
        assertEquals(50, weakness["evalCp"].intValue())
        kotlin.test.assertTrue(weakness["lastSeenAt"].isTextual)
    }

    @Test
    fun `test nonexistent account produces controlled 404 Not Found response`() {
        val response =
            restTemplate.exchange(
                "/api/positions/weaknesses?platform=CHESS_COM&username=nonexistent_user_xyz&playerColor=WHITE",
                HttpMethod.GET,
                null,
                String::class.java,
            )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertNotNull(response.body)
        kotlin.test.assertTrue(response.body!!.contains("NOT_FOUND"))
    }

    @Test
    fun `test weaknesses endpoint pagination with page size and priority ordering`() {
        val user = appUserRepository.save(AppUser(email = "pagination@test.com"))
        val account = chessAccountRepository.save(ChessAccount(user = user, platform = "CHESS_COM", username = "pageuser"))
        val game =
            gameRepository.save(
                Game(chessAccount = account, platformGameId = "pagegame", timeControl = "blitz", pgn = "pgn", result = "win"),
            )

        // Create 5 position weaknesses with distinct priority scores
        for (i in 1..5) {
            val position =
                positionRepository.save(
                    Position(hash = "pagehash_$i", fen = "rnbqkbnr/pppp1ppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 $i"),
                )
            userPositionStatsRepository.save(
                UserPositionStats(chessAccount = account, position = position, playerColor = "WHITE", timesReached = 10),
            )
            for (occ in 1..5) {
                positionOccurrenceRepository.save(
                    PositionOccurrence(
                        game = game,
                        position = position,
                        chessAccount = account,
                        plyNumber = 1,
                        movePlayed = "badMove",
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
                        bestMove = "goodMove",
                        bestMoveEvalCp = 100,
                        analyzedAt = Instant.now(),
                    ),
                )
            // Give higher loss to higher indices so priority increases
            analysis.moveEvaluations.add(
                MoveEvaluation(engineAnalysis = analysis, move = "badMove", evalCp = 100 - (i * 50), evalLossFromBest = 0.5 * i),
            )
            engineAnalysisRepository.save(analysis)
        }

        // 1. Default pagination (page=0, size=20) returns all 5 sorted by descending priority
        val defaultResp =
            restTemplate.exchange(
                "/api/positions/weaknesses?platform=CHESS_COM&username=pageuser&playerColor=WHITE&minEvalLoss=0.3",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<WeaknessResponse>>() {},
            )
        assertEquals(HttpStatus.OK, defaultResp.statusCode)
        val defaultList = defaultResp.body!!
        assertEquals(5, defaultList.size)
        // Verify priority ordering descending
        for (idx in 0 until defaultList.size - 1) {
            kotlin.test.assertTrue(
                defaultList[idx].priority >= defaultList[idx + 1].priority,
                "Results must be sorted descending by priority",
            )
        }

        // 2. Explicit pagination: page=0, size=2
        val page0Resp =
            restTemplate.exchange(
                "/api/positions/weaknesses?platform=CHESS_COM&username=pageuser&playerColor=WHITE&minEvalLoss=0.3&page=0&size=2",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<WeaknessResponse>>() {},
            )
        assertEquals(HttpStatus.OK, page0Resp.statusCode)
        val page0List = page0Resp.body!!
        assertEquals(2, page0List.size)
        assertEquals(defaultList[0].positionId, page0List[0].positionId)
        assertEquals(defaultList[1].positionId, page0List[1].positionId)

        // 3. Explicit pagination: page=1, size=2
        val page1Resp =
            restTemplate.exchange(
                "/api/positions/weaknesses?platform=CHESS_COM&username=pageuser&playerColor=WHITE&minEvalLoss=0.3&page=1&size=2",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<WeaknessResponse>>() {},
            )
        assertEquals(HttpStatus.OK, page1Resp.statusCode)
        val page1List = page1Resp.body!!
        assertEquals(2, page1List.size)
        assertEquals(defaultList[2].positionId, page1List[0].positionId)
        assertEquals(defaultList[3].positionId, page1List[1].positionId)

        // 4. Page beyond available results: page=10, size=2
        val pageBeyondResp =
            restTemplate.exchange(
                "/api/positions/weaknesses?platform=CHESS_COM&username=pageuser&playerColor=WHITE&minEvalLoss=0.3&page=10&size=2",
                HttpMethod.GET,
                null,
                object : ParameterizedTypeReference<List<WeaknessResponse>>() {},
            )
        assertEquals(HttpStatus.OK, pageBeyondResp.statusCode)
        val pageBeyondList = pageBeyondResp.body!!
        assertEquals(0, pageBeyondList.size)
    }
}
