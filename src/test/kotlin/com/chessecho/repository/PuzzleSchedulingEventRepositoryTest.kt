package com.chessecho.repository

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Game
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles

/**
 * Issue #457, task T4. Both scheduling-event queries must be namespaced by `app_user_id`.
 *
 * `findHistory` is a personal read: one user's training history must never be visible to
 * another user of the same shared Chess.com account. `findExistingClaim` is the import-replay
 * conflict-recovery lookup, so it must match guest rows (`app_user_id IS NULL`) null-safely —
 * a plain equality predicate never matches NULL, which would turn a recoverable guest conflict
 * into a rethrown `DataIntegrityViolationException`.
 */
@DataJpaTest
@ActiveProfiles("test")
class PuzzleSchedulingEventRepositoryTest {
    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    private lateinit var puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository

    private lateinit var alice: AppUser
    private lateinit var bob: AppUser
    private lateinit var account: ChessAccount
    private lateinit var position: Position
    private lateinit var occurrence: PositionOccurrence

    @BeforeEach
    fun setUp() {
        alice = appUserRepository.save(AppUser(email = "alice@example.com"))
        bob = appUserRepository.save(AppUser(email = "bob@example.com"))
        // One shared Chess.com identity that both users read, per Approach B.
        account = chessAccountRepository.save(ChessAccount(platform = "CHESS_COM", username = "shared"))
        position = positionRepository.save(Position(hash = "scoping-hash", fen = "scoping-fen"))
        val game =
            gameRepository.save(
                Game(chessAccount = account, platformGameId = "game-1", pgn = "1. e4 e5"),
            )
        occurrence =
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = account,
                    plyNumber = 1,
                    movePlayed = "e4",
                    playerColor = "WHITE",
                ),
            )
    }

    private fun saveEvent(
        user: AppUser?,
        eventType: SchedulingEventType,
        sourceOccurrence: PositionOccurrence? = null,
    ): PuzzleSchedulingEvent =
        puzzleSchedulingEventRepository.save(
            PuzzleSchedulingEvent(
                appUser = user,
                chessAccount = account,
                position = position,
                playerColor = "WHITE",
                eventType = eventType,
                sourceOccurrence = sourceOccurrence,
            ),
        )

    @Test
    fun `findHistory returns only the requesting user's events for a shared account`() {
        val aliceEvent = saveEvent(alice, SchedulingEventType.SOLVED)
        saveEvent(bob, SchedulingEventType.FAILED)
        saveEvent(null, SchedulingEventType.FAILED)

        val history = puzzleSchedulingEventRepository.findHistory(alice.id, account.id, listOf(position.id), "BOTH")

        assertEquals(listOf(aliceEvent.id), history.map { it.id })
    }

    @Test
    fun `findHistory keeps one user's training state separate across accounts`() {
        val accountY =
            chessAccountRepository.save(
                ChessAccount(platform = "CHESS_COM", username = "shared-y"),
            )
        val eventX = saveEvent(alice, SchedulingEventType.SOLVED)
        val eventY =
            puzzleSchedulingEventRepository.save(
                PuzzleSchedulingEvent(
                    appUser = alice,
                    chessAccount = accountY,
                    position = position,
                    playerColor = "WHITE",
                    eventType = SchedulingEventType.FAILED,
                ),
            )

        val historyX = puzzleSchedulingEventRepository.findHistory(alice.id, account.id, listOf(position.id), "BOTH")
        val historyY = puzzleSchedulingEventRepository.findHistory(alice.id, accountY.id, listOf(position.id), "BOTH")

        assertEquals(listOf(eventX.id), historyX.map { it.id })
        assertEquals(listOf(eventY.id), historyY.map { it.id })
    }

    @Test
    fun `findHistory with a null user returns only guest events`() {
        saveEvent(alice, SchedulingEventType.SOLVED)
        val guestEvent = saveEvent(null, SchedulingEventType.FAILED)

        val history = puzzleSchedulingEventRepository.findHistory(null, account.id, listOf(position.id), "BOTH")

        assertEquals(listOf(guestEvent.id), history.map { it.id })
    }

    @Test
    fun `findExistingClaim does not leak another user's claim for the same occurrence`() {
        val aliceClaim = saveEvent(alice, SchedulingEventType.GAME_MISTAKE, occurrence)

        assertEquals(
            aliceClaim.id,
            puzzleSchedulingEventRepository
                .findExistingClaim(alice.id, occurrence.id, SchedulingEventType.GAME_MISTAKE)
                ?.id,
        )
        assertNull(
            puzzleSchedulingEventRepository.findExistingClaim(bob.id, occurrence.id, SchedulingEventType.GAME_MISTAKE),
        )
    }

    @Test
    fun `findExistingClaim matches a guest claim null-safely`() {
        val guestClaim = saveEvent(null, SchedulingEventType.GAME_MISTAKE, occurrence)
        saveEvent(alice, SchedulingEventType.GAME_MISTAKE, occurrence)

        val recovered =
            puzzleSchedulingEventRepository.findExistingClaim(null, occurrence.id, SchedulingEventType.GAME_MISTAKE)

        assertEquals(guestClaim.id, recovered?.id)
        assertNull(recovered?.appUser)
    }
}
