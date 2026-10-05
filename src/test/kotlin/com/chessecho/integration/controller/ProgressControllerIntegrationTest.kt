package com.chessecho.integration.controller

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import com.chessecho.domain.TrainingAttempt
import com.chessecho.domain.TrainingAttemptMode
import com.chessecho.domain.TrainingAttemptOutcome
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.TrainingAttemptRepository
import com.chessecho.service.auth.IdentitySessionService
import com.chessecho.service.auth.VerifiedIdentityClaims
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProgressControllerIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var identitySessionService: IdentitySessionService

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
    private lateinit var occurrenceRepository: PositionOccurrenceRepository

    @Autowired
    private lateinit var puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository

    @Autowired
    private lateinit var trainingAttemptRepository: TrainingAttemptRepository

    @Autowired
    private lateinit var engineAnalysisRepository: EngineAnalysisRepository

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = ["invalid", "", "0", "-0.3", "NaN", "Infinity", "-Infinity", "1e309"])
    fun `invalid or missing threshold returns structured validation error`(threshold: String?) {
        val subject = "progress-validation-${UUID.randomUUID()}"
        val session =
            identitySessionService.establishSession(
                VerifiedIdentityClaims(
                    issuer = "integration-test",
                    subject = subject,
                    emailSnapshot = "$subject@example.test",
                    emailVerified = true,
                ),
                devPrincipal = false,
            )
        val headers = HttpHeaders().apply { add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=${session.rawSecret}") }
        val query = threshold?.let { "&minEvalLoss=$it" }.orEmpty()

        val response =
            restTemplate.exchange(
                "/api/positions/${UUID.randomUUID()}/progress?playerColor=WHITE$query",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        val body = objectMapper.readTree(response.body)
        assertEquals("VALIDATION_ERROR", body["error"].textValue())
        assertTrue(body["details"].isArray)
        assertTrue(body["details"].size() > 0)
    }

    @Test
    fun `selected threshold returns 23 mistakes over 26 attempts`() {
        val subject = "progress-threshold-${UUID.randomUUID()}"
        val session =
            identitySessionService.establishSession(
                VerifiedIdentityClaims(
                    issuer = "integration-test",
                    subject = subject,
                    emailSnapshot = "$subject@example.test",
                    emailVerified = true,
                ),
                devPrincipal = false,
            )
        val user = appUserRepository.findById(session.principal.appUserId).orElseThrow()
        val account = chessAccountRepository.save(ChessAccount(user = user, platform = "CHESS_COM", username = subject))
        val position = positionRepository.save(Position(hash = UUID.randomUUID().toString(), fen = "8/8/8/8/8/8/8/8 w - -"))
        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 0, bestMove = "best", bestMoveEvalCp = 0)
        (0 until 26).forEach { index ->
            val game =
                gameRepository.save(
                    Game(
                        chessAccount = account,
                        platformGameId = "$subject-$index",
                        pgn = """[Result "*"]""",
                        result = "win",
                        playedAt = Instant.parse("2026-09-01T12:00:00Z").plusSeconds(index.toLong()),
                        whiteUsername = subject,
                        blackUsername = "opponent",
                    ),
                )
            occurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "move-$index",
                    playerColor = PlayerColor.WHITE.name,
                ),
            )
            analysis.moveEvaluations.add(
                MoveEvaluation(
                    engineAnalysis = analysis,
                    move = "move-$index",
                    evalCp = 0,
                    evalLossFromBest = if (index < 23) 0.34 else 0.29,
                ),
            )
        }
        engineAnalysisRepository.save(analysis)
        val headers = HttpHeaders().apply { add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=${session.rawSecret}") }

        val response =
            restTemplate.exchange(
                "/api/positions/${position.id}/progress?playerColor=WHITE&minEvalLoss=0.3",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        val baseline = objectMapper.readTree(response.body)["baseline"]
        assertEquals(26, baseline["sourceEncounterCount"].intValue())
        assertEquals(88.461538, baseline["mistakeRate"].doubleValue(), 0.000001)
    }

    @ParameterizedTest
    @EnumSource(value = PlayerColor::class, names = ["WHITE", "BLACK"])
    fun `authenticated history returns scoped solved intervals and excludes undated encounters`(color: PlayerColor) {
        val subject = "progress-${UUID.randomUUID()}"
        val session =
            identitySessionService.establishSession(
                VerifiedIdentityClaims(
                    issuer = "integration-test",
                    subject = subject,
                    emailSnapshot = "$subject@example.test",
                    emailVerified = true,
                ),
                devPrincipal = false,
            )
        val user = appUserRepository.findById(session.principal.appUserId).orElseThrow()
        val foreignSubject = "$subject-foreign"
        val foreignSession =
            identitySessionService.establishSession(
                VerifiedIdentityClaims(
                    issuer = "integration-test",
                    subject = foreignSubject,
                    emailSnapshot = "$foreignSubject@example.test",
                    emailVerified = true,
                ),
                devPrincipal = false,
            )
        val foreignUser = appUserRepository.findById(foreignSession.principal.appUserId).orElseThrow()
        val firstPlayedAt = Instant.parse("2026-09-01T12:00:00Z")
        val account =
            chessAccountRepository.save(
                ChessAccount(
                    user = user,
                    platform = "CHESS_COM",
                    username = subject,
                    createdAt = firstPlayedAt.minusSeconds(60 * 60 * 24),
                ),
            )
        val position =
            positionRepository.save(
                Position(hash = UUID.randomUUID().toString(), fen = "8/8/8/8/8/8/8/8 ${if (color == PlayerColor.WHITE) "w" else "b"} - -"),
            )
        val otherPosition =
            positionRepository.save(
                Position(hash = UUID.randomUUID().toString(), fen = "8/8/8/8/8/8/8/8 ${if (color == PlayerColor.WHITE) "b" else "w"} - -"),
            )
        val otherAccount =
            chessAccountRepository.save(
                ChessAccount(user = user, platform = "CHESS_COM", username = "$subject-other", createdAt = firstPlayedAt),
            )
        val results = if (color == PlayerColor.WHITE) listOf("win", "resigned", "agreed") else listOf("resigned", "win", "agreed")
        results.withIndex().reversed().forEach { (index, result) ->
            val game =
                gameRepository.save(
                    Game(
                        chessAccount = account,
                        platformGameId = "$subject-$index",
                        pgn = """[Result "*"]""",
                        result = result,
                        playedAt = firstPlayedAt.plusSeconds(index * 60L),
                        whiteUsername = if (color == PlayerColor.WHITE) account.username else "opponent",
                        blackUsername = if (color == PlayerColor.BLACK) account.username else "opponent",
                    ),
                )
            occurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "Nf3",
                    playerColor = color.name,
                ),
            )
        }

        fun addUndatedOccurrence(
            occurrenceAccount: ChessAccount,
            occurrencePosition: Position,
            occurrenceColor: PlayerColor,
            gameSuffix: String,
        ) {
            val game =
                gameRepository.save(
                    Game(
                        chessAccount = occurrenceAccount,
                        platformGameId = "$subject-$gameSuffix",
                        pgn = """[Result "*"]""",
                        result = "unknown",
                        playedAt = null,
                        whiteUsername = occurrenceAccount.username,
                        blackUsername = "opponent",
                    ),
                )
            occurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = occurrencePosition,
                    chessAccount = occurrenceAccount,
                    plyNumber = 1,
                    movePlayed = "Nf3",
                    playerColor = occurrenceColor.name,
                ),
            )
        }
        addUndatedOccurrence(account, position, color, "undated-scoped")
        addUndatedOccurrence(
            account,
            position,
            if (color == PlayerColor.WHITE) PlayerColor.BLACK else PlayerColor.WHITE,
            "undated-other-color",
        )
        addUndatedOccurrence(account, otherPosition, color, "undated-other-position")
        addUndatedOccurrence(otherAccount, position, color, "undated-other-account")

        val firstCheckpointAt = firstPlayedAt.plusSeconds(30)
        val secondCheckpointAt = firstPlayedAt.plusSeconds(180)
        val checkpoints =
            listOf(
                PuzzleSchedulingEvent(
                    appUser = user,
                    chessAccount = account,
                    position = position,
                    playerColor = color.name,
                    eventType = SchedulingEventType.SOLVED,
                    occurredAt = firstCheckpointAt,
                ),
                PuzzleSchedulingEvent(
                    appUser = user,
                    chessAccount = account,
                    position = position,
                    playerColor = color.name,
                    eventType = SchedulingEventType.SOLVED,
                    occurredAt = secondCheckpointAt,
                ),
                PuzzleSchedulingEvent(
                    appUser = foreignUser,
                    chessAccount = account,
                    position = position,
                    playerColor = color.name,
                    eventType = SchedulingEventType.SOLVED,
                    occurredAt = firstPlayedAt.minusSeconds(30),
                ),
                PuzzleSchedulingEvent(
                    appUser = user,
                    chessAccount = otherAccount,
                    position = position,
                    playerColor = color.name,
                    eventType = SchedulingEventType.SOLVED,
                    occurredAt = firstPlayedAt.minusSeconds(30),
                ),
                PuzzleSchedulingEvent(
                    appUser = user,
                    chessAccount = account,
                    position = otherPosition,
                    playerColor = color.name,
                    eventType = SchedulingEventType.SOLVED,
                    occurredAt = firstPlayedAt.minusSeconds(30),
                ),
                PuzzleSchedulingEvent(
                    appUser = user,
                    chessAccount = account,
                    position = position,
                    playerColor = if (color == PlayerColor.WHITE) PlayerColor.BLACK.name else PlayerColor.WHITE.name,
                    eventType = SchedulingEventType.SOLVED,
                    occurredAt = firstPlayedAt.minusSeconds(30),
                ),
            )
        puzzleSchedulingEventRepository.saveAll(checkpoints)
        trainingAttemptRepository.saveAll(
            TrainingAttemptOutcome.entries.map { outcome ->
                TrainingAttempt(
                    puzzleId = position.id.toString(),
                    mode = TrainingAttemptMode.STOPWATCH,
                    elapsedMs = 1_000,
                    outcome = outcome,
                    chessAccount = account,
                    appUser = user,
                    createdAt = outcome.ordinal.let { secondCheckpointAt.plusSeconds(it.toLong()) },
                )
            },
        )
        val boundaryGame =
            gameRepository.save(
                Game(
                    chessAccount = account,
                    platformGameId = "$subject-checkpoint-boundary",
                    pgn = """[Result "*"]""",
                    result = "win",
                    playedAt = firstCheckpointAt,
                    whiteUsername = if (color == PlayerColor.WHITE) account.username else "opponent",
                    blackUsername = if (color == PlayerColor.BLACK) account.username else "opponent",
                ),
            )
        occurrenceRepository.save(
            PositionOccurrence(
                game = boundaryGame,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "Nf3",
                playerColor = color.name,
            ),
        )
        val headers = HttpHeaders().apply { add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=${session.rawSecret}") }

        val response =
            restTemplate.exchange(
                "/api/positions/${position.id}/progress?playerColor=${color.name}&minEvalLoss=0.3",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = objectMapper.readTree(response.body)
        assertEquals(
            setOf(
                "positionId",
                "playerColor",
                "baseline",
                "points",
                "currentIntervalState",
                "excludedUndatedEncounters",
                "mistakeRateChange",
                "winRateChange",
                "assessment",
            ),
            body.fieldNames().asSequence().toSet(),
        )
        assertEquals(position.id.toString(), body["positionId"].textValue())
        assertEquals(color.name, body["playerColor"].textValue())
        val baseline = body["baseline"]
        assertEquals(setOf("occurredAt", "mistakeRate", "winRate", "sourceEncounterCount"), baseline.fieldNames().asSequence().toSet())
        assertEquals(firstPlayedAt, Instant.parse(baseline["occurredAt"].textValue()))
        assertEquals(1, baseline["sourceEncounterCount"].intValue())
        assertEquals(100.0, baseline["winRate"].doubleValue())
        val points = body["points"]
        assertEquals(1, points.size())
        assertEquals(
            setOf("checkpointId", "occurredAt", "mistakeRate", "winRate", "attempts", "open"),
            points[0].fieldNames().asSequence().toSet(),
        )
        assertEquals(3, points[0]["attempts"].intValue())
        assertEquals(results.lastIndex * 60L, Duration.between(firstPlayedAt, Instant.parse(points[0]["occurredAt"].textValue())).seconds)
        val expectedIntervalWinRate = if (color == PlayerColor.WHITE) 100.0 / 3 else 0.0
        assertEquals(expectedIntervalWinRate, points[0]["winRate"].doubleValue(), 0.000001)
        assertEquals(false, points[0]["open"].booleanValue())
        assertEquals("OPEN_AWAITING_EVIDENCE", body["currentIntervalState"].textValue())
        assertEquals(1, body["excludedUndatedEncounters"].intValue())
        assertEquals(expectedIntervalWinRate - 100.0, body["winRateChange"].doubleValue(), 0.000001)
        assertEquals(true, body["mistakeRateChange"].isNull)
        assertEquals(
            "Your mistake rate stayed the same, and your win rate has decreased.",
            body["assessment"].textValue(),
        )
    }

    @Test
    fun `guest progress history requests require authentication`() {
        val positionId = UUID.randomUUID()

        val first =
            restTemplate.getForEntity(
                "/api/positions/$positionId/progress?playerColor=WHITE&minEvalLoss=0.3",
                String::class.java,
            )
        val repeated =
            restTemplate.getForEntity(
                "/api/positions/$positionId/progress?playerColor=WHITE&minEvalLoss=0.3",
                String::class.java,
            )

        assertEquals(HttpStatus.UNAUTHORIZED, first.statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, repeated.statusCode)
    }
}
