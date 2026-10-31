package com.chessecho.integration.controller

import com.chessecho.domain.AccountConnection
import com.chessecho.domain.AppUser
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
import com.chessecho.dto.AccountAssociationRequest
import com.chessecho.repository.AccountConnectionRepository
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.TrainingAttemptRepository
import com.chessecho.service.AccountOwnershipService
import com.chessecho.service.auth.EstablishedSession
import com.chessecho.service.auth.IdentitySessionService
import com.chessecho.service.auth.VerifiedIdentityClaims
import com.fasterxml.jackson.databind.JsonNode
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
    private lateinit var connections: AccountConnectionRepository

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

    @Autowired
    private lateinit var accountOwnershipService: AccountOwnershipService

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
        val account = chessAccountRepository.save(ChessAccount(platform = "CHESS_COM", username = subject))
        connections.saveAndFlush(
            AccountConnection(
                appUser = appUserRepository.findById(session.principal.appUserId).orElseThrow(),
                chessAccount = account,
            ),
        )
        val headers = HttpHeaders().apply { add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=${session.rawSecret}") }
        val query = threshold?.let { "&minEvalLoss=$it" }.orEmpty()

        val response =
            restTemplate.exchange(
                "/api/positions/${UUID.randomUUID()}/progress?accountId=${account.id}&playerColor=WHITE$query",
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
        val session = establishSession(subject)
        val user = appUserRepository.findById(session.principal.appUserId).orElseThrow()
        val account = chessAccountRepository.save(ChessAccount(platform = "CHESS_COM", username = subject))
        connections.saveAndFlush(AccountConnection(appUser = user, chessAccount = account))
        val position = positionRepository.save(Position(hash = UUID.randomUUID().toString(), fen = "8/8/8/8/8/8/8/8 w - -"))
        seedProgressThresholdData(account, position, subject, mistakeCount = 23, totalAttempts = 26)

        val headers = HttpHeaders().apply { add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=${session.rawSecret}") }
        val response =
            restTemplate.exchange(
                "/api/positions/${position.id}/progress?accountId=${account.id}&playerColor=WHITE&minEvalLoss=0.3",
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
        val session = establishSession(subject)
        val user = appUserRepository.findById(session.principal.appUserId).orElseThrow()
        val foreignSession = establishSession("$subject-foreign")
        val foreignUser = appUserRepository.findById(foreignSession.principal.appUserId).orElseThrow()

        val firstPlayedAt = Instant.parse("2026-09-01T12:00:00Z")
        val account =
            chessAccountRepository.save(
                ChessAccount(platform = "CHESS_COM", username = subject, createdAt = firstPlayedAt.minusSeconds(86400)),
            )
        connections.saveAndFlush(AccountConnection(appUser = user, chessAccount = account))
        val otherAccount =
            chessAccountRepository.save(
                ChessAccount(platform = "CHESS_COM", username = "$subject-other", createdAt = firstPlayedAt),
            )

        val fen = if (color == PlayerColor.WHITE) "8/8/8/8/8/8/8/8 w - -" else "8/8/8/8/8/8/8/8 b - -"
        val otherFen = if (color == PlayerColor.WHITE) "8/8/8/8/8/8/8/8 b - -" else "8/8/8/8/8/8/8/8 w - -"
        val position = positionRepository.save(Position(hash = UUID.randomUUID().toString(), fen = fen))
        val otherPosition = positionRepository.save(Position(hash = UUID.randomUUID().toString(), fen = otherFen))

        val results = seedProgressHistoryGames(subject, account, position, color, firstPlayedAt)
        seedUndatedOccurrences(subject, account, otherAccount, position, otherPosition, color)

        seedCheckpointsAndAttempts(
            user = user,
            foreignUser = foreignUser,
            account = account,
            otherAccount = otherAccount,
            position = position,
            otherPosition = otherPosition,
            color = color,
            firstPlayedAt = firstPlayedAt,
        )

        val headers = HttpHeaders().apply { add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=${session.rawSecret}") }
        val response =
            restTemplate.exchange(
                "/api/positions/${position.id}/progress?accountId=${account.id}&playerColor=${color.name}&minEvalLoss=0.3",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertHistoryResponseJson(objectMapper.readTree(response.body), position, color, firstPlayedAt, results)
        assertAccountLifecycle(account, otherAccount, position, color, headers, session)
    }

    private fun establishSession(subject: String) =
        identitySessionService.establishSession(
            VerifiedIdentityClaims(
                issuer = "integration-test",
                subject = subject,
                emailSnapshot = "$subject@example.test",
                emailVerified = true,
            ),
            devPrincipal = false,
        )

    private fun seedProgressThresholdData(
        account: ChessAccount,
        position: Position,
        subject: String,
        mistakeCount: Int,
        totalAttempts: Int,
    ): EngineAnalysis {
        val analysis = EngineAnalysis(position = position, depth = 16, baselineEvalCp = 0, bestMove = "best", bestMoveEvalCp = 0)
        (0 until totalAttempts).forEach { index ->
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
                    evalLossFromBest = if (index < mistakeCount) 0.34 else 0.29,
                ),
            )
        }
        return engineAnalysisRepository.save(analysis)
    }

    private fun seedProgressHistoryGames(
        subject: String,
        account: ChessAccount,
        position: Position,
        color: PlayerColor,
        firstPlayedAt: Instant,
    ): List<String> {
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
        return results
    }

    private fun seedUndatedOccurrences(
        subject: String,
        account: ChessAccount,
        otherAccount: ChessAccount,
        position: Position,
        otherPosition: Position,
        color: PlayerColor,
    ) {
        fun addUndated(
            occAccount: ChessAccount,
            occPos: Position,
            occColor: PlayerColor,
            suffix: String,
        ) {
            val game =
                gameRepository.save(
                    Game(
                        chessAccount = occAccount,
                        platformGameId = "$subject-$suffix",
                        pgn = """[Result "*"]""",
                        result = "unknown",
                        playedAt = null,
                        whiteUsername = occAccount.username,
                        blackUsername = "opponent",
                    ),
                )
            occurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = occPos,
                    chessAccount = occAccount,
                    plyNumber = 1,
                    movePlayed = "Nf3",
                    playerColor = occColor.name,
                ),
            )
        }
        addUndated(account, position, color, "undated-scoped")
        addUndated(account, position, if (color == PlayerColor.WHITE) PlayerColor.BLACK else PlayerColor.WHITE, "undated-other-color")
        addUndated(account, otherPosition, color, "undated-other-position")
        addUndated(otherAccount, position, color, "undated-other-account")
    }

    private fun seedCheckpointsAndAttempts(
        user: AppUser,
        foreignUser: AppUser,
        account: ChessAccount,
        otherAccount: ChessAccount,
        position: Position,
        otherPosition: Position,
        color: PlayerColor,
        firstPlayedAt: Instant,
    ) {
        val firstCheckpointAt = firstPlayedAt.plusSeconds(30)
        val secondCheckpointAt = firstPlayedAt.plusSeconds(180)
        seedCheckpoints(
            user,
            foreignUser,
            account,
            otherAccount,
            position,
            otherPosition,
            color,
            firstPlayedAt,
            firstCheckpointAt,
            secondCheckpointAt,
        )
        seedAttemptsAndBoundaryGame(user, account, position, color, firstCheckpointAt, secondCheckpointAt)
    }

    private fun seedCheckpoints(
        user: AppUser,
        foreignUser: AppUser,
        account: ChessAccount,
        otherAccount: ChessAccount,
        position: Position,
        otherPosition: Position,
        color: PlayerColor,
        firstPlayedAt: Instant,
        firstCheckpointAt: Instant,
        secondCheckpointAt: Instant,
    ) {
        fun chk(
            u: AppUser,
            a: ChessAccount,
            p: Position,
            c: String,
            t: Instant,
        ) = PuzzleSchedulingEvent(
            appUser = u,
            chessAccount = a,
            position = p,
            playerColor = c,
            eventType = SchedulingEventType.SOLVED,
            occurredAt = t,
        )

        puzzleSchedulingEventRepository.saveAll(
            listOf(
                chk(user, account, position, color.name, firstCheckpointAt),
                chk(user, account, position, color.name, secondCheckpointAt),
                chk(foreignUser, account, position, color.name, firstPlayedAt.minusSeconds(30)),
                chk(user, otherAccount, position, color.name, firstPlayedAt.minusSeconds(30)),
                chk(user, account, otherPosition, color.name, firstPlayedAt.minusSeconds(30)),
                chk(
                    user,
                    account,
                    position,
                    if (color == PlayerColor.WHITE) PlayerColor.BLACK.name else PlayerColor.WHITE.name,
                    firstPlayedAt.minusSeconds(30),
                ),
            ),
        )
    }

    private fun seedAttemptsAndBoundaryGame(
        user: AppUser,
        account: ChessAccount,
        position: Position,
        color: PlayerColor,
        firstCheckpointAt: Instant,
        secondCheckpointAt: Instant,
    ) {
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
                    platformGameId = "$user-checkpoint-boundary",
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
    }

    private fun assertHistoryResponseJson(
        body: JsonNode,
        position: Position,
        color: PlayerColor,
        firstPlayedAt: Instant,
        results: List<String>,
    ) {
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

    private fun assertAccountLifecycle(
        account: ChessAccount,
        otherAccount: ChessAccount,
        position: Position,
        color: PlayerColor,
        headers: HttpHeaders,
        session: EstablishedSession,
    ) {
        val unconnectedAccountResponse =
            restTemplate.exchange(
                "/api/positions/${position.id}/progress?accountId=${otherAccount.id}&playerColor=${color.name}&minEvalLoss=0.3",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        assertEquals(HttpStatus.NOT_FOUND, unconnectedAccountResponse.statusCode)
        assertEquals("ACCOUNT_NOT_FOUND", objectMapper.readTree(unconnectedAccountResponse.body).path("error").asText())

        accountOwnershipService.disconnect(account.id, session.principal)
        val disconnectedResponse =
            restTemplate.exchange(
                "/api/positions/${position.id}/progress?accountId=${account.id}&playerColor=${color.name}&minEvalLoss=0.3",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        assertEquals(HttpStatus.NOT_FOUND, disconnectedResponse.statusCode)

        accountOwnershipService.associate(
            session.principal,
            AccountAssociationRequest(platform = "CHESS_COM", username = account.username),
        )
        val reconnectedResponse =
            restTemplate.exchange(
                "/api/positions/${position.id}/progress?accountId=${account.id}&playerColor=${color.name}&minEvalLoss=0.3",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        assertEquals(HttpStatus.OK, reconnectedResponse.statusCode)
        assertEquals(
            1,
            objectMapper
                .readTree(reconnectedResponse.body)
                .path("baseline")
                .path("sourceEncounterCount")
                .asInt(),
        )
    }

    @Test
    fun `authenticated progress requires an explicit connected account`() {
        val subject = "progress-selection-${UUID.randomUUID()}"
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
        val account = chessAccountRepository.save(ChessAccount(platform = "CHESS_COM", username = subject))
        val positionId = UUID.randomUUID()
        val headers = HttpHeaders().apply { add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=${session.rawSecret}") }

        val missingSelection =
            restTemplate.exchange(
                "/api/positions/$positionId/progress?playerColor=WHITE&minEvalLoss=0.3",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        assertEquals(HttpStatus.BAD_REQUEST, missingSelection.statusCode)
        assertEquals("ACCOUNT_SELECTION_REQUIRED", objectMapper.readTree(missingSelection.body).path("error").asText())

        val unconnectedSelection =
            restTemplate.exchange(
                "/api/positions/$positionId/progress?accountId=${account.id}&playerColor=WHITE&minEvalLoss=0.3",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        assertEquals(HttpStatus.NOT_FOUND, unconnectedSelection.statusCode)
        assertEquals("ACCOUNT_NOT_FOUND", objectMapper.readTree(unconnectedSelection.body).path("error").asText())

        val unknownSelection =
            restTemplate.exchange(
                "/api/positions/$positionId/progress?accountId=${UUID.randomUUID()}&playerColor=WHITE&minEvalLoss=0.3",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        assertEquals(HttpStatus.NOT_FOUND, unknownSelection.statusCode)
        assertEquals("ACCOUNT_NOT_FOUND", objectMapper.readTree(unknownSelection.body).path("error").asText())
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
