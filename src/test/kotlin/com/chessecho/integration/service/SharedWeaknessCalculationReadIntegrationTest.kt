package com.chessecho.integration.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.ChessAccount
import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.Game
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.Position
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import com.chessecho.domain.UserPositionStats
import com.chessecho.repository.AppUserRepository
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.GameRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.UserPositionStatsRepository
import com.chessecho.service.StockfishService
import com.chessecho.service.WeaknessCalculationService
import com.chessecho.service.auth.AuthenticatedPrincipal
import com.chessecho.web.UnauthenticatedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFailsWith

/**
 * Issue #457, tasks T6 and T7. These must ship together (plan Section 2.4): opening shared reads to
 * every authenticated principal turns `findHistory` into a live cross-user leakage path unless
 * personal scheduling history is filtered by `app_user_id` in the same change.
 *
 * The two halves of the boundary asserted here are:
 *  - shared account-derived weaknesses are readable by any authenticated principal (Decision 1);
 *  - personal scheduling history is never visible to a principal who did not record it.
 */
@SpringBootTest
@ActiveProfiles("test")
class SharedWeaknessCalculationReadIntegrationTest {
    @Autowired
    private lateinit var weaknessCalculationService: WeaknessCalculationService

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var chessAccountRepository: ChessAccountRepository

    @Autowired
    private lateinit var gameRepository: GameRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var userPositionStatsRepository: UserPositionStatsRepository

    @Autowired
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository

    @Autowired
    private lateinit var engineAnalysisRepository: EngineAnalysisRepository

    @Autowired
    private lateinit var puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository

    @MockBean
    private lateinit var stockfishService: StockfishService

    private lateinit var connector: AppUser
    private lateinit var stranger: AppUser
    private lateinit var account: ChessAccount
    private lateinit var game: Game
    private lateinit var positionA: Position
    private lateinit var positionB: Position

    @BeforeEach
    fun seedSharedAccountData() {
        connector = appUserRepository.save(AppUser(email = "${UUID.randomUUID()}@example.com"))
        stranger = appUserRepository.save(AppUser(email = "${UUID.randomUUID()}@example.com"))
        account =
            chessAccountRepository.save(
                ChessAccount(
                    platform = Platform.CHESS_COM.name,
                    username = "u${UUID.randomUUID().toString().take(8)}",
                ),
            )
        game = gameRepository.save(Game(chessAccount = account, platformGameId = "g-${UUID.randomUUID()}", pgn = "pgn"))
        // Two positions seeded identically, so their baseline priority is equal and any difference
        // in ranking order can only come from per-user scheduling history.
        positionA = seedWeakness("rnbqkbnr/pppp1ppp/8/4p3/3P4/8/PPP1PPPP/RNBQKBNR w KQkq e6 0 2")
        positionB = seedWeakness("rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2")
    }

    @AfterEach
    fun clearSeededData() {
        puzzleSchedulingEventRepository.deleteAll()
        engineAnalysisRepository.deleteAll()
        userPositionStatsRepository.deleteAll()
        positionOccurrenceRepository.deleteAll()
        positionRepository.deleteAll()
        gameRepository.deleteAll()
        chessAccountRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    private fun seedWeakness(fen: String): Position {
        val position = positionRepository.save(Position(hash = "h-${UUID.randomUUID()}", fen = fen))
        userPositionStatsRepository.save(
            UserPositionStats(chessAccount = account, position = position, playerColor = "WHITE", timesReached = 5),
        )
        for (i in 1..5) {
            positionOccurrenceRepository.save(
                PositionOccurrence(
                    game = game,
                    position = position,
                    chessAccount = account,
                    plyNumber = i,
                    movePlayed = if (i <= 3) "Qh5" else "e4",
                    playerColor = "WHITE",
                ),
            )
        }
        val analysis =
            engineAnalysisRepository.save(
                EngineAnalysis(
                    position = position,
                    depth = 16,
                    baselineEvalCp = 50,
                    bestMove = "e4",
                    bestMoveEvalCp = 50,
                    analyzedAt = Instant.now(),
                ),
            )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "e4", evalCp = 45, evalLossFromBest = 0.05),
        )
        analysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = analysis, move = "Qh5", evalCp = -150, evalLossFromBest = 2.0),
        )
        engineAnalysisRepository.save(analysis)
        return position
    }

    private fun weaknessesFor(user: AppUser?) =
        weaknessCalculationService.getWeaknesses(
            platform = Platform.CHESS_COM,
            username = account.username,
            playerColor = PlayerColor.WHITE,
            principal = user?.let { AuthenticatedPrincipal(it.id, devPrincipal = false) },
            accountId = account.id,
        )

    @Test
    fun `a principal who does not connect the account still reads its shared weaknesses`() {
        val forConnector = weaknessesFor(connector)
        val forStranger = weaknessesFor(stranger)

        assertTrue(forConnector.isNotEmpty(), "the connecting principal must see the shared weakness")
        assertEquals(
            forConnector.map { it.positionId }.toSet(),
            forStranger.map { it.positionId }.toSet(),
            "shared account-derived weaknesses must not depend on who is asking",
        )
    }

    @Test
    fun `an unauthenticated caller is still rejected for an accountId read`() {
        assertFailsWith<UnauthenticatedException> { weaknessesFor(null) }
    }

    private fun failRepeatedly(
        user: AppUser,
        position: Position,
        times: Int = 6,
    ) {
        repeat(times) {
            puzzleSchedulingEventRepository.save(
                PuzzleSchedulingEvent(
                    appUser = user,
                    chessAccount = account,
                    position = position,
                    playerColor = "WHITE",
                    eventType = SchedulingEventType.FAILED,
                ),
            )
        }
    }

    @Test
    fun `each principal is ranked by their own scheduling history, not by each other's`() {
        // Both positions are seeded identically, so ranking order is decided purely by the
        // FAILED-event boost. Each principal fails a different position; if personal history leaked
        // across principals, or were keyed off `account.user`, both would be ranked identically.
        failRepeatedly(connector, positionA)
        failRepeatedly(stranger, positionB)

        assertEquals(positionA.id, weaknessesFor(connector).first().positionId)
        assertEquals(positionB.id, weaknessesFor(stranger).first().positionId)
    }

    @Test
    fun `a principal's own weaker history outranks a louder history belonging to someone else`() {
        // The connector fails positionA far more often than the stranger fails positionB. For the
        // stranger, only their own two failures may count, so positionB must still rank first. If
        // history were keyed off `account.user` (the connector), positionA's larger boost would win.
        failRepeatedly(connector, positionA, times = 6)
        failRepeatedly(stranger, positionB, times = 2)

        assertEquals(positionB.id, weaknessesFor(stranger).first().positionId)
        assertEquals(positionA.id, weaknessesFor(connector).first().positionId)
    }
}
