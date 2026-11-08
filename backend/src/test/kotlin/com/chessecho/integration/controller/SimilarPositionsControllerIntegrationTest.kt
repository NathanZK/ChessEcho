package com.chessecho.integration.controller

import com.chessecho.domain.AccountConnection
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.dto.SimilarPositionsResponse
import com.chessecho.repository.AccountConnectionRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.service.BatchContinuationResult
import com.chessecho.service.ContinuationResult
import com.chessecho.service.PrincipalVariation
import com.chessecho.service.SearchStatus
import com.chessecho.service.StockfishService
import com.chessecho.service.auth.IdentitySessionService
import com.chessecho.service.auth.VerifiedIdentityClaims
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.util.UriComponentsBuilder
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SimilarPositionsControllerIntegrationTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var identitySessionService: IdentitySessionService

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var accountConnectionRepository: AccountConnectionRepository

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var occurrenceRepository: PositionOccurrenceRepository

    @MockBean
    private lateinit var stockfishService: StockfishService

    private val accountIds = mutableListOf<UUID>()
    private val connectionIds = mutableListOf<UUID>()
    private val gameIds = mutableListOf<UUID>()
    private val positionIds = mutableListOf<UUID>()
    private val occurrenceIds = mutableListOf<UUID>()

    @BeforeEach
    fun setUpStockfish() {
        val batchAnswer: (org.mockito.invocation.InvocationOnMock) -> BatchContinuationResult = { invocation ->
            val candidates = invocation.getArgument<List<Pair<UUID, String>>>(1)
            val queryPv = PrincipalVariation(rank = 1, moves = listOf("g1f3"), achievedDepth = 8)
            val queryResult = ContinuationResult(SearchStatus.COMPLETED, 8, listOf(queryPv))
            val candMap =
                candidates.associate { (id, _) ->
                    id to
                        ContinuationResult(
                            SearchStatus.COMPLETED,
                            8,
                            listOf(PrincipalVariation(rank = 1, moves = listOf("b1c3"), achievedDepth = 8)),
                        )
                }
            BatchContinuationResult(
                queryResult = queryResult,
                candidateResults = candMap,
                fallbackReason = null,
                shortlistedCount = candidates.size,
                engineAttemptedCount = candidates.size,
                engineCompletedCount = candidates.size,
            )
        }
        whenever(stockfishService.batchResolveContinuations(any(), any(), any())).thenAnswer(batchAnswer)
        whenever(stockfishService.batchResolveContinuations(any(), any(), any(), anyOrNull())).thenAnswer(batchAnswer)
    }

    @AfterEach
    fun tearDown() {
        occurrenceRepository.deleteAllById(occurrenceIds)
        positionRepository.deleteAllById(positionIds)
        gameRepository.deleteAllById(gameIds)
        accountConnectionRepository.deleteAllById(connectionIds)
        chessAccountRepository.deleteAllById(accountIds)
    }

    @Test
    fun `authenticated user receives 200 with ranked results and verified chessComUrl`() {
        val userSession = createSession("owner-${UUID.randomUUID()}")
        val account = createAccount("owner-user-${UUID.randomUUID()}")
        connectAccount(userSession.principal.appUserId, account)

        val game = createGame(account, "https://www.chess.com/game/live/123456789")
        val posFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val position = createPosition(posFen)
        createOccurrence(game, position, 4, "Nf3")

        val queryFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3"
        val response = get(buildUrl(queryFen, accountId = account.id), userSession.rawSecret)
        assertEquals(HttpStatus.OK, response.statusCode)

        val body = objectMapper.readValue(response.body, SimilarPositionsResponse::class.java)
        assertEquals(queryFen, body.queryFen)
        assertTrue(body.candidatesEvaluated >= 1)
        assertEquals(1, body.results.size)

        val match = body.results[0]
        assertEquals(1, match.rank)
        assertTrue(match.score > 0.0)
        assertEquals(position.id, match.positionId)
        assertEquals(1, match.occurrences.size)
        assertEquals("https://www.chess.com/game/live/123456789?move=4", match.occurrences[0].chessComUrl)
    }

    @Test
    fun `guest receives 200 with ranked results using valid username`() {
        val account = createAccount("guest-user-${UUID.randomUUID()}")
        val game = createGame(account, "unverified-id-999")
        val posFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val position = createPosition(posFen)
        createOccurrence(game, position, 2, "e4")

        val queryFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3"
        val response = get(buildUrl(queryFen, username = account.username), null)
        assertEquals(HttpStatus.OK, response.statusCode)

        val body = objectMapper.readValue(response.body, SimilarPositionsResponse::class.java)
        assertEquals(1, body.results.size)
        // Since platformGameId "unverified-id-999" is not an absolute URL and timeControl is standard/unresolved daily/live, chessComUrl is null
        assertNull(body.results[0].occurrences[0].chessComUrl)
    }

    @Test
    fun `returns 400 for invalid FEN`() {
        val account = createAccount("fen-test-${UUID.randomUUID()}")
        val response = get(buildUrl("invalid fen position string", username = account.username), null)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `returns 400 for negative minScore`() {
        val account = createAccount("minscore-test-${UUID.randomUUID()}")
        val validFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val response = get(buildUrl(validFen, username = account.username, minScore = -0.5), null)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `returns 400 when neither accountId nor username is provided`() {
        val validFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val response = get(buildUrl(validFen), null)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `returns 400 when authenticated user supplies username preventing spoofing`() {
        val userSession = createSession("owner-${UUID.randomUUID()}")
        val account = createAccount("owner-user-${UUID.randomUUID()}")
        connectAccount(userSession.principal.appUserId, account)

        val validFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val response = get(buildUrl(validFen, username = account.username), userSession.rawSecret)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `returns 401 when unauthenticated guest supplies accountId`() {
        val account = createAccount("guest-aid-test-${UUID.randomUUID()}")
        val validFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val response = get(buildUrl(validFen, accountId = account.id), null)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `returns 403 when authenticated user supplies another users accountId`() {
        val userSession = createSession("owner-${UUID.randomUUID()}")
        val otherSession = createSession("other-${UUID.randomUUID()}")
        val otherAccount = createAccount("other-user-${UUID.randomUUID()}")
        connectAccount(otherSession.principal.appUserId, otherAccount)

        val validFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val response = get(buildUrl(validFen, accountId = otherAccount.id), userSession.rawSecret)
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `returns 404 when accountId does not exist`() {
        val userSession = createSession("owner-${UUID.randomUUID()}")
        val validFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val response = get(buildUrl(validFen, accountId = UUID.randomUUID()), userSession.rawSecret)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `returns 404 when guest username does not exist`() {
        val validFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val response = get(buildUrl(validFen, username = "non-existent-user-${UUID.randomUUID()}"), null)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `coerces limit into range 1 to 100`() {
        val account = createAccount("limit-user-${UUID.randomUUID()}")
        val validFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val response = get(buildUrl(validFen, username = account.username, limit = 500), null)
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `respects limit parameter and preserves ranking invariance across different limits`() {
        val userSession = createSession("owner-${UUID.randomUUID()}")
        val account = createAccount("owner-user-${UUID.randomUUID()}")
        connectAccount(userSession.principal.appUserId, account)

        val game = createGame(account, "https://www.chess.com/game/live/111222333")
        val posFen1 = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val posFen2 = "r1bqkbnr/pppp1ppp/2n5/4p3/3P4/5N2/PPP1PPPP/RNBQKB1R w KQkq - 2 3"
        val posFen3 = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"

        val pos1 = createPosition(posFen1)
        val pos2 = createPosition(posFen2)
        val pos3 = createPosition(posFen3)

        createOccurrence(game, pos1, 4, "Nf3")
        createOccurrence(game, pos2, 4, "d4")
        createOccurrence(game, pos3, 6, "Bc4")

        val queryFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3"

        // Request with limit = 1
        val resLimit1 = get(buildUrl(queryFen, accountId = account.id, limit = 1), userSession.rawSecret)
        assertEquals(HttpStatus.OK, resLimit1.statusCode)
        val body1 = objectMapper.readValue(resLimit1.body, SimilarPositionsResponse::class.java)
        assertEquals(1, body1.results.size)

        // Request with limit = 2
        val resLimit2 = get(buildUrl(queryFen, accountId = account.id, limit = 2), userSession.rawSecret)
        assertEquals(HttpStatus.OK, resLimit2.statusCode)
        val body2 = objectMapper.readValue(resLimit2.body, SimilarPositionsResponse::class.java)
        assertTrue(body2.results.size >= 2)

        // Verify mathematical ranking and scoring invariance between limit=1 and limit=2
        assertEquals(body1.results[0].positionId, body2.results[0].positionId)
        assertEquals(body1.results[0].score, body2.results[0].score)
        assertEquals(body1.results[0].canonicalFen, body2.results[0].canonicalFen)
    }

    @Test
    fun `controller excludes candidate positions where user played opposite color`() {
        val userSession = createSession("owner-${UUID.randomUUID()}")
        val account = createAccount("owner-user-${UUID.randomUUID()}")
        connectAccount(userSession.principal.appUserId, account)

        val game = createGame(account, "https://www.chess.com/game/live/999888777")
        val posFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val position = createPosition(posFen)
        createOccurrence(game, position, 4, "Nf3", playerColor = "BLACK")

        val queryFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3"
        val response = get(buildUrl(queryFen, accountId = account.id), userSession.rawSecret)
        assertEquals(HttpStatus.OK, response.statusCode)

        val body = objectMapper.readValue(response.body, SimilarPositionsResponse::class.java)
        assertTrue(body.results.isEmpty(), "Candidates where user was opposite color must be excluded")
    }

    @Test
    fun `authenticated user receives 200 with structural results without invoking engine`() {
        val userSession = createSession("owner-${UUID.randomUUID()}")
        val account = createAccount("owner-user-${UUID.randomUUID()}")
        connectAccount(userSession.principal.appUserId, account)

        val game = createGame(account, "https://www.chess.com/game/live/123456789")
        val posFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val position = createPosition(posFen)
        createOccurrence(game, position, 4, "Nf3")

        val queryFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3"
        val response = get(buildUrl(queryFen, accountId = account.id), userSession.rawSecret)
        assertEquals(HttpStatus.OK, response.statusCode)

        val body = objectMapper.readValue(response.body, SimilarPositionsResponse::class.java)
        assertEquals(queryFen, body.queryFen)
        assertEquals("STRUCTURAL", body.mode)
        assertEquals(false, body.dynamicSimilarityApplied)
        assertEquals(null, body.fallbackReason)
        assertEquals(1, body.results.size)
        assertEquals(1, body.results[0].rank)
        assertEquals(0.0, body.results[0].dynamicScore)
        assertTrue(body.results[0].score > 0.0)
    }

    @Test
    fun `authenticated user receives clean failure in dynamic mode when engine fails`() {
        whenever(stockfishService.analyzeMultiPv(any(), any(), any())).thenThrow(
            RuntimeException("Stockfish process crashed"),
        )

        val userSession = createSession("owner-${UUID.randomUUID()}")
        val account = createAccount("owner-user-${UUID.randomUUID()}")
        connectAccount(userSession.principal.appUserId, account)

        val game = createGame(account, "https://www.chess.com/game/live/123456789")
        val posFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val position = createPosition(posFen)
        createOccurrence(game, position, 4, "Nf3")

        val queryFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/2N5/PPPP1PPP/R1BQKBNR w KQkq - 2 3"
        val response = get(buildUrl(queryFen, accountId = account.id, mode = "DYNAMIC"), userSession.rawSecret)
        assertEquals(HttpStatus.OK, response.statusCode)

        val body = objectMapper.readValue(response.body, SimilarPositionsResponse::class.java)
        assertEquals(queryFen, body.queryFen)
        assertEquals("DYNAMIC", body.mode)
        assertEquals(true, body.dynamicSimilarityApplied)
        assertEquals("ENGINE_FAILED", body.fallbackReason)
        assertTrue(body.results.isEmpty())
    }

    private fun buildUrl(
        fen: String,
        accountId: UUID? = null,
        username: String? = null,
        minScore: Double? = null,
        limit: Int? = null,
        mode: String? = null,
    ): java.net.URI {
        val builder =
            UriComponentsBuilder
                .fromPath("/api/positions/similar")
                .queryParam("fen", fen)
        if (accountId != null) builder.queryParam("accountId", accountId)
        if (username != null) builder.queryParam("username", username)
        if (minScore != null) builder.queryParam("minScore", minScore)
        if (limit != null) builder.queryParam("limit", limit)
        if (mode != null) builder.queryParam("mode", mode)
        return builder.build().toUri()
    }

    private fun get(
        uri: java.net.URI,
        secret: String?,
    ): ResponseEntity<String> {
        val headers = HttpHeaders()
        if (secret != null) {
            headers.add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=$secret")
        }
        return restTemplate.exchange(uri, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    private fun createSession(subject: String) =
        identitySessionService.establishSession(
            VerifiedIdentityClaims(
                issuer = "integration-test",
                subject = subject,
                emailSnapshot = "$subject@example.test",
                emailVerified = true,
            ),
            devPrincipal = false,
        )

    private fun createAccount(username: String): ChessAccount {
        val account =
            chessAccountRepository.save(
                ChessAccount(
                    platform = "CHESS_COM",
                    username = username,
                ),
            )
        accountIds.add(account.id)
        return account
    }

    private fun connectAccount(
        appUserId: UUID,
        account: ChessAccount,
    ) {
        val connection =
            accountConnectionRepository.save(
                AccountConnection(
                    appUser = com.chessecho.domain.AppUser(id = appUserId, email = "test@example.com"),
                    chessAccount = account,
                ),
            )
        connectionIds.add(connection.id)
    }

    private fun createGame(
        account: ChessAccount,
        platformGameId: String,
    ): Game {
        val game =
            gameRepository.save(
                Game(
                    chessAccount = account,
                    platformGameId = platformGameId,
                    timeControl = "600",
                    pgn = "1. e4 e5",
                    result = "win",
                ),
            )
        gameIds.add(game.id)
        return game
    }

    private fun createPosition(fen: String): Position {
        val position =
            positionRepository.save(
                Position(
                    hash = UUID.randomUUID().toString(),
                    fen = fen,
                ),
            )
        positionIds.add(position.id)
        return position
    }

    private fun createOccurrence(
        game: Game,
        position: Position,
        ply: Int,
        playedMove: String,
        playerColor: String = "WHITE",
    ): PositionOccurrence {
        val occurrence =
            occurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = game.chessAccount,
                    plyNumber = ply,
                    movePlayed = playedMove,
                    playerColor = playerColor,
                ),
            )
        occurrenceIds.add(occurrence.id)
        return occurrence
    }
}
