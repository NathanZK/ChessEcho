package com.chessecho.repository

import com.chessecho.domain.AccountConnection
import com.chessecho.domain.AppUser
import com.chessecho.domain.AsyncJob
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Position
import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import com.chessecho.domain.TrainingAttempt
import com.chessecho.domain.TrainingAttemptMode
import com.chessecho.domain.TrainingAttemptOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * Issue #457, task T3. The personally scoped entities must expose an optional `appUser`
 * association mapped to the nullable `app_user_id` column defined by the baseline schema.
 *
 * The database uniqueness invariant itself is asserted by the Flyway-backed
 * `AccountDataBoundaryBaselineSchemaTest`, because H2 cannot represent it.
 */
@DataJpaTest
@ActiveProfiles("test")
class AccountDataBoundaryEntityMappingTest {
    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var connections: AccountConnectionRepository

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Test
    fun `shared account links map user account and connection timestamp`() {
        val first = appUserRepository.save(AppUser(email = "connection-first@example.com"))
        val second = appUserRepository.save(AppUser(email = "connection-second@example.com"))
        val account = chessAccountRepository.save(ChessAccount(platform = "CHESS_COM", username = "shared-mapping"))
        val firstLink =
            connections.saveAndFlush(
                AccountConnection(
                    appUser = first,
                    chessAccount = account,
                    connectedAt = Instant.parse("2026-10-05T13:30:00.123457Z"),
                ),
            )
        connections.saveAndFlush(AccountConnection(appUser = second, chessAccount = account))
        entityManager.clear()

        val reloaded = connections.findById(firstLink.id).orElseThrow()
        assertEquals(first.id, reloaded.appUser.id)
        assertEquals(account.id, reloaded.chessAccount.id)
        assertEquals(firstLink.connectedAt, reloaded.connectedAt)
        assertEquals(2, connections.findAll().size)
    }

    @Test
    fun `a user cannot persist a second active account connection`() {
        val user = appUserRepository.save(AppUser(email = "single-connection@example.com"))
        val first = chessAccountRepository.save(ChessAccount(platform = "CHESS_COM", username = "mapping-first"))
        val second = chessAccountRepository.save(ChessAccount(platform = "CHESS_COM", username = "mapping-second"))
        connections.saveAndFlush(AccountConnection(appUser = user, chessAccount = first))

        assertFailsWith<DataIntegrityViolationException> {
            connections.saveAndFlush(AccountConnection(appUser = user, chessAccount = second))
        }
    }

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository

    @Autowired
    private lateinit var trainingAttemptRepository: TrainingAttemptRepository

    @Autowired
    private lateinit var asyncJobRepository: AsyncJobRepository

    @Test
    fun `scheduling events persist with and without an app user`() {
        val user = appUserRepository.save(AppUser(email = "scheduling@example.com"))
        val account = chessAccountRepository.save(ChessAccount(platform = "CHESS_COM", username = "sched"))
        val position = positionRepository.save(Position(hash = "sched-hash", fen = "sched-fen"))

        val attributed =
            puzzleSchedulingEventRepository.save(
                PuzzleSchedulingEvent(
                    appUser = user,
                    chessAccount = account,
                    position = position,
                    playerColor = "WHITE",
                    eventType = SchedulingEventType.SOLVED,
                ),
            )
        val guest =
            puzzleSchedulingEventRepository.save(
                PuzzleSchedulingEvent(
                    chessAccount = account,
                    position = position,
                    playerColor = "WHITE",
                    eventType = SchedulingEventType.SOLVED,
                ),
            )

        assertEquals(user.id, attributed.appUser?.id)
        assertNull(guest.appUser)
    }

    @Test
    fun `training attempts persist with and without an app user`() {
        val user = appUserRepository.save(AppUser(email = "attempt@example.com"))

        val attributed =
            trainingAttemptRepository.save(
                TrainingAttempt(
                    appUser = user,
                    puzzleId = "puzzle-1",
                    mode = TrainingAttemptMode.STOPWATCH,
                    elapsedMs = 1_000,
                    outcome = TrainingAttemptOutcome.SUBMITTED,
                ),
            )
        val guest =
            trainingAttemptRepository.save(
                TrainingAttempt(
                    puzzleId = "puzzle-2",
                    mode = TrainingAttemptMode.STOPWATCH,
                    elapsedMs = 2_000,
                    outcome = TrainingAttemptOutcome.SUBMITTED,
                ),
            )

        assertEquals(user.id, attributed.appUser?.id)
        assertNull(guest.appUser)
    }

    @Test
    fun `async jobs persist with and without an app user`() {
        val user = appUserRepository.save(AppUser(email = "job@example.com"))

        val attributed =
            asyncJobRepository.save(
                AsyncJob(appUser = user, username = "jobuser", platform = "CHESS_COM"),
            )
        val guest =
            asyncJobRepository.save(
                AsyncJob(username = "guestuser", platform = "CHESS_COM"),
            )

        assertEquals(user.id, attributed.appUser?.id)
        assertNull(guest.appUser)
    }

    @Test
    fun `the initiating app user is part of the immutable async job configuration`() {
        val user = appUserRepository.save(AppUser(email = "immutable@example.com"))
        val other = appUserRepository.save(AppUser(email = "other@example.com"))
        val job =
            asyncJobRepository.saveAndFlush(
                AsyncJob(appUser = user, username = "jobuser", platform = "CHESS_COM"),
            )

        // Progress fields stay mutable, exactly as before.
        job.gamesImported = 3
        asyncJobRepository.saveAndFlush(job)
        assertEquals(3, asyncJobRepository.findById(job.id).orElseThrow().gamesImported)

        // `appUser` is a `val`, so ordinary Kotlin callers cannot reassign it. The snapshot guard
        // defends the field-level mutation paths that remain — Hibernate's own field access, or a
        // future refactor making it `var`. Reflection reproduces exactly that, and the guard must
        // reject it the same way it rejects a changed chessAccount/username/platform.
        val loaded = asyncJobRepository.findById(job.id).orElseThrow()
        val field = AsyncJob::class.java.getDeclaredField("appUser")
        field.isAccessible = true
        field.set(loaded, other)

        val failure =
            assertFailsWith<InvalidDataAccessApiUsageException> {
                asyncJobRepository.saveAndFlush(loaded)
            }
        val causes = generateSequence(failure as Throwable) { it.cause }
        val guard = causes.filterIsInstance<IllegalStateException>().firstOrNull()
        assertNotNull(guard, "the immutability guard must be what rejects the re-attribution")
        assertEquals("Async job configuration is immutable after insertion", guard?.message)
    }
}
