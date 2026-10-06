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
        chessAccountRepository.save(
            ChessAccount(
                platform = "CHESS_COM",
                username = "$username-${UUID.randomUUID()}",
            ),
        ).also { accountIds.add(it.id) }

    private fun addOccurrence(
        account: ChessAccount,
        timeControl: String,
        decisionTimeMs: Long?,
        playedAt: String,
        suffix: String,
    ): PositionOccurrence {
        val game =
            gameRepository.save(
                Game(
                    chessAccount = account,
                    platformGameId = "$suffix-${UUID.randomUUID()}",
                    pgn = "[Result \"*\"]",
                    timeControl = timeControl.lowercase(),
                    playedAt = Instant.parse(playedAt),
                    whiteUsername = account.username,
                    blackUsername = "opponent-$suffix",
                ),
            ).also { gameIds.add(it.id) }
        val position =
            positionRepository.save(
                Position(
                    hash = UUID.randomUUID().toString(),
                    fen = "8/8/8/8/8/8/8/8 w - -",
                ),
            ).also { positionIds.add(it.id) }
        return occurrenceRepository.save(
            PositionOccurrence(
                game = game,
                position = position,
                chessAccount = account,
                plyNumber = 1,
                movePlayed = "Nf3",
                playerColor = "WHITE",
                decisionTimeMs = decisionTimeMs,
            ),
        ).also { occurrenceIds.add(it.id) }
    }

    private fun get(
        accountId: UUID,
        timeControl: String,
        thresholdSeconds: Int,
        secret: String? = null,
        page: Int? = null,
        size: Int? = null,
    ) = restTemplate.exchange(
        "/api/positions/long-decisions?accountId=$accountId&timeControl=$timeControl&thresholdSeconds=$thresholdSeconds" +
            page?.let { "&page=$it" }.orEmpty() +
            size?.let { "&size=$it" }.orEmpty(),
        HttpMethod.GET,
        HttpEntity<Void>(
            HttpHeaders().apply {
                secret?.let { add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=$it") }
            },
        ),
        String::class.java,
    )
}
