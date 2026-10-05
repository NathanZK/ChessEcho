package com.chessecho.service

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
import com.chessecho.dto.AccountAssociationRequest
import com.chessecho.dto.ImportGamesRequest
import com.chessecho.repository.AccountConnectionRepository
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.TrainingAttemptRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.web.UnauthenticatedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertFailsWith

/**
 * Issue #457, task T5. Shared account-derived data is owned by the `ChessAccount`, not by
 * whichever user currently points at it, so resolving an account for a *shared* read must check
 * only that the row exists. Active connections gate import/ownership flows.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountOwnershipServiceTest {
    @Autowired
    private lateinit var ownership: AccountOwnershipService

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var connections: AccountConnectionRepository

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository

    @Autowired
    private lateinit var trainingAttemptRepository: TrainingAttemptRepository

    private var disconnectTestUserId: UUID? = null
    private var disconnectTestAccountId: UUID? = null
    private var disconnectTestPositionId: UUID? = null
    private var disconnectTestEventId: UUID? = null
    private var disconnectTestAttemptId: UUID? = null

    private fun user(): AppUser = appUserRepository.save(AppUser(email = "${UUID.randomUUID()}@example.com"))

    private fun account(owner: AppUser?): ChessAccount =
        chessAccountRepository.save(
            ChessAccount(platform = "CHESS_COM", username = "u${UUID.randomUUID().toString().take(8)}"),
        ).also { account ->
            if (owner != null) connections.saveAndFlush(AccountConnection(appUser = owner, chessAccount = account))
        }

    private fun principalFor(user: AppUser) = AuthenticatedPrincipal(user.id, devPrincipal = false)

    @AfterEach
    fun cleanDisconnectFixture() {
        disconnectTestEventId?.let(puzzleSchedulingEventRepository::deleteById)
        disconnectTestAttemptId?.let(trainingAttemptRepository::deleteById)
        disconnectTestPositionId?.let(positionRepository::deleteById)
        disconnectTestAccountId?.let(chessAccountRepository::deleteById)
        disconnectTestUserId?.let(appUserRepository::deleteById)
        disconnectTestUserId = null
        disconnectTestAccountId = null
        disconnectTestPositionId = null
        disconnectTestEventId = null
        disconnectTestAttemptId = null
    }

    @Test
    fun `resolveSharedAccount returns an account the principal does not own`() {
        val owner = user()
        val stranger = user()
        val account = account(owner)

        val resolved = ownership.resolveSharedAccount(account.id, principalFor(stranger))

        assertEquals(account.id, resolved.id)
    }

    @Test
    fun `resolveSharedAccount returns an account the principal does own`() {
        val owner = user()
        val account = account(owner)

        val resolved = ownership.resolveSharedAccount(account.id, principalFor(owner))

        assertEquals(account.id, resolved.id)
    }

    @Test
    fun `resolveSharedAccount returns an unclaimed account`() {
        val account = account(null)

        val resolved = ownership.resolveSharedAccount(account.id, principalFor(user()))

        assertEquals(account.id, resolved.id)
    }

    @Test
    fun `resolveSharedAccount still rejects an unauthenticated caller`() {
        val account = account(user())

        assertFailsWith<UnauthenticatedException> {
            ownership.resolveSharedAccount(account.id, null)
        }
    }

    @Test
    fun `resolveSharedAccount rejects an unknown account`() {
        assertFailsWith<AccountNotFoundException> {
            ownership.resolveSharedAccount(UUID.randomUUID(), principalFor(user()))
        }
    }

    @Test
    fun `authorizeJob allows the initiating user after the account is reconnected`() {
        val initiator = user()
        val newOwner = user()
        val account = account(initiator)
        ownership.disconnect(account.id, principalFor(initiator))
        ownership.associate(principalFor(newOwner), AccountAssociationRequest("CHESS_COM", account.username))
        val job = AsyncJob(chessAccount = account, appUser = initiator, username = account.username, platform = account.platform)

        ownership.authorizeJob(job, principalFor(initiator))
    }

    @Test
    fun `authorizeJob denies the current owner when they did not initiate the job`() {
        val initiator = user()
        val currentOwner = user()
        val account = account(currentOwner)
        val job = AsyncJob(chessAccount = account, appUser = initiator, username = account.username, platform = account.platform)

        assertFailsWith<ForbiddenAccountException> {
            ownership.authorizeJob(job, principalFor(currentOwner))
        }
    }

    @Test
    fun `authorizeJob denies authenticated readers of guest jobs`() {
        val job = AsyncJob(chessAccount = account(null), username = "guest", platform = "CHESS_COM")

        assertFailsWith<ForbiddenAccountException> {
            ownership.authorizeJob(job, principalFor(user()))
        }
    }

    @Test
    fun `authorizeJob retains guest visibility for an unclaimed account`() {
        val job = AsyncJob(chessAccount = account(null), username = "guest", platform = "CHESS_COM")

        ownership.authorizeJob(job, null)
    }

    @Test
    fun `authorizeJob retains guest visibility after the account is connected`() {
        val job = AsyncJob(chessAccount = account(user()), username = "guest", platform = "CHESS_COM")

        ownership.authorizeJob(job, null)
    }

    @Test
    fun `guest import and username reads ignore connection state`() {
        val connectedAccount = account(user())

        val resolvedImport =
            ownership.resolveImportAccount(
                ImportGamesRequest(platform = com.chessecho.domain.Platform.CHESS_COM, username = connectedAccount.username),
                null,
            )
        val resolvedRead =
            ownership.resolvePrivateRead(
                com.chessecho.domain.Platform.CHESS_COM,
                connectedAccount.username,
                null,
            )

        assertEquals(connectedAccount.id, resolvedImport.id)
        assertEquals(connectedAccount.id, resolvedRead.id)
    }

    @Test
    fun `authenticated import rejects a known but unconnected account`() {
        val connectedByAnotherUser = account(user())

        assertFailsWith<ForbiddenAccountException> {
            ownership.resolveImportAccount(
                ImportGamesRequest(accountId = connectedByAnotherUser.id),
                principalFor(user()),
            )
        }
    }

    @Test
    fun `the ownership-gated resolvers are left intact for claim flows`() {
        val account = account(user())
        val stranger = principalFor(user())

        assertFailsWith<ForbiddenAccountException> { ownership.resolvePrivateRead(account.id, stranger) }
        assertFailsWith<ForbiddenAccountException> { ownership.requireOwnedAccount(account.id, stranger) }
    }

    @Test
    fun `disconnect clears only the owner's connection and preserves account and personal rows`() {
        val owner = user()
        disconnectTestUserId = owner.id
        val account = account(owner)
        disconnectTestAccountId = account.id
        val position = positionRepository.save(Position(hash = UUID.randomUUID().toString(), fen = "fen"))
        disconnectTestPositionId = position.id
        val event =
            puzzleSchedulingEventRepository.save(
                PuzzleSchedulingEvent(
                    appUser = owner,
                    chessAccount = account,
                    position = position,
                    playerColor = "WHITE",
                    eventType = SchedulingEventType.SOLVED,
                ),
            )
        disconnectTestEventId = event.id
        val attempt =
            trainingAttemptRepository.save(
                TrainingAttempt(
                    puzzleId = "puzzle-${UUID.randomUUID()}",
                    mode = TrainingAttemptMode.STOPWATCH,
                    elapsedMs = 1_000,
                    outcome = TrainingAttemptOutcome.SUBMITTED,
                    chessAccount = account,
                    appUser = owner,
                ),
            )
        disconnectTestAttemptId = attempt.id

        ownership.disconnect(account.id, principalFor(owner))

        assertEquals(0, ownership.listOwnedAccounts(principalFor(owner)).size)
        assertEquals(account.id, chessAccountRepository.findById(account.id).orElseThrow().id)
        assertEquals(event.id, puzzleSchedulingEventRepository.findById(event.id).orElseThrow().id)
        assertEquals(attempt.id, trainingAttemptRepository.findById(attempt.id).orElseThrow().id)
        assertEquals(
            listOf(event.id),
            puzzleSchedulingEventRepository.findHistory(owner.id, account.id, listOf(position.id), "BOTH")
                .map { it.id },
        )

        ownership.associate(principalFor(owner), AccountAssociationRequest("CHESS_COM", account.username))

        assertEquals(
            listOf(event.id),
            puzzleSchedulingEventRepository.findHistory(owner.id, account.id, listOf(position.id), "BOTH")
                .map { it.id },
            "the user's existing personal history must remain available after reconnecting",
        )
    }

    @Test
    fun `disconnect returns not found for a missing caller connection`() {
        val owner = user()
        val account = account(owner)
        val stranger = principalFor(user())

        assertFailsWith<AccountNotFoundException> {
            ownership.disconnect(account.id, stranger)
        }
        assertEquals(account.id, ownership.listOwnedAccounts(principalFor(owner)).single().id)
    }

    @Test
    fun `disconnect removes only the caller link and repeated disconnect returns not found`() {
        val first = user()
        val second = user()
        val account = account(first)
        ownership.associate(principalFor(second), AccountAssociationRequest("CHESS_COM", account.username))

        ownership.disconnect(account.id, principalFor(first))

        assertEquals(0, ownership.listOwnedAccounts(principalFor(first)).size)
        assertEquals(account.id, ownership.listOwnedAccounts(principalFor(second)).single().id)
        assertFailsWith<AccountNotFoundException> { ownership.disconnect(account.id, principalFor(first)) }
    }
}
