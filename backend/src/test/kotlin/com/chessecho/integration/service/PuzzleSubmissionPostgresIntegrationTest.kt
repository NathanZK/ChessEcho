package com.chessecho.integration.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import com.chessecho.domain.TrainingAttempt
import com.chessecho.domain.TrainingAttemptMode
import com.chessecho.domain.TrainingAttemptOutcome
import com.chessecho.dto.PuzzleAttemptCountResponse
import com.chessecho.dto.PuzzleEventRequest
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.TrainingAttemptRepository
import com.chessecho.service.PuzzleSubmissionConflictException
import com.chessecho.service.PuzzleSubmissionService
import com.chessecho.service.auth.AuthenticatedPrincipal
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals

@SpringBootTest
@Testcontainers
class PuzzleSubmissionPostgresIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var service: PuzzleSubmissionService

    @Autowired private lateinit var users: AppUserRepository

    @Autowired private lateinit var accounts: ChessAccountRepository

    @Autowired private lateinit var positions: PositionRepository

    @Autowired private lateinit var games: GameRepository

    @Autowired private lateinit var occurrences: PositionOccurrenceRepository

    @Autowired private lateinit var events: PuzzleSchedulingEventRepository

    @Autowired private lateinit var timing: TrainingAttemptRepository

    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory

    private fun fixture(): Triple<AppUser, ChessAccount, Position> {
        val user = users.saveAndFlush(AppUser(email = "${UUID.randomUUID()}@example.test"))
        val account = accounts.saveAndFlush(ChessAccount(platform = "CHESS_COM", username = UUID.randomUUID().toString()))
        val position = positions.saveAndFlush(Position(hash = UUID.randomUUID().toString(), fen = "fixture"))
        val game = games.saveAndFlush(Game(chessAccount = account, platformGameId = UUID.randomUUID().toString(), pgn = "1. e4"))
        occurrences.saveAndFlush(
            PositionOccurrence(
                game = game,
                chessAccount = account,
                position = position,
                plyNumber = 1,
                movePlayed = "e4",
                playerColor = "WHITE",
            ),
        )
        return Triple(user, account, position)
    }

    @Test
    fun `legacy outcome counts are two and three excluding foreign contexts and telemetry`() {
        val (user, account, position) = fixture()
        val otherUser = users.saveAndFlush(AppUser(email = "${UUID.randomUUID()}@example.test"))
        val (_, otherAccount, otherPosition) = fixture()

        fun event(
            owner: AppUser?,
            selectedAccount: ChessAccount,
            selectedPosition: Position,
            color: String,
            type: SchedulingEventType,
        ) {
            events.saveAndFlush(
                PuzzleSchedulingEvent(
                    appUser = owner,
                    chessAccount = selectedAccount,
                    position = selectedPosition,
                    playerColor = color,
                    eventType = type,
                ),
            )
        }
        repeat(2) { event(user, account, position, "WHITE", SchedulingEventType.SOLVED) }
        repeat(3) { event(user, account, position, "WHITE", SchedulingEventType.FAILED) }
        event(otherUser, account, position, "WHITE", SchedulingEventType.SOLVED)
        event(null, account, position, "WHITE", SchedulingEventType.FAILED)
        event(user, otherAccount, position, "WHITE", SchedulingEventType.SOLVED)
        event(user, account, otherPosition, "WHITE", SchedulingEventType.SOLVED)
        event(user, account, position, "BLACK", SchedulingEventType.FAILED)
        event(user, account, position, "WHITE", SchedulingEventType.GAME_MISTAKE)
        val source = occurrences.findByChessAccountIdAndPlayerColorAndPositionIdIn(account.id, "WHITE", listOf(position.id)).first()
        events.saveAndFlush(
            PuzzleSchedulingEvent(
                appUser = user,
                chessAccount = account,
                position = position,
                playerColor = "WHITE",
                eventType = SchedulingEventType.SOLVED,
                sourceOccurrence = source,
            ),
        )
        listOf(TrainingAttemptOutcome.SUBMITTED, TrainingAttemptOutcome.EXPIRED).forEach { outcome ->
            timing.saveAndFlush(
                TrainingAttempt(
                    puzzleId = position.id.toString(),
                    mode = TrainingAttemptMode.COUNTDOWN,
                    elapsedMs = 30000,
                    allowedMs = 30000,
                    outcome = outcome,
                    chessAccount = account,
                    appUser = user,
                ),
            )
        }
        val principal = AuthenticatedPrincipal(user.id, devPrincipal = false)
        repeat(2) { assertEquals(PuzzleAttemptCountResponse(2, 3), service.count(account.id, position.id, "WHITE", principal)) }
        assertEquals(
            PuzzleAttemptCountResponse(1, 0),
            service.count(account.id, position.id, "WHITE", AuthenticatedPrincipal(otherUser.id, false)),
        )
    }

    @Test
    fun `replay is one answer and fresh identities increment regardless of outcome`() {
        val (user, account, position) = fixture()
        val principal = AuthenticatedPrincipal(user.id, false)
        val request = PuzzleEventRequest(position.id, "WHITE", SchedulingEventType.FAILED, account.id, UUID.randomUUID(), "d4")
        service.record(request, principal)
        service.record(request, principal)
        assertEquals(PuzzleAttemptCountResponse(0, 1), service.count(account.id, position.id, "WHITE", principal))
        val solved = request.copy(submissionId = UUID.randomUUID(), eventType = SchedulingEventType.SOLVED, submittedMove = "e4")
        service.record(solved, principal)
        service.record(solved, principal)
        assertEquals(PuzzleAttemptCountResponse(1, 1), service.count(account.id, position.id, "WHITE", principal))
        assertThrows<PuzzleSubmissionConflictException> { service.record(request.copy(submittedMove = "Nf3"), principal) }
        assertThrows<PuzzleSubmissionConflictException> { service.record(request.copy(eventType = SchedulingEventType.SOLVED), principal) }
        assertEquals(PuzzleAttemptCountResponse(1, 1), service.count(account.id, position.id, "WHITE", principal))
    }

    @ParameterizedTest
    @EnumSource(value = SchedulingEventType::class, names = ["SOLVED", "FAILED"])
    fun `concurrent identical delivery inserts exactly one submission`(outcome: SchedulingEventType) {
        val (user, account, position) = fixture()
        val principal = AuthenticatedPrincipal(user.id, false)
        val request = PuzzleEventRequest(position.id, "WHITE", outcome, account.id, UUID.randomUUID(), "e4")
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll(List(8) { Callable { service.record(request, principal) } })
            results.forEach { assertEquals(request.submissionId, it.get().submissionId) }
        } finally {
            executor.shutdownNow()
        }

        val expected = if (outcome == SchedulingEventType.SOLVED) PuzzleAttemptCountResponse(1, 0) else PuzzleAttemptCountResponse(0, 1)
        assertEquals(expected, service.count(account.id, position.id, "WHITE", principal))
    }

    @Test
    fun `identical fresh answers are separate and account outcome counts remain isolated`() {
        val (user, account, position) = fixture()
        val (_, secondAccount, secondPosition) = fixture()
        val principal = AuthenticatedPrincipal(user.id, false)
        val request = PuzzleEventRequest(position.id, "WHITE", SchedulingEventType.SOLVED, account.id, UUID.randomUUID(), "e4")
        assertEquals(PuzzleAttemptCountResponse(0, 0), service.count(account.id, position.id, "WHITE", principal))
        service.record(request, principal)
        service.record(request.copy(submissionId = UUID.randomUUID()), principal)
        service.record(
            request.copy(submissionId = UUID.randomUUID(), accountId = secondAccount.id, positionId = secondPosition.id),
            principal,
        )
        assertEquals(PuzzleAttemptCountResponse(2, 0), service.count(account.id, position.id, "WHITE", principal))
        assertEquals(PuzzleAttemptCountResponse(1, 0), service.count(secondAccount.id, secondPosition.id, "WHITE", principal))
    }

    @Test
    fun `one grouped aggregate supplies both outcomes during concurrent identified writes`() {
        val (user, account, position) = fixture()
        val principal = AuthenticatedPrincipal(user.id, false)
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.clear()
        service.count(account.id, position.id, "WHITE", principal)
        val aggregate = statistics.queries.single { it.contains("GROUP BY", ignoreCase = true) }
        assertEquals(1, statistics.getQueryStatistics(aggregate).executionCount)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val deliveries =
                (0 until 8).map { index ->
                    executor.submit(
                        Callable {
                            service.record(
                                PuzzleEventRequest(
                                    position.id,
                                    "WHITE",
                                    if (index % 2 == 0) SchedulingEventType.SOLVED else SchedulingEventType.FAILED,
                                    account.id,
                                    UUID.randomUUID(),
                                    "e4",
                                ),
                                principal,
                            )
                        },
                    )
                }
            repeat(8) {
                val counts = service.count(account.id, position.id, "WHITE", principal)
                kotlin.test.assertTrue(counts.solvedCount in 0..4 && counts.failedCount in 0..4)
            }
            deliveries.forEach { it.get() }
            assertEquals(PuzzleAttemptCountResponse(4, 4), service.count(account.id, position.id, "WHITE", principal))
            assertEquals(10, statistics.getQueryStatistics(aggregate).executionCount)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `database rejects malformed identified rows`() {
        val (user, account, position) = fixture()
        val source = occurrences.findByChessAccountIdAndPlayerColorAndPositionIdIn(account.id, "WHITE", listOf(position.id)).first().id
        val invalid =
            listOf(
                listOf(user.id, "SOLVED", null, UUID.randomUUID(), null),
                listOf(user.id, "SOLVED", null, null, "e4"),
                listOf(user.id, "SOLVED", null, UUID.randomUUID(), " "),
                listOf(null, "SOLVED", null, UUID.randomUUID(), "e4"),
                listOf(user.id, "GAME_MISTAKE", null, UUID.randomUUID(), "e4"),
                listOf(user.id, "SOLVED", source, UUID.randomUUID(), "e4"),
            )
        invalid.forEach { values ->
            assertThrows<DataIntegrityViolationException> {
                jdbc.update(
                    """
                    INSERT INTO puzzle_scheduling_event
                        (id, app_user_id, chess_account_id, position_id, player_color, event_type,
                         position_occurrence_id, submission_id, submitted_move)
                    VALUES (?, ?, ?, ?, 'WHITE', ?, ?, ?, ?)
                    """.trimIndent(),
                    UUID.randomUUID(), values[0], account.id, position.id, values[1], values[2], values[3], values[4],
                )
            }
        }
    }

    @Test
    fun `invalid answer and missing occurrence never add history`() {
        val (user, account, position) = fixture()
        val principal = AuthenticatedPrincipal(user.id, false)
        val valid = PuzzleEventRequest(position.id, "WHITE", SchedulingEventType.SOLVED, account.id, UUID.randomUUID(), "e4")
        listOf(
            valid.copy(submittedMove = " "),
            valid.copy(submittedMove = " e4"),
            valid.copy(eventType = SchedulingEventType.GAME_MISTAKE),
            valid.copy(playerColor = "BOTH"),
        ).forEach { request -> assertThrows<IllegalArgumentException> { service.record(request, principal) } }
        assertThrows<NoSuchElementException> { service.record(valid.copy(positionId = UUID.randomUUID()), principal) }
        assertEquals(PuzzleAttemptCountResponse(0, 0), service.count(account.id, position.id, "WHITE", principal))
    }

    companion object {
        @Container @JvmField
        val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { true }
            registry.add("spring.jpa.properties.hibernate.generate_statistics") { true }
        }
    }
}
