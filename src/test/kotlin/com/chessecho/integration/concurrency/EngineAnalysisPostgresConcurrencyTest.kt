package com.chessecho.integration.concurrency

import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionRepository
import com.chessecho.service.EngineAnalysisService
import com.chessecho.service.EngineCandidate
import com.chessecho.service.EvalScore
import com.chessecho.service.PositionAnalysis
import com.chessecho.service.StockfishService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@Testcontainers
class EngineAnalysisPostgresConcurrencyTest {
    @Autowired
    private lateinit var engineAnalysisService: EngineAnalysisService

    @Autowired
    private lateinit var engineAnalysisRepository: EngineAnalysisRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @MockBean
    private lateinit var stockfishService: StockfishService

    @BeforeEach
    fun setUp() {
        jdbcTemplate.execute("DELETE FROM engine_move_evaluation")
        jdbcTemplate.execute("DELETE FROM engine_analysis")
        jdbcTemplate.execute("DELETE FROM position_occurrence")
        jdbcTemplate.execute("DELETE FROM game")
        jdbcTemplate.execute("DELETE FROM chess_account")
        jdbcTemplate.execute("DELETE FROM position")
    }

    @AfterEach
    fun tearDown() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS reject_engine_analysis_child_trigger ON engine_move_evaluation")
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_engine_analysis_child()")
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS suppress_engine_analysis_child_trigger ON engine_move_evaluation")
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS suppress_engine_analysis_child()")
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS reject_engine_analysis_parent_trigger ON engine_analysis")
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_engine_analysis_parent()")
        jdbcTemplate.execute("DELETE FROM engine_move_evaluation")
        jdbcTemplate.execute("DELETE FROM engine_analysis")
        jdbcTemplate.execute("DELETE FROM position_occurrence")
        jdbcTemplate.execute("DELETE FROM game")
        jdbcTemplate.execute("DELETE FROM chess_account")
        jdbcTemplate.execute("DELETE FROM position")
    }

    @Test
    fun `same new position converges on one complete authoritative analysis`() {
        val position = seedPosition("new-race")
        seedOccurrences(position, "e4", "d4")
        val overlap = overlapFor(2)
        whenever(stockfishService.analyzeMultiPv(eq(position.fen), eq(16), any()))
            .thenAnswer {
                overlap.arrive()
                listOf(
                    EngineCandidate("e4", EvalScore(cp = 40, mate = null)),
                    EngineCandidate("d4", EvalScore(cp = 20, mate = null)),
                )
            }

        val outcomes = runConcurrently(position, 2)

        assertTrue(outcomes.all { it.isSuccess }, "both callers must recover: $outcomes")
        assertEquals(1, engineAnalysisRepository.count())
        val persisted = engineAnalysisRepository.findByPositionIdWithMoveEvaluations(position.id)
        assertNotNull(persisted)
        assertEquals("e4", persisted.bestMove)
        assertEquals(40, persisted.bestMoveEvalCp)
        assertEquals(setOf("e4", "d4"), persisted.moveEvaluations.map { it.move }.toSet())
        assertEquals(2, persisted.moveEvaluations.size)
    }

    @Test
    fun `committed parent visible before children lets competing worker complete required state`() {
        val position = seedPosition("partial-parent")
        seedOccurrences(position, "e4", "d4")
        val analysisId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO engine_analysis
                (id, position_id, depth, baseline_eval_cp, best_move, best_move_eval_cp)
            VALUES (?, ?, 16, 70, 'e4', 70)
            """.trimIndent(),
            analysisId,
            position.id,
        )
        jdbcTemplate.update(
            """
            INSERT INTO engine_move_evaluation
                (id, engine_analysis_id, move, eval_cp, eval_loss_from_best)
            VALUES (?, ?, 'e4', 70, 0.0)
            """.trimIndent(),
            UUID.randomUUID(),
            analysisId,
        )
        val overlap = overlapFor(2)
        whenever(stockfishService.analyzeMultiPv(eq(position.fen), eq(16), any()))
            .thenAnswer {
                overlap.arrive()
                listOf(EngineCandidate("d4", EvalScore(cp = 50, mate = null)))
            }

        val outcomes = runConcurrently(position, 2)

        assertTrue(outcomes.all { it.isSuccess }, "partial-parent recovery must be successful: $outcomes")
        val persisted = engineAnalysisRepository.findByPositionIdWithMoveEvaluations(position.id)
        assertNotNull(persisted)
        assertEquals(analysisId, persisted.id)
        assertEquals(70, persisted.bestMoveEvalCp, "incremental analysis must reuse the authoritative baseline")
        assertEquals(setOf("e4", "d4"), persisted.moveEvaluations.map { it.move }.toSet())
    }

    @Test
    fun `same missing move converges on one authoritative child`() {
        val position = seedPosition("same-child")
        seedOccurrences(position, "e4")
        seedAnalysis(position, baseline = 40, move = null)
        val overlap = overlapFor(2)
        whenever(stockfishService.analyzeMultiPv(eq(position.fen), eq(16), any()))
            .thenAnswer {
                overlap.arrive()
                listOf(EngineCandidate("e4", EvalScore(cp = 30, mate = null)))
            }

        val outcomes = runConcurrently(position, 2)

        assertTrue(outcomes.all { it.isSuccess }, "same-move race must be recovered: $outcomes")
        val persisted = engineAnalysisRepository.findByPositionIdWithMoveEvaluations(position.id)
        assertNotNull(persisted)
        assertEquals(1, persisted.moveEvaluations.count { it.move == "e4" })
        assertEquals(30, persisted.moveEvaluations.single { it.move == "e4" }.evalCp)
    }

    @Test
    fun `different missing moves overlap and neither evaluation is lost`() {
        val position = seedPosition("different-children")
        seedOccurrences(position, "e4")
        seedAnalysis(position, baseline = 40, move = "e4")
        val overlap = overlapFor(2)
        whenever(stockfishService.analyzeMultiPv(eq(position.fen), eq(16), eq(1)))
            .thenAnswer {
                overlap.arrive()
                listOf(EngineCandidate("d4", EvalScore(cp = 30, mate = null)))
            }
        whenever(stockfishService.analyzeMultiPv(eq(position.fen), eq(16), eq(2)))
            .thenAnswer {
                overlap.arrive()
                listOf(EngineCandidate("c4", EvalScore(cp = 20, mate = null)))
            }

        val outcomes =
            runConcurrently(
                listOf(
                    positionRepository.findById(position.id).orElseThrow() to 1,
                    positionRepository.findById(position.id).orElseThrow() to 2,
                ),
            )

        assertTrue(outcomes.all { it.isSuccess }, "different-move workers must both complete: $outcomes")
        val persisted = engineAnalysisRepository.findByPositionIdWithMoveEvaluations(position.id)
        assertNotNull(persisted)
        assertEquals(setOf("e4", "d4", "c4"), persisted.moveEvaluations.map { it.move }.toSet())
        assertEquals(3, persisted.moveEvaluations.size)
        assertEquals(1, persisted.moveEvaluations.count { it.move == "d4" })
        assertEquals(1, persisted.moveEvaluations.count { it.move == "c4" })
    }

    @Test
    fun `unrelated positions overlap without a global analysis lock`() {
        val first = seedPosition("unrelated-one")
        val second = seedPosition("unrelated-two")
        seedOccurrences(first, "e4")
        seedOccurrences(second, "d4")
        val overlap = overlapFor(2)
        whenever(stockfishService.analyzeMultiPv(any(), eq(16), any()))
            .thenAnswer {
                overlap.arrive()
                listOf(EngineCandidate("e4", EvalScore(cp = 40, mate = null)))
            }
        whenever(stockfishService.analyze(eq(first.fen), eq(16), eq(listOf("d4"))))
            .thenReturn(
                mapOf(
                    "d4" to PositionAnalysis(bestMove = "c4", score = EvalScore(cp = 20, mate = null)),
                ),
            )

        val outcomes =
            runConcurrently(
                listOf(
                    positionRepository.findById(first.id).orElseThrow() to null,
                    positionRepository.findById(second.id).orElseThrow() to null,
                ),
            )

        assertTrue(outcomes.all { it.isSuccess }, "unrelated analyses must both complete: $outcomes")
        assertEquals(2, engineAnalysisRepository.count())
        val firstAnalysis = engineAnalysisRepository.findByPositionIdWithMoveEvaluations(first.id)
        assertNotNull(firstAnalysis)
        assertEquals("e4", firstAnalysis.bestMove)
        assertEquals(40, firstAnalysis.bestMoveEvalCp)
        assertEquals(setOf("e4"), firstAnalysis.moveEvaluations.map { it.move }.toSet())
        val secondAnalysis = engineAnalysisRepository.findByPositionIdWithMoveEvaluations(second.id)
        assertNotNull(secondAnalysis)
        assertEquals("e4", secondAnalysis.bestMove)
        assertEquals(40, secondAnalysis.bestMoveEvalCp)
        assertEquals(setOf("e4", "d4"), secondAnalysis.moveEvaluations.map { it.move }.toSet())
        assertEquals(2, secondAnalysis.moveEvaluations.size)
    }

    @Test
    fun `genuine database failure is not an expected uniqueness race`() {
        val position = seedPosition("genuine-failure")
        seedOccurrences(position, "e4")
        jdbcTemplate.execute(
            """
            CREATE OR REPLACE FUNCTION reject_engine_analysis_child()
            RETURNS trigger AS $$
            BEGIN
                IF NEW.move = 'e4' THEN
                    RAISE EXCEPTION 'forced engine child foreign-key failure'
                        USING ERRCODE = '23503';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER reject_engine_analysis_child_trigger
            BEFORE INSERT ON engine_move_evaluation
            FOR EACH ROW EXECUTE FUNCTION reject_engine_analysis_child()
            """.trimIndent(),
        )
        whenever(stockfishService.analyzeMultiPv(eq(position.fen), eq(16), any()))
            .thenReturn(listOf(EngineCandidate("e4", EvalScore(cp = 30, mate = null))))

        val failure =
            assertFailsWith<RuntimeException> {
                engineAnalysisService.analyzePosition(position)
            }
        val failureMessage = failure.message.orEmpty()
        val causeMessage = failure.cause?.message.orEmpty()
        assertTrue(
            failureMessage.contains("foreign-key failure") || causeMessage.contains("foreign-key failure"),
            "the real database failure must remain distinguishable: $failure",
        )

        assertEquals(0, engineAnalysisRepository.count())
    }

    @Test
    fun `worker rejects a child insert that did not persist after authoritative reread`() {
        val position = seedPosition("missing-child-after-insert")
        seedOccurrences(position, "d4")
        seedAnalysis(position, baseline = 40, move = null)
        jdbcTemplate.execute(
            """
            CREATE OR REPLACE FUNCTION suppress_engine_analysis_child()
            RETURNS trigger AS $$
            BEGIN
                IF NEW.move = 'd4' THEN
                    RETURN NULL;
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER suppress_engine_analysis_child_trigger
            BEFORE INSERT ON engine_move_evaluation
            FOR EACH ROW EXECUTE FUNCTION suppress_engine_analysis_child()
            """.trimIndent(),
        )
        whenever(stockfishService.analyzeMultiPv(eq(position.fen), eq(16), any()))
            .thenReturn(listOf(EngineCandidate("d4", EvalScore(cp = 30, mate = null))))

        val failure =
            assertFailsWith<IllegalStateException> {
                engineAnalysisService.analyzePosition(position)
            }

        assertTrue(
            failure.message.orEmpty().contains("authoritative re-read") &&
                failure.message.orEmpty().contains("d4"),
            "the service must identify the missing persisted evaluation: ${failure.message}",
        )
        val persisted = engineAnalysisRepository.findByPositionIdWithMoveEvaluations(position.id)
        assertNotNull(persisted)
        assertTrue(persisted.moveEvaluations.isEmpty(), "the trigger should leave d4 absent in PostgreSQL")
    }

    @Test
    fun `unrelated unique violation during parent creation remains a persistence failure`() {
        val position = seedPosition("unrelated-unique-failure")
        seedOccurrences(position, "e4")
        jdbcTemplate.execute(
            """
            CREATE OR REPLACE FUNCTION reject_engine_analysis_parent()
            RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'duplicate key value violates unique constraint "other_unique_constraint"'
                    USING ERRCODE = '23505';
            END;
            $$ LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            CREATE TRIGGER reject_engine_analysis_parent_trigger
            BEFORE INSERT ON engine_analysis
            FOR EACH ROW EXECUTE FUNCTION reject_engine_analysis_parent()
            """.trimIndent(),
        )
        whenever(stockfishService.analyzeMultiPv(eq(position.fen), eq(16), any()))
            .thenReturn(listOf(EngineCandidate("e4", EvalScore(cp = 30, mate = null))))

        val failure =
            assertFailsWith<RuntimeException> {
                engineAnalysisService.analyzePosition(position)
            }

        assertTrue(
            failure.message.orEmpty().contains("other_unique_constraint") ||
                failure.cause?.message.orEmpty().contains("other_unique_constraint"),
            "an unrelated 23505 must remain distinguishable: $failure",
        )
        assertEquals(0, engineAnalysisRepository.count())
    }

    private fun seedPosition(hash: String): Position =
        positionRepository.saveAndFlush(
            Position(
                hash = hash,
                fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
            ),
        )

    private fun seedOccurrences(
        position: Position,
        vararg moves: String,
    ) {
        val accountId = UUID.randomUUID()
        val gameId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO chess_account (id, platform, username) VALUES (?, 'CHESS_COM', ?)",
            accountId,
            "engine-race-${position.hash}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO game
                (id, chess_account_id, platform_game_id, pgn)
            VALUES (?, ?, ?, '1. e4 e5')
            """.trimIndent(),
            gameId,
            accountId,
            "engine-race-${position.hash}",
        )
        moves.forEachIndexed { index, move ->
            jdbcTemplate.update(
                """
                INSERT INTO position_occurrence
                    (id, game_id, position_id, chess_account_id, ply_number, move_played, player_color)
                VALUES (?, ?, ?, ?, ?, ?, 'WHITE')
                """.trimIndent(),
                UUID.randomUUID(),
                gameId,
                position.id,
                accountId,
                index + 1,
                move,
            )
        }
    }

    private fun seedAnalysis(
        position: Position,
        baseline: Int,
        move: String?,
    ): EngineAnalysis {
        val analysis =
            engineAnalysisRepository.saveAndFlush(
                EngineAnalysis(
                    position = position,
                    depth = 16,
                    baselineEvalCp = baseline,
                    bestMove = "e4",
                    bestMoveEvalCp = baseline,
                ),
            )
        if (move != null) {
            analysis.moveEvaluations.add(
                MoveEvaluation(
                    engineAnalysis = analysis,
                    move = move,
                    evalCp = baseline,
                    evalLossFromBest = 0.0,
                ),
            )
            engineAnalysisRepository.saveAndFlush(analysis)
        }
        return analysis
    }

    private fun runConcurrently(
        position: Position,
        workers: Int,
    ): List<Result<Unit>> = runConcurrently(List(workers) { positionRepository.findById(position.id).orElseThrow() }.map { it to null })

    private fun runConcurrently(invocations: List<Pair<Position, Int?>>): List<Result<Unit>> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(invocations.size)
        return try {
            invocations.map { (position, multiPv) ->
                pool.submit<Result<Unit>> {
                    start.await(10, TimeUnit.SECONDS)
                    runCatching {
                        if (multiPv == null) {
                            engineAnalysisService.analyzePosition(position)
                        } else {
                            engineAnalysisService.analyzePosition(position, multiPv)
                        }
                    }
                }
            }.also { start.countDown() }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "worker executor did not terminate")
        }
    }

    private fun overlapFor(expected: Int): Overlap {
        val arrivals = AtomicInteger()
        val release = CountDownLatch(1)
        return Overlap {
            check(arrivals.incrementAndGet() <= expected)
            if (arrivals.get() == expected) release.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "Stockfish calls did not overlap" }
        }
    }

    private fun interface Overlap {
        fun arrive()
    }

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer<Nothing> = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.clean-disabled") { false }
            registry.add("spring.jpa.hibernate.ddl-auto") { "none" }
        }
    }
}
