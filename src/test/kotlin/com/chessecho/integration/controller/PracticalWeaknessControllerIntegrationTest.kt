package com.chessecho.integration.controller

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.UserPositionStatsRepository
import com.chessecho.service.StockfishService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "chess.weakness.practical.ranking-enabled=true",
        "chess.weakness.practical.sample-floor=5",
        "chess.weakness.practical.comparator-method=FIXED_SCORE_RATE",
        "chess.weakness.practical.comparator-score-rate=0.5",
        "chess.weakness.practical.confidence-method=BERNOULLI_WILSON_SCORE_POINTS_HALF_DRAW_CONSERVATIVE",
        "chess.weakness.practical.wilson-z-score=1.0",
        "chess.weakness.practical.meaningful-difference=0.1",
        "chess.weakness.practical.max-priority-adjustment=0.25",
        "chess.weakness.practical.observation-window-days=365",
        "chess.weakness.practical.policy-version=integration-test-v1",
    ],
)
class PracticalWeaknessControllerIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

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

    @Autowired
    private lateinit var userPositionStatsRepository: UserPositionStatsRepository

    @MockBean
    private lateinit var stockfishService: StockfishService

    @AfterEach
    fun tearDown() {
        verifyNoInteractions(stockfishService)
        engineAnalysisRepository.deleteAll()
        positionOccurrenceRepository.deleteAll()
        userPositionStatsRepository.deleteAll()
        positionRepository.deleteAll()
        gameRepository.deleteAll()
        chessAccountRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `normalizes Black and authoritative legacy attribution`() {
        val account = account("legacyblack")
        val candidate =
            candidate(
                account = account,
                color = "BLACK",
                suffix = "legacy-black",
                games =
                    (1..5).map {
                        GameSpec(
                            sourceResult = "win",
                            whiteUsername = "stale-white-$it",
                            blackUsername = "stale-black-$it",
                            pgnWhite = "opponent-$it",
                            pgnBlack = account.username,
                            pgnResult = "1-0",
                        )
                    },
            )

        val weakness = weaknesses(account.username, "BLACK").single()
        val evidence = weakness["practicalEvidence"]

        assertEquals(candidate.id.toString(), weakness["positionId"].textValue())
        assertEquals("BLACK", weakness["playerColor"].textValue())
        assertEquals(5, evidence["candidateGames"].intValue())
        assertEquals(5, evidence["eligibleGames"].intValue())
        assertEquals(0, evidence["wins"].intValue())
        assertEquals(0, evidence["draws"].intValue())
        assertEquals(5, evidence["losses"].intValue())
        assertEquals(0.0, evidence["scoreRate"].doubleValue())
        assertEquals(0, evidence["sideCorroborationConflictGames"].intValue())
    }

    @Test
    fun `deduplicates repeated occurrences and isolates account color position and SAN`() {
        val account = account("isolated")
        val otherAccount = account("other-account")
        val candidate =
            candidate(
                account = account,
                color = "WHITE",
                suffix = "dedupe",
                games =
                    listOf(
                        GameSpec(sourceResult = "win", repetitions = 2),
                        GameSpec(sourceResult = "win"),
                        GameSpec(sourceResult = "win"),
                        GameSpec(sourceResult = "win"),
                        GameSpec(sourceResult = "win"),
                        GameSpec(sourceResult = "win", san = "not-engine-evaluated", evaluated = false),
                    ),
            )

        addRawOccurrence(otherAccount, candidate, "WHITE", GameSpec(sourceResult = "win"))
        addRawOccurrence(account, candidate, "BLACK", GameSpec(sourceResult = "win"))
        val unrelatedPosition = position("unrelated", "WHITE")
        addRawOccurrence(account, unrelatedPosition, "WHITE", GameSpec(sourceResult = "win"))

        val weakness = weaknesses(account.username, "WHITE").single()
        val positionEvidence = weakness["practicalEvidence"]
        val badDecision = weakness["movesPlayed"].single { it["move"].textValue() == "bad" }["practicalEvidence"]

        assertEquals(6, weakness["timesReached"].intValue())
        assertEquals(6, positionEvidence["candidateGames"].intValue())
        assertEquals(6, positionEvidence["eligibleGames"].intValue())
        assertEquals(5, badDecision["candidateGames"].intValue())
        assertEquals(5, badDecision["eligibleGames"].intValue())
        assertEquals("bad", badDecision["decisionSan"].textValue())
        assertEquals("DECISION", badDecision["scope"].textValue())
    }

    @Test
    fun `reports ineligible excluded and corroboration-conflict counts separately`() {
        val account = account("quality")
        candidate(
            account = account,
            color = "WHITE",
            suffix = "quality",
            games =
                listOf(
                    GameSpec(sourceResult = "win"),
                    GameSpec(
                        sourceResult = "win",
                        whiteUsername = account.username,
                        blackUsername = account.username,
                        pgnWhite = account.username,
                        pgnBlack = account.username,
                    ),
                    GameSpec(sourceResult = "win", variant = "Chess960"),
                    GameSpec(sourceResult = "win", playedAt = Instant.now().minusSeconds(400L * 86_400L)),
                    GameSpec(sourceResult = "unknown", pgnResult = null),
                    GameSpec(sourceResult = "win", malformedHeader = true),
                ),
        )

        val evidence = weaknesses(account.username, "WHITE").single()["practicalEvidence"]

        assertEquals(6, evidence["candidateGames"].intValue())
        assertEquals(2, evidence["eligibleGames"].intValue())
        assertEquals(2, evidence["ineligibleGames"].intValue())
        assertEquals(2, evidence["excludedGames"].intValue())
        assertEquals(
            evidence["candidateGames"].intValue(),
            evidence["eligibleGames"].intValue() +
                evidence["ineligibleGames"].intValue() +
                evidence["excludedGames"].intValue(),
        )
        assertEquals(2, evidence["wins"].intValue())
        assertEquals(0, evidence["draws"].intValue())
        assertEquals(0, evidence["losses"].intValue())
        assertEquals(1, evidence["sideCorroborationConflictGames"].intValue())
        assertEquals(1.0, evidence["scoreRate"].doubleValue())
    }

    @Test
    fun `applies confidence-gated priority while preserving objective JSON values`() {
        val account = account("ranking")
        candidate(
            account = account,
            color = "WHITE",
            suffix = "ranking-poor",
            games = (1..5).map { GameSpec(sourceResult = "resigned") },
        )

        val weakness = weaknesses(account.username, "WHITE").single()
        val evidence = weakness["practicalEvidence"]

        assertEquals(5, weakness["timesReached"].intValue())
        assertEquals(5, weakness["mistakeCount"].intValue())
        assertEquals(100.0, weakness["mistakeRate"].doubleValue())
        assertEquals(1.0, weakness["averageLoss"].doubleValue())
        assertEquals(5.0, weakness["priority"].doubleValue(), 0.001)
        assertEquals(6.25, weakness["recommendationPriority"].doubleValue(), 0.001)
        assertEquals("INACCURATE", weakness["objectiveEvidenceState"].textValue())
        assertEquals("CORROBORATED_OBJECTIVE_WEAKNESS", weakness["evidenceCombination"].textValue())
        assertEquals("RANKING_ELIGIBLE", evidence["confidenceState"].textValue())
        assertEquals("POOR", evidence["practicalAssessment"].textValue())
        assertTrue(evidence["confidenceLowerBound"].isNumber)
        assertTrue(evidence["confidenceUpperBound"].isNumber)
        assertEquals("FIXED_SCORE_RATE", evidence["comparatorMethod"].textValue())
        assertEquals(0.5, evidence["comparatorScoreRate"].doubleValue())
        assertEquals(
            "BERNOULLI_WILSON_SCORE_POINTS_HALF_DRAW_CONSERVATIVE",
            evidence["confidenceMethod"].textValue(),
        )
        assertEquals(5, evidence["sampleFloor"].intValue())
        assertEquals(0.1, evidence["meaningfulDifference"].doubleValue())
        assertEquals("STANDARD_ALL_IMPORTED_TIME_CONTROLS", evidence["cohort"].textValue())
        assertEquals(365, evidence["observationWindowDays"].intValue())
        assertEquals("integration-test-v1", evidence["policyVersion"].textValue())
        assertEquals("CALIBRATED", evidence["configurationState"].textValue())
        assertEquals("POSITION", evidence["scope"].textValue())
        assertTrue(evidence["decisionSan"].isNull)
        assertTrue(evidence["rankingApplied"].booleanValue())
    }

    @Test
    fun `ranking reorders both endpoints before their page boundaries`() {
        val account = account("shared-order")
        val poor =
            candidate(
                account,
                "WHITE",
                "poor",
                (1..5).map { GameSpec(sourceResult = "resigned") },
                evalLoss = 1.6,
            )
        val successful =
            candidate(
                account,
                "WHITE",
                "successful",
                (1..5).map { GameSpec(sourceResult = "win") },
                evalLoss = 1.8,
            )
        val recommendationOrder = listOf(poor.id.toString(), successful.id.toString())
        val objectiveOrder = listOf(successful.id.toString(), poor.id.toString())

        val weaknesses = weaknesses(account.username, "WHITE")
        val puzzles = puzzles(account.username, "WHITE")

        assertEquals(recommendationOrder, weaknesses.map { it["positionId"].textValue() })
        assertEquals(recommendationOrder, puzzles.map { it["puzzleId"].textValue() })
        assertEquals(
            objectiveOrder,
            weaknesses.sortedByDescending { it["priority"].doubleValue() }.map { it["positionId"].textValue() },
        )
        assertEquals(
            objectiveOrder,
            puzzles.sortedByDescending { it["priority"].doubleValue() }.map { it["puzzleId"].textValue() },
        )
        assertEquals(8.0, weaknesses[0]["priority"].doubleValue(), 0.001)
        assertEquals(9.0, weaknesses[1]["priority"].doubleValue(), 0.001)
        assertEquals(10.0, weaknesses[0]["recommendationPriority"].doubleValue(), 0.001)
        assertEquals(6.75, weaknesses[1]["recommendationPriority"].doubleValue(), 0.001)
        assertEquals("POOR", weaknesses[0]["practicalEvidence"]["practicalAssessment"].textValue())
        assertEquals("SUCCESSFUL", weaknesses[1]["practicalEvidence"]["practicalAssessment"].textValue())
        weaknesses.zip(puzzles).forEach { (weakness, puzzle) ->
            assertEquals(weakness["playerColor"], puzzle["playerColor"])
            assertEquals(weakness["priority"], puzzle["priority"])
            assertEquals(weakness["recommendationPriority"], puzzle["recommendationPriority"])
            assertEquals(weakness["objectiveEvidenceState"], puzzle["objectiveEvidenceState"])
            assertEquals(weakness["evidenceCombination"], puzzle["evidenceCombination"])
            assertEquals(weakness["practicalEvidence"], puzzle["practicalEvidence"])
            assertEquals(
                weakness["movesPlayed"].map { it["practicalEvidence"] },
                puzzle["movesPlayed"].map { it["practicalEvidence"] },
            )
        }

        assertEquals(
            listOf(poor.id.toString()),
            weaknesses(account.username, "WHITE", "&page=0&size=1").map { it["positionId"].textValue() },
        )
        assertEquals(
            listOf(successful.id.toString()),
            weaknesses(account.username, "WHITE", "&page=1&size=1").map { it["positionId"].textValue() },
        )
        assertEquals(
            listOf(poor.id.toString()),
            puzzles(account.username, "WHITE", "&page=0&limit=1").map { it["puzzleId"].textValue() },
        )
        assertEquals(
            listOf(successful.id.toString()),
            puzzles(account.username, "WHITE", "&page=1&limit=1").map { it["puzzleId"].textValue() },
        )
    }

    @Test
    fun `puzzle BOTH response copies each independently eligible scoped color`() {
        val account = account("both-puzzle")
        val position =
            candidate(
                account,
                "WHITE",
                "both-colors",
                (1..5).map { GameSpec(sourceResult = "win") },
            )
        repeat(5) {
            addRawOccurrence(
                account,
                position,
                "BLACK",
                GameSpec(sourceResult = "resigned"),
            )
        }

        val puzzles = puzzles(account.username, "BOTH")

        assertEquals(2, puzzles.size)
        assertEquals(
            setOf(position.id.toString() to "WHITE", position.id.toString() to "BLACK"),
            puzzles.map { it["puzzleId"].textValue() to it["playerColor"].textValue() }.toSet(),
        )
        puzzles.forEach { puzzle ->
            val color = puzzle["playerColor"].textValue()
            assertTrue(color == "WHITE" || color == "BLACK")
            assertEquals(5, puzzle["timesReached"].intValue())
            assertEquals(5, puzzle["mistakeCount"].intValue())
            assertEquals(5, puzzle["practicalEvidence"]["candidateGames"].intValue())
            assertEquals(5, puzzle["practicalEvidence"]["eligibleGames"].intValue())
            assertEquals(5, puzzle["practicalEvidence"]["wins"].intValue())
        }
    }

    @Test
    fun `keeps deterministic tie boundaries across repeated page reads`() {
        val account = account("ties")
        val positions =
            (1..3).map {
                candidate(
                    account,
                    "WHITE",
                    "tie-$it",
                    (1..5).map { GameSpec(sourceResult = "resigned") },
                )
            }
        val expected = positions.map { it.id.toString() }.sorted()

        assertEquals(expected, weaknesses(account.username, "WHITE").map { it["positionId"].textValue() })
        assertEquals(expected, puzzles(account.username, "WHITE").map { it["puzzleId"].textValue() })

        expected.indices.forEach { page ->
            repeat(2) {
                val weaknessPage = weaknesses(account.username, "WHITE", "&page=$page&size=1")
                val puzzlePage = puzzles(account.username, "WHITE", "&page=$page&limit=1")
                assertEquals(listOf(expected[page]), weaknessPage.map { it["positionId"].textValue() })
                assertEquals(listOf(expected[page]), puzzlePage.map { it["puzzleId"].textValue() })
            }
        }
    }

    @Test
    fun `falls back to objective order for insufficient inconclusive and invalid outcomes`() {
        val account = account("fallbacks")
        val insufficient =
            candidate(
                account,
                "WHITE",
                "insufficient",
                (1..4).map { GameSpec(sourceResult = "resigned") } +
                    GameSpec(sourceResult = "unknown", pgnResult = null),
            )
        val inconclusive =
            candidate(
                account,
                "WHITE",
                "inconclusive",
                listOf(
                    GameSpec(sourceResult = "win"),
                    GameSpec(sourceResult = "win"),
                    GameSpec(sourceResult = "agreed"),
                    GameSpec(sourceResult = "resigned"),
                    GameSpec(sourceResult = "resigned"),
                ),
            )
        val invalid =
            candidate(
                account,
                "WHITE",
                "invalid",
                (1..5).map { GameSpec(sourceResult = "unknown", pgnResult = null) },
            )

        val byId = weaknesses(account.username, "WHITE").associateBy { it["positionId"].textValue() }
        listOf(insufficient, inconclusive, invalid).forEach { position ->
            val weakness = assertNotNull(byId[position.id.toString()])
            assertEquals(weakness["priority"].doubleValue(), weakness["recommendationPriority"].doubleValue())
            assertTrue(weakness["evidenceCombination"].isNull)
            assertEquals(false, weakness["practicalEvidence"]["rankingApplied"].booleanValue())
        }
        assertEquals("INSUFFICIENT", byId.getValue(insufficient.id.toString())["practicalEvidence"]["confidenceState"].textValue())
        assertEquals("INCONCLUSIVE", byId.getValue(inconclusive.id.toString())["practicalEvidence"]["confidenceState"].textValue())
        assertEquals("INSUFFICIENT", byId.getValue(invalid.id.toString())["practicalEvidence"]["confidenceState"].textValue())
    }

    private fun weaknesses(
        username: String,
        color: String,
        extraQuery: String = "",
    ): List<JsonNode> {
        val response =
            restTemplate.getForEntity(
                "/api/positions/weaknesses?platform=CHESS_COM&username=$username&playerColor=$color&minEvalLoss=0.8$extraQuery",
                String::class.java,
            )
        assertEquals(HttpStatus.OK, response.statusCode, response.body)
        return objectMapper.readTree(response.body).toList()
    }

    private fun puzzles(
        username: String,
        color: String,
        extraQuery: String = "",
    ): List<JsonNode> {
        val response =
            restTemplate.getForEntity(
                "/api/puzzles?platform=CHESS_COM&username=$username&playerColor=$color&minEvalLoss=0.8$extraQuery",
                String::class.java,
            )
        assertEquals(HttpStatus.OK, response.statusCode, response.body)
        return objectMapper.readTree(response.body).toList()
    }

    private fun account(username: String): ChessAccount {
        val user = appUserRepository.save(AppUser(email = "$username-${nextId++}@integration.test"))
        return chessAccountRepository.save(
            ChessAccount(
                user = user,
                platform = "CHESS_COM",
                username = username,
            ),
        )
    }

    private fun candidate(
        account: ChessAccount,
        color: String,
        suffix: String,
        games: List<GameSpec>,
        evalLoss: Double = 1.0,
    ): Position {
        val position = position(suffix, color)
        games.forEach { spec ->
            addRawOccurrence(account, position, color, spec)
        }
        val analysis =
            engineAnalysisRepository.save(
                EngineAnalysis(
                    position = position,
                    depth = 16,
                    baselineEvalCp = 100,
                    bestMove = "best",
                    bestMoveEvalCp = 100,
                    analyzedAt = Instant.now(),
                ),
            )
        games.filter { it.evaluated }.map { it.san }.toSet().forEach { san ->
            analysis.moveEvaluations.add(
                MoveEvaluation(
                    engineAnalysis = analysis,
                    move = san,
                    evalCp = 0,
                    evalLossFromBest = evalLoss,
                ),
            )
        }
        analysis.moveEvaluations.add(
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "best",
                evalCp = 100,
                evalLossFromBest = 0.0,
            ),
        )
        engineAnalysisRepository.save(analysis)
        return position
    }

    private fun position(
        suffix: String,
        color: String,
    ): Position {
        val activeColor = if (color == "BLACK") "b" else "w"
        return positionRepository.save(
            Position(
                hash = "practical-$suffix-${nextId++}",
                fen = "8/8/8/8/8/8/8/K6k $activeColor - - 0 ${nextId++}",
            ),
        )
    }

    private fun addRawOccurrence(
        account: ChessAccount,
        position: Position,
        color: String,
        spec: GameSpec,
    ) {
        val game =
            gameRepository.save(
                Game(
                    chessAccount = account,
                    platformGameId = "practical-game-${nextId++}",
                    pgn = pgn(spec, account, color),
                    timeControl = spec.timeControl,
                    playedAt = spec.playedAt,
                    result = spec.sourceResult,
                    whiteUsername =
                        spec.whiteUsername
                            ?: if (color == "WHITE") account.username else "opponent-${nextId++}",
                    blackUsername =
                        spec.blackUsername
                            ?: if (color == "BLACK") account.username else "opponent-${nextId++}",
                ),
            )
        repeat(spec.repetitions) { repetition ->
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = account,
                    plyNumber = repetition + 1,
                    movePlayed = spec.san,
                    playerColor = color,
                ),
            )
        }
    }

    private fun pgn(
        spec: GameSpec,
        account: ChessAccount,
        color: String,
    ): String {
        if (spec.malformedHeader) return "[White \"${account.username}\"\n\n1. e4"
        if (spec.noHeader) return "1. e4 e5"
        val white = spec.pgnWhite ?: if (color == "WHITE") account.username else "opponent"
        val black = spec.pgnBlack ?: if (color == "BLACK") account.username else "opponent"
        return buildList {
            add("[White \"$white\"]")
            add("[Black \"$black\"]")
            spec.pgnResult?.let { add("[Result \"$it\"]") }
            spec.variant?.let { add("[Variant \"$it\"]") }
        }.joinToString("\n", postfix = "\n\n1. e4")
    }

    private data class GameSpec(
        val sourceResult: String?,
        val san: String = "bad",
        val evaluated: Boolean = true,
        val repetitions: Int = 1,
        val pgnResult: String? = sourceResultToPgn(sourceResult),
        val variant: String? = null,
        val malformedHeader: Boolean = false,
        val noHeader: Boolean = false,
        val timeControl: String? = "blitz",
        val playedAt: Instant = Instant.now(),
        val whiteUsername: String? = null,
        val blackUsername: String? = null,
        val pgnWhite: String? = null,
        val pgnBlack: String? = null,
    )

    companion object {
        private var nextId = 1

        private fun sourceResultToPgn(result: String?): String? =
            when (result?.lowercase()) {
                "win" -> "1-0"
                "checkmated", "resigned", "timeout", "abandoned", "lose" -> "0-1"
                "agreed", "repetition", "stalemate", "insufficient", "50move", "timevsinsufficient" -> "1/2-1/2"
                else -> null
            }
    }
}
