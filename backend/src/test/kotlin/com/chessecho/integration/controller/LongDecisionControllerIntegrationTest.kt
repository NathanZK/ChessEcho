package com.chessecho.integration.controller

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.service.auth.IdentitySessionService
import com.chessecho.service.auth.VerifiedIdentityClaims
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LongDecisionControllerIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var identitySessionService: IdentitySessionService

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var occurrenceRepository: PositionOccurrenceRepository

    private val accountIds = mutableListOf<UUID>()
    private val gameIds = mutableListOf<UUID>()
    private val positionIds = mutableListOf<UUID>()
    private val occurrenceIds = mutableListOf<UUID>()

    @AfterEach
    fun tearDown() {
        occurrenceRepository.deleteAllById(occurrenceIds)
        gameRepository.deleteAllById(gameIds)
        positionRepository.deleteAllById(positionIds)
        chessAccountRepository.deleteAllById(accountIds)
    }

    @Test
    fun `filters account time control and inclusive threshold then returns pages in stable order`() {
        val session = session()
        val selectedAccount = account("long-decisions")
        val otherAccount = account("other-long-decisions")
        val newest = addOccurrence(selectedAccount, "BULLET", 30_000, "2026-02-03T12:00:00Z", "newest")
        addOccurrence(selectedAccount, "BULLET", 29_999, "2026-02-04T12:00:00Z", "below-threshold")
        addOccurrence(selectedAccount, "BULLET", null, "2026-02-05T12:00:00Z", "missing-time")
        val second = addOccurrence(selectedAccount, "BULLET", 30_000, "2026-02-02T12:00:00Z", "second")
        addOccurrence(selectedAccount, "BLITZ", 90_000, "2026-02-06T12:00:00Z", "other-control")
        addOccurrence(otherAccount, "BULLET", 60_000, "2026-02-06T12:00:00Z", "other-account")

        val pageZero = get(selectedAccount.id, "BULLET", 30, session.rawSecret, page = 0, size = 0)
        assertEquals(HttpStatus.OK, pageZero.statusCode)
        val firstBody = objectMapper.readTree(pageZero.body)
        assertEquals(2, firstBody["totalElements"].intValue())
        assertEquals(2, firstBody["totalPages"].intValue())
        assertEquals(0, firstBody["page"].intValue())
        assertEquals(1, firstBody["size"].intValue())
        assertTrue(firstBody["hasNext"].booleanValue())
        assertEquals(newest.id.toString(), firstBody["content"][0]["id"].textValue())
        assertEquals(30_000, firstBody["content"][0]["decisionTimeMs"].longValue())
        assertEquals(newest.position.id.toString(), firstBody["content"][0]["positionId"].textValue())
        assertEquals("WHITE", firstBody["content"][0]["playerColor"].textValue())
        assertEquals(1, firstBody["content"][0]["plyNumber"].intValue())
        assertEquals("Nf3", firstBody["content"][0]["movePlayed"].textValue())
        assertEquals("BULLET", firstBody["content"][0]["timeControl"].textValue())
        assertEquals(newest.game.platformGameId, firstBody["content"][0]["platformGameId"].textValue())
        assertEquals("opponent-newest", firstBody["content"][0]["opponentUsername"].textValue())
        assertEquals(newest.position.fen, firstBody["content"][0]["fen"].textValue())
        assertEquals("CRITICAL", firstBody["content"][0]["severity"].textValue())

        val pageOne = get(selectedAccount.id, "BULLET", 30, session.rawSecret, page = 1, size = 1)
        val secondBody = objectMapper.readTree(pageOne.body)
        assertEquals(second.id.toString(), secondBody["content"][0]["id"].textValue())
        assertFalse(secondBody["hasNext"].booleanValue())

        val empty = get(selectedAccount.id, "BULLET", 31, session.rawSecret)
        assertEquals(0, objectMapper.readTree(empty.body)["totalElements"].intValue())

        val singleOccurrence = get(selectedAccount.id, "BLITZ", 90, session.rawSecret)
        val singleBody = objectMapper.readTree(singleOccurrence.body)
        assertEquals(1, singleBody["totalElements"].intValue())
        assertEquals(1, singleBody["content"].size())
    }

    @Test
    fun `requires authentication and validates time control threshold and page`() {
        val session = session()
        val account = account("long-decision-validation")
        val authenticatedHeaders = HttpHeaders().apply { add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=${session.rawSecret}") }
        val missingThreshold =
            restTemplate.exchange(
                "/api/positions/long-decisions?accountId=${account.id}&timeControl=BULLET",
                HttpMethod.GET,
                HttpEntity<Void>(authenticatedHeaders),
                String::class.java,
            )

        assertEquals(HttpStatus.UNAUTHORIZED, get(account.id, "BULLET", 30).statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, missingThreshold.statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, get(account.id, "DAILY", 30, session.rawSecret).statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, get(account.id, "BULLET", 0, session.rawSecret).statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, get(account.id, "BULLET", 30, session.rawSecret, page = -1).statusCode)
    }

    @Test
    fun `orders deterministically by decision duration descending then game playedAt descending nulls last`() {
        val session = session()
        val account = account("ordering-test")

        val game1 = saveGame(account, "https://www.chess.com/game/live/111222333", "2026-02-10T15:00:00Z")
        val occ40k = addOccurrence(account, "RAPID", 40_000L, "2026-02-10T15:00:00Z", "g1-40k", 2, existingGame = game1)
        val occ60kNew = addOccurrence(account, "RAPID", 60_000L, "2026-02-10T15:00:00Z", "g1-60k", 10, existingGame = game1)
        val occ60kOld =
            addOccurrence(account, "RAPID", 60_000L, "2026-02-08T10:00:00Z", "g2-60k-old", 5, "https://www.chess.com/game/live/444555666")
        val occ60kNull =
            addOccurrence(account, "RAPID", 60_000L, null, "g3-60k-null", 8, "https://chess.com/game/daily/777888999")
        val occ120k =
            addOccurrence(account, "RAPID", 120_000L, "2026-02-01T12:00:00Z", "g4-120k", 12, "https://www.chess.com/game/live/999888777")

        val response = get(account.id, "RAPID", 30, session.rawSecret, page = 0, size = 10)
        assertEquals(HttpStatus.OK, response.statusCode)
        val content = objectMapper.readTree(response.body)["content"]
        assertEquals(5, content.size())

        assertEquals(occ120k.id.toString(), content[0]["id"].textValue())
        assertEquals(120_000L, content[0]["decisionTimeMs"].longValue())

        assertEquals(occ60kNew.id.toString(), content[1]["id"].textValue())
        assertEquals(60_000L, content[1]["decisionTimeMs"].longValue())

        assertEquals(occ60kOld.id.toString(), content[2]["id"].textValue())
        assertEquals(60_000L, content[2]["decisionTimeMs"].longValue())

        assertEquals(occ60kNull.id.toString(), content[3]["id"].textValue())
        assertEquals(60_000L, content[3]["decisionTimeMs"].longValue())

        assertEquals(occ40k.id.toString(), content[4]["id"].textValue())
        assertEquals(40_000L, content[4]["decisionTimeMs"].longValue())
    }

    @Test
    fun `supports minDurationSeconds, severity filtering, server-side pagination, and empty intersection`() {
        val session = session()
        val account = account("severity-filters")

        // Blitz thresholds: Minor [15s, 30s), Significant [30s, 60s), Critical [60s, inf)
        addOccurrence(account, "BLITZ", 10_000L, "2026-02-01T10:00:00Z", "no-concern")
        val minor1 = addOccurrence(account, "BLITZ", 20_000L, "2026-02-01T12:00:00Z", "minor-1")
        val minor2 = addOccurrence(account, "BLITZ", 28_000L, "2026-02-01T14:00:00Z", "minor-2")
        addOccurrence(account, "BLITZ", 45_000L, "2026-02-01T16:00:00Z", "significant-1")
        addOccurrence(account, "BLITZ", 75_000L, "2026-02-01T18:00:00Z", "critical-1")

        // 1. Query MINOR with minDurationSeconds=18 -> returns minor2 (28s) and minor1 (20s)
        val minorPageZero =
            get(
                accountId = account.id,
                timeControl = "BLITZ",
                minDurationSeconds = 18,
                severity = "MINOR",
                page = 0,
                size = 1,
                secret = session.rawSecret,
            )
        assertEquals(HttpStatus.OK, minorPageZero.statusCode)
        val body0 = objectMapper.readTree(minorPageZero.body)
        assertEquals(2, body0["totalElements"].intValue())
        assertEquals(2, body0["totalPages"].intValue())
        assertTrue(body0["hasNext"].booleanValue())
        assertEquals(minor2.id.toString(), body0["content"][0]["id"].textValue())
        assertEquals("MINOR", body0["content"][0]["severity"].textValue())

        // 2. Query page 1
        val minorPageOne =
            get(
                accountId = account.id,
                timeControl = "BLITZ",
                minDurationSeconds = 18,
                severity = "MINOR",
                page = 1,
                size = 1,
                secret = session.rawSecret,
            )
        val body1 = objectMapper.readTree(minorPageOne.body)
        assertEquals(minor1.id.toString(), body1["content"][0]["id"].textValue())
        assertFalse(body1["hasNext"].booleanValue())

        // 3. Empty intersection: minDurationSeconds=35 and severity=MINOR ([35s, 30s) = empty)
        val emptyIntersection =
            get(
                accountId = account.id,
                timeControl = "BLITZ",
                minDurationSeconds = 35,
                severity = "MINOR",
                secret = session.rawSecret,
            )
        assertEquals(HttpStatus.OK, emptyIntersection.statusCode)
        val emptyBody = objectMapper.readTree(emptyIntersection.body)
        assertEquals(0, emptyBody["totalElements"].intValue())
        assertEquals(0, emptyBody["content"].size())
    }

    @Test
    fun `minDurationSeconds takes precedence over thresholdSeconds in controller`() {
        val session = session()
        val account = account("precedence-test")
        addOccurrence(account, "RAPID", 20_000L, "2026-02-01T10:00:00Z", "under-min")
        val match = addOccurrence(account, "RAPID", 35_000L, "2026-02-01T12:00:00Z", "above-min")

        // minDurationSeconds=30 should override thresholdSeconds=10
        val response =
            get(
                accountId = account.id,
                timeControl = "RAPID",
                thresholdSeconds = 10,
                minDurationSeconds = 30,
                secret = session.rawSecret,
            )
        assertEquals(HttpStatus.OK, response.statusCode)
        val body = objectMapper.readTree(response.body)
        assertEquals(1, body["totalElements"].intValue())
        assertEquals(match.id.toString(), body["content"][0]["id"].textValue())
    }

    private fun saveGame(
        account: ChessAccount,
        platformGameId: String,
        playedAt: String?,
        timeControl: String = "rapid",
    ): Game =
        gameRepository
            .save(
                Game(
                    chessAccount = account,
                    platformGameId = platformGameId,
                    pgn = "[Result \"*\"]",
                    timeControl = timeControl,
                    playedAt = playedAt?.let { Instant.parse(it) },
                    whiteUsername = account.username,
                    blackUsername = "opponent-test",
                ),
            ).also { gameIds.add(it.id) }

    private fun session() =
        identitySessionService.establishSession(
            VerifiedIdentityClaims(
                issuer = "integration-test",
                subject = "long-decisions-${UUID.randomUUID()}",
                emailSnapshot = "long-decisions-${UUID.randomUUID()}@example.test",
                emailVerified = true,
            ),
            devPrincipal = false,
        )

    private fun account(username: String): ChessAccount =
        chessAccountRepository
            .save(
                ChessAccount(
                    platform = "CHESS_COM",
                    username = "$username-${UUID.randomUUID()}",
                ),
            ).also { accountIds.add(it.id) }

    private fun addOccurrence(
        account: ChessAccount,
        timeControl: String,
        decisionTimeMs: Long?,
        playedAt: String?,
        suffix: String,
        plyNumber: Int = 1,
        platformGameId: String? = null,
        existingGame: Game? = null,
    ): PositionOccurrence {
        val game =
            existingGame ?: gameRepository
                .save(
                    Game(
                        chessAccount = account,
                        platformGameId = platformGameId ?: "$suffix-${UUID.randomUUID()}",
                        pgn = "[Result \"*\"]",
                        timeControl = timeControl.lowercase(),
                        playedAt = playedAt?.let { Instant.parse(it) },
                        whiteUsername = account.username,
                        blackUsername = "opponent-$suffix",
                    ),
                ).also { gameIds.add(it.id) }
        val position =
            positionRepository
                .save(
                    Position(
                        hash = UUID.randomUUID().toString(),
                        fen = "8/8/8/8/8/8/8/8 w - -",
                    ),
                ).also { positionIds.add(it.id) }
        return occurrenceRepository
            .save(
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = account,
                    plyNumber = plyNumber,
                    movePlayed = "Nf3",
                    playerColor = "WHITE",
                    decisionTimeMs = decisionTimeMs,
                ),
            ).also { occurrenceIds.add(it.id) }
    }

    private fun get(
        accountId: UUID,
        timeControl: String,
        thresholdSeconds: Int? = null,
        secret: String? = null,
        page: Int? = null,
        size: Int? = null,
        minDurationSeconds: Int? = null,
        severity: String? = null,
    ): ResponseEntity<String> {
        val query = StringBuilder("/api/positions/long-decisions?accountId=$accountId&timeControl=$timeControl")
        if (minDurationSeconds != null) query.append("&minDurationSeconds=$minDurationSeconds")
        if (thresholdSeconds != null) query.append("&thresholdSeconds=$thresholdSeconds")
        if (severity != null) query.append("&severity=$severity")
        if (page != null) query.append("&page=$page")
        if (size != null) query.append("&size=$size")

        return restTemplate.exchange(
            query.toString(),
            HttpMethod.GET,
            HttpEntity<Void>(
                HttpHeaders().apply {
                    secret?.let { add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=$it") }
                },
            ),
            String::class.java,
        )
    }
}
