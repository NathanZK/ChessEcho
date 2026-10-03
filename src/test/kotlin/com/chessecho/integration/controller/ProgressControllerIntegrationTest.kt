package com.chessecho.integration.controller

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.service.auth.IdentitySessionService
import com.chessecho.service.auth.VerifiedIdentityClaims
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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

    @ParameterizedTest
    @EnumSource(value = PlayerColor::class, names = ["WHITE", "BLACK"])
    fun `authenticated history exposes chronological normalized cumulative win rates`(color: PlayerColor) {
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
        val account =
            chessAccountRepository.save(
                ChessAccount(user = user, platform = "CHESS_COM", username = subject),
            )
        val position =
            positionRepository.save(
                Position(hash = UUID.randomUUID().toString(), fen = "8/8/8/8/8/8/8/8 ${if (color == PlayerColor.WHITE) "w" else "b"} - -"),
            )
        val firstPlayedAt = Instant.parse("2026-09-01T12:00:00Z")
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
        val headers = HttpHeaders().apply { add(HttpHeaders.COOKIE, "CHESSECHO_SESSION=${session.rawSecret}") }

        val response =
            restTemplate.exchange(
                "/api/positions/${position.id}/progress?playerColor=${color.name}",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = objectMapper.readTree(response.body)
        assertEquals(
            setOf("positionId", "playerColor", "points", "mistakeRateChange", "winRateChange", "assessment"),
            body.fieldNames().asSequence().toSet(),
        )
        assertEquals(position.id.toString(), body["positionId"].textValue())
        assertEquals(color.name, body["playerColor"].textValue())
        val points = body["points"]
        assertEquals(3, points.size())
        listOf(100.0, 50.0, 100.0 / 3).forEachIndexed { index, expected ->
            assertEquals(setOf("occurredAt", "mistakeRate", "winRate", "attempts"), points[index].fieldNames().asSequence().toSet())
            assertEquals(index + 1, points[index]["attempts"].intValue())
            assertEquals(firstPlayedAt.plusSeconds(index * 60L), Instant.parse(points[index]["occurredAt"].textValue()))
            assertEquals(expected, points[index]["winRate"].doubleValue(), 0.000001)
            assertEquals(0.0, points[index]["mistakeRate"].doubleValue())
        }
        assertEquals(-200.0 / 3, body["winRateChange"].doubleValue(), 0.000001)
        assertEquals(true, body["mistakeRateChange"].isNull)
        assertEquals("Your performance at this position is stable.", body["assessment"].textValue())
    }

    @Test
    fun `guest progress history requests require authentication`() {
        val positionId = UUID.randomUUID()

        val first =
            restTemplate.getForEntity(
                "/api/positions/$positionId/progress?playerColor=WHITE",
                String::class.java,
            )
        val repeated =
            restTemplate.getForEntity(
                "/api/positions/$positionId/progress?playerColor=WHITE",
                String::class.java,
            )

        assertEquals(HttpStatus.UNAUTHORIZED, first.statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, repeated.statusCode)
    }
}
