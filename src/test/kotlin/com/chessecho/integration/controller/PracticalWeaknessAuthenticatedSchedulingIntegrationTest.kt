package com.chessecho.integration.controller

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.AuthIdentityRepository
import com.chessecho.repository.AuthSessionRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.service.auth.IdentitySessionService
import com.chessecho.service.auth.VerifiedIdentityClaims
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

abstract class PracticalWeaknessAuthenticatedSchedulingContract {
    @Autowired
    protected lateinit var restTemplate: TestRestTemplate

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    @Autowired
    protected lateinit var appUserRepository: AppUserRepository

    @Autowired
    protected lateinit var authIdentityRepository: AuthIdentityRepository

    @Autowired
    protected lateinit var authSessionRepository: AuthSessionRepository

    @Autowired
    protected lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    protected lateinit var gameRepository: GameRepository

    @Autowired
    protected lateinit var positionRepository: PositionRepository

    @Autowired
    protected lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    protected lateinit var engineAnalysisRepository: EngineAnalysisRepository

    @Autowired
    protected lateinit var puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository

    @Autowired
    protected lateinit var identitySessionService: IdentitySessionService

    protected abstract val rankingEnabled: Boolean

    private lateinit var account: ChessAccount
    private lateinit var sessionSecret: String
    private lateinit var schedulingUserId: UUID
    private lateinit var poorPosition: Position
    private lateinit var successfulPosition: Position

    @BeforeEach
    fun createAuthenticatedCandidates() {
        val subject = "practical-scheduling-${UUID.randomUUID()}"
        val session =
            identitySessionService.establishSession(
                VerifiedIdentityClaims(
                    issuer = "practical-scheduling-integration",
                    subject = subject,
                    emailSnapshot = "$subject@example.test",
                    emailVerified = true,
                ),
                devPrincipal = false,
            )
        sessionSecret = session.rawSecret
        schedulingUserId = session.principal.appUserId
        account =
            chessAccountRepository.save(
                ChessAccount(
                    user = null,
                    platform = "CHESS_COM",
                    username = "practical-${UUID.randomUUID().toString().take(8)}",
                ),
            )
        poorPosition = candidate("poor", evalLoss = 1.6, outcome = "resigned")
        successfulPosition = candidate("successful", evalLoss = 1.8, outcome = "win")
    }

    @AfterEach
    fun clearAuthenticatedCandidates() {
        puzzleSchedulingEventRepository.deleteAll()
        engineAnalysisRepository.deleteAll()
        positionOccurrenceRepository.deleteAll()
        positionRepository.deleteAll()
        gameRepository.deleteAll()
        chessAccountRepository.deleteAll()
        authSessionRepository.deleteAll()
        authIdentityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `both endpoints preserve practical ranking and authenticated scheduling page order`() {
        val initialExpected =
            if (rankingEnabled) {
                listOf(poorPosition.id.toString(), successfulPosition.id.toString())
            } else {
                listOf(successfulPosition.id.toString(), poorPosition.id.toString())
            }
        val initialWeaknesses = weaknesses()
        val initialPuzzles = puzzles()

        assertEquals(initialExpected, initialWeaknesses.map { it["positionId"].asText() })
        assertEquals(initialExpected, initialPuzzles.map { it["puzzleId"].asText() })
        assertEquals(initialExpected.first(), weaknesses(page = 0).single()["positionId"].asText())
        assertEquals(initialExpected.first(), puzzles(page = 0).single()["puzzleId"].asText())
        assertEquals(initialExpected.last(), weaknesses(page = 1).single()["positionId"].asText())
        assertEquals(initialExpected.last(), puzzles(page = 1).single()["puzzleId"].asText())

        val byPosition = initialWeaknesses.associateBy { it["positionId"].asText() }
        assertEquals(8.0, byPosition.getValue(poorPosition.id.toString())["priority"].asDouble(), 0.01)
        assertEquals(9.0, byPosition.getValue(successfulPosition.id.toString())["priority"].asDouble(), 0.01)
        assertEquals(
            if (rankingEnabled) 10.0 else 8.0,
            byPosition.getValue(poorPosition.id.toString())["recommendationPriority"].asDouble(),
            0.01,
        )
        assertEquals(
            if (rankingEnabled) 6.75 else 9.0,
            byPosition.getValue(successfulPosition.id.toString())["recommendationPriority"].asDouble(),
            0.01,
        )
        assertEquals(rankingEnabled, byPosition.getValue(poorPosition.id.toString())["practicalEvidence"]["rankingApplied"].asBoolean())

        val scheduledPosition = candidate("scheduled", evalLoss = 1.4, outcome = "resigned")
        val appUser = appUserRepository.findById(schedulingUserId).orElseThrow()
        repeat(6) {
            puzzleSchedulingEventRepository.save(
                PuzzleSchedulingEvent(
                    appUser = appUser,
                    chessAccount = account,
                    position = scheduledPosition,
                    playerColor = "WHITE",
                    eventType = SchedulingEventType.FAILED,
                    occurredAt = Instant.now(),
                ),
            )
        }

        val finalWeaknesses = weaknesses()
        val finalPuzzles = puzzles()
        val expectedAfterScheduling =
            if (rankingEnabled) {
                listOf(scheduledPosition.id.toString(), poorPosition.id.toString(), successfulPosition.id.toString())
            } else {
                listOf(scheduledPosition.id.toString(), successfulPosition.id.toString(), poorPosition.id.toString())
            }
        assertEquals(expectedAfterScheduling, finalWeaknesses.map { it["positionId"].asText() })
        assertEquals(expectedAfterScheduling, finalPuzzles.map { it["puzzleId"].asText() })
        assertTrue(
            finalWeaknesses.first()["recommendationPriority"].asDouble() <
                finalWeaknesses[1]["recommendationPriority"].asDouble(),
        )
        expectedAfterScheduling.indices.forEach { page ->
            assertEquals(expectedAfterScheduling[page], weaknesses(page = page).single()["positionId"].asText())
            assertEquals(expectedAfterScheduling[page], puzzles(page = page).single()["puzzleId"].asText())
        }
    }

    private fun candidate(
        suffix: String,
        evalLoss: Double,
        outcome: String,
    ): Position {
        val position =
            positionRepository.save(
                Position(
                    hash = "authenticated-practical-$suffix-${UUID.randomUUID()}",
                    fen = "8/8/8/8/8/8/8/K6k w - - 0 1",
                ),
            )
        repeat(5) { index ->
            val gameResult = if (outcome == "win") "1-0" else "0-1"
            val game =
                gameRepository.save(
                    Game(
                        chessAccount = account,
                        platformGameId = "authenticated-practical-$suffix-$index-${UUID.randomUUID()}",
                        pgn = "[White \"${account.username}\"]\n[Black \"opponent\"]\n[Result \"$gameResult\"]\n\n1. e4",
                        result = outcome,
                        whiteUsername = account.username,
                        blackUsername = "opponent",
                        playedAt = Instant.now(),
                    ),
                )
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "bad",
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
                    bestMove = "best",
                    bestMoveEvalCp = 100,
                    analyzedAt = Instant.now(),
                ),
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "bad",
                evalCp = 0,
                evalLossFromBest = evalLoss,
            ),
        )
        engineAnalysisRepository.save(analysis)
        return position
    }

    private fun weaknesses(page: Int? = null): List<JsonNode> =
        get(
            "/api/positions/weaknesses?accountId=${account.id}&playerColor=WHITE&minEvalLoss=0.8" +
                (page?.let { "&page=$it&size=1" } ?: ""),
        )

    private fun puzzles(page: Int? = null): List<JsonNode> =
        get(
            "/api/puzzles?accountId=${account.id}&playerColor=WHITE&minEvalLoss=0.8" +
                (page?.let { "&page=$it&limit=1" } ?: ""),
        )

    private fun get(path: String): List<JsonNode> {
        val headers = HttpHeaders().apply { add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=$sessionSecret") }
        val response = restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode, response.body)
        return objectMapper.readTree(response.body).toList()
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PracticalWeaknessAuthenticatedSchedulingEnabledIntegrationTest :
    PracticalWeaknessAuthenticatedSchedulingContract() {
    override val rankingEnabled = true
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = ["chess.weakness.practical.ranking-enabled=false"])
class PracticalWeaknessAuthenticatedSchedulingDisabledIntegrationTest :
    PracticalWeaknessAuthenticatedSchedulingContract() {
    override val rankingEnabled = false
}
