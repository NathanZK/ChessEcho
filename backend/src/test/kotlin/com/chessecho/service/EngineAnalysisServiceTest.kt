package com.chessecho.service

import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.reflect.full.memberFunctions
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EngineAnalysisServiceTest {
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository
    private lateinit var engineAnalysisRepository: EngineAnalysisRepository
    private lateinit var stockfishService: StockfishService
    private lateinit var engineAnalysisService: EngineAnalysisService

    @BeforeEach
    fun setup() {
        positionOccurrenceRepository = mock()
        engineAnalysisRepository = mock()
        stockfishService = mock()
        val transactionManager = mock<PlatformTransactionManager>()
        whenever(transactionManager.getTransaction(any())).thenReturn(SimpleTransactionStatus())
        val transactionTemplate = TransactionTemplate(transactionManager)
        engineAnalysisService =
            EngineAnalysisService(
                positionOccurrenceRepository,
                engineAnalysisRepository,
                stockfishService,
                transactionTemplate,
            )
    }

    @Test
    fun `analyzePosition performs full analysis for new position using managed Position`() {
        val positionId = UUID.randomUUID()
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        val position = Position(id = positionId, hash = "hash1", fen = fen)

        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("e4", "Nf3"))
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)).thenReturn(null)

        val analysisMap =
            mapOf(
                "baseline" to PositionAnalysis(bestMove = "e4", score = EvalScore(cp = 40, mate = null)),
                "e4" to PositionAnalysis(bestMove = "e5", score = EvalScore(cp = 40, mate = null)),
                "Nf3" to PositionAnalysis(bestMove = "d6", score = EvalScore(cp = -10, mate = null)),
            )
        whenever(stockfishService.analyze(fen, 16, listOf("e4", "Nf3"))).thenReturn(analysisMap)

        engineAnalysisService.analyzePosition(position)

        val captor = argumentCaptor<EngineAnalysis>()
        verify(engineAnalysisRepository, times(1)).saveAndFlush(captor.capture())

        val saved = captor.firstValue
        assertEquals("e4", saved.bestMove)
        assertEquals(40, saved.bestMoveEvalCp)
        assertEquals(2, saved.moveEvaluations.size)
    }

    @Test
    fun `deterministically derives evaluation loss only when both centipawn inputs are present`() {
        assertEquals(0.75, engineAnalysisService.calculateEvalLoss(100, 25))
        assertEquals(0.0, engineAnalysisService.calculateEvalLoss(25, 100))
        assertEquals(null, engineAnalysisService.calculateEvalLoss(null, 25))
        assertEquals(null, engineAnalysisService.calculateEvalLoss(100, null))
    }

    @Test
    fun `analyzePosition persists historical move even if evalLossFromBest exceeds 1 5 pawns`() {
        val positionId = UUID.randomUUID()
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        val position = Position(id = positionId, hash = "hash1", fen = fen)

        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("e4", "a3"))
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)).thenReturn(null)

        // e4: evalLoss = 0.0 (40 - 40 / 100), a3: severe blunder evalLoss = 2.0 (40 - (-160) / 100) > 1.5 pawns
        val analysisMap =
            mapOf(
                "baseline" to PositionAnalysis(bestMove = "e4", score = EvalScore(cp = 40, mate = null)),
                "e4" to PositionAnalysis(bestMove = "e5", score = EvalScore(cp = 40, mate = null)),
                "a3" to PositionAnalysis(bestMove = "d5", score = EvalScore(cp = -160, mate = null)),
            )
        whenever(stockfishService.analyze(fen, 16, listOf("e4", "a3"))).thenReturn(analysisMap)

        engineAnalysisService.analyzePosition(position)

        val captor = argumentCaptor<EngineAnalysis>()
        verify(engineAnalysisRepository, times(1)).saveAndFlush(captor.capture())

        val saved = captor.firstValue
        // Both e4 and severe blunder a3 must be persisted for historical weakness tracking
        assertEquals(2, saved.moveEvaluations.size)
        assertNotNull(saved.moveEvaluations.find { it.move == "e4" })
        val blunderEval = saved.moveEvaluations.find { it.move == "a3" }
        assertNotNull(blunderEval)
        assertEquals(2.00, blunderEval.evalLossFromBest)
    }

    @Test
    fun `analyzePosition performs incremental analysis for missing moves on existing position reusing baseline`() {
        val positionId = UUID.randomUUID()
        val analysisId = UUID.randomUUID()
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        val position = Position(id = positionId, hash = "hash1", fen = fen)

        val existingAnalysis =
            EngineAnalysis(
                id = analysisId,
                position = position,
                depth = 16,
                baselineEvalCp = 40,
                bestMove = "e4",
                bestMoveEvalCp = 40,
            )
        existingAnalysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = existingAnalysis, move = "e4", evalCp = 40, evalLossFromBest = 0.0),
        )

        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("e4", "c4"))
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)).thenReturn(existingAnalysis)

        // Authoritative re-read after the conflict-safe insert: both the pre-existing "e4" and the newly
        // inserted "c4" are now persisted (whether this worker's own insert won the race or a concurrent
        // writer's did is irrelevant - the re-read only cares that the row now exists).
        val persistedAfterInsert =
            EngineAnalysis(
                id = analysisId,
                position = position,
                depth = 16,
                baselineEvalCp = 40,
                bestMove = "e4",
                bestMoveEvalCp = 40,
            )
        persistedAfterInsert.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = persistedAfterInsert, move = "e4", evalCp = 40, evalLossFromBest = 0.0),
        )
        persistedAfterInsert.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = persistedAfterInsert, move = "c4", evalCp = 10, evalLossFromBest = 0.30),
        )
        whenever(engineAnalysisRepository.findByIdWithMoveEvaluations(analysisId)).thenReturn(persistedAfterInsert)

        val missingAnalysisMap =
            mapOf(
                "c4" to PositionAnalysis(bestMove = "e5", score = EvalScore(cp = 10, mate = null)),
            )
        whenever(stockfishService.analyze(fen, 16, listOf("c4"))).thenReturn(missingAnalysisMap)

        engineAnalysisService.analyzePosition(position)

        // Only missing move "c4" should be sent to Stockfish, baseline is NOT recomputed
        verify(stockfishService, times(1)).analyze(fen, 16, listOf("c4"))

        // The existing analysis's fetched moveEvaluations collection must never be reassigned/cascaded
        // (that would risk orphan-removing a concurrently-inserted sibling row); only the missing move is
        // inserted, individually and idempotently, against the reused (not recomputed) baseline.
        verify(engineAnalysisRepository, never()).save(any())
        verify(engineAnalysisRepository, never()).saveAndFlush(any())
        val idCaptor = argumentCaptor<UUID>()
        verify(engineAnalysisRepository, times(1)).insertMoveEvaluationIfAbsent(
            idCaptor.capture(),
            eq(analysisId),
            eq("c4"),
            eq(10),
            anyOrNull(),
            eq(0.30),
        )
        // The write transaction must re-read authoritative persisted state after the idempotent insert
        // rather than trusting ON CONFLICT's own return value.
        verify(engineAnalysisRepository, times(1)).findByIdWithMoveEvaluations(analysisId)
    }

    @Test
    fun `analyzePosition fails fast if a required move is still absent after the authoritative re-read`() {
        val positionId = UUID.randomUUID()
        val analysisId = UUID.randomUUID()
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        val position = Position(id = positionId, hash = "hash1", fen = fen)

        val existingAnalysis =
            EngineAnalysis(
                id = analysisId,
                position = position,
                depth = 16,
                baselineEvalCp = 40,
                bestMove = "e4",
                bestMoveEvalCp = 40,
            )
        existingAnalysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = existingAnalysis, move = "e4", evalCp = 40, evalLossFromBest = 0.0),
        )

        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("e4", "c4"))
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)).thenReturn(existingAnalysis)

        // Simulate a defensive-invariant break: despite the idempotent insert being issued, the
        // authoritative re-read still does not show "c4" as persisted. This must surface loudly rather
        // than being silently accepted as "probably fine because ON CONFLICT ran".
        whenever(engineAnalysisRepository.findByIdWithMoveEvaluations(analysisId)).thenReturn(existingAnalysis)

        val missingAnalysisMap =
            mapOf(
                "c4" to PositionAnalysis(bestMove = "e5", score = EvalScore(cp = 10, mate = null)),
            )
        whenever(stockfishService.analyze(fen, 16, listOf("c4"))).thenReturn(missingAnalysisMap)

        assertThrows<IllegalStateException> {
            engineAnalysisService.analyzePosition(position)
        }
    }

    @Test
    fun `analyzePosition skips Stockfish call when no missing moves exist for existing position`() {
        val positionId = UUID.randomUUID()
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        val position = Position(id = positionId, hash = "hash1", fen = fen)

        val existingAnalysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = 40,
                bestMove = "e4",
                bestMoveEvalCp = 40,
            )
        existingAnalysis.moveEvaluations.add(
            MoveEvaluation(engineAnalysis = existingAnalysis, move = "e4", evalCp = 40, evalLossFromBest = 0.0),
        )

        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("e4"))
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)).thenReturn(existingAnalysis)

        engineAnalysisService.analyzePosition(position)

        verify(stockfishService, never()).analyze(any(), any(), any())
        verify(engineAnalysisRepository, never()).save(any())
        verify(engineAnalysisRepository, never()).saveAndFlush(any())
        verify(engineAnalysisRepository, never()).insertMoveEvaluationIfAbsent(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `test evalLoss calculation is semantically pure for best move, suboptimal move, and close alternative`() {
        val positionId = UUID.randomUUID()
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val position = Position(id = positionId, hash = "hash_sem", fen = fen)

        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("Qh4", "Nf6", "d5"))
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)).thenReturn(null)

        // Qh4: bestMove (+0.70 / 70 cp)
        // Nf6: suboptimal move (+0.20 / 20 cp) -> loss = (70 - 20) / 100 = 0.50
        // d5: close alternative (+0.69 / 69 cp) -> loss = (70 - 69) / 100 = 0.01
        val analysisMap =
            mapOf(
                "baseline" to PositionAnalysis(bestMove = "Qh4", score = EvalScore(cp = 70, mate = null)),
                "Qh4" to PositionAnalysis(bestMove = "e5", score = EvalScore(cp = 70, mate = null)),
                "Nf6" to PositionAnalysis(bestMove = "d6", score = EvalScore(cp = 20, mate = null)),
                "d5" to PositionAnalysis(bestMove = "exd5", score = EvalScore(cp = 69, mate = null)),
            )
        whenever(stockfishService.analyze(fen, 16, listOf("Qh4", "Nf6", "d5"))).thenReturn(analysisMap)

        engineAnalysisService.analyzePosition(position)

        val captor = argumentCaptor<EngineAnalysis>()
        verify(engineAnalysisRepository, times(1)).saveAndFlush(captor.capture())

        val saved = captor.firstValue
        val qh4Eval = saved.moveEvaluations.find { it.move == "Qh4" }
        val nf6Eval = saved.moveEvaluations.find { it.move == "Nf6" }
        val d5Eval = saved.moveEvaluations.find { it.move == "d5" }

        assertNotNull(qh4Eval)
        assertNotNull(nf6Eval)
        assertNotNull(d5Eval)

        // 1. Best move naturally evaluates to 0.0 loss
        assertEquals(0.00, qh4Eval.evalLossFromBest)

        // 2. Suboptimal move produces exact 0.50 pawns loss
        assertEquals(0.50, nf6Eval.evalLossFromBest)

        // 3. Close alternative produces 0.01 pawns loss
        assertEquals(0.01, d5Eval.evalLossFromBest)
    }

    @Test
    fun `test evalLoss calculation is perspective correct for Black player`() {
        val positionId = UUID.randomUUID()
        val fen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"
        val position = Position(id = positionId, hash = "hash_black", fen = fen)

        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("Nf6", "e5"))
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)).thenReturn(null)

        // Black to move:
        // Nf6: bestMove (+0.70 / 70 cp for Black)
        // e5: suboptimal move (+0.20 / 20 cp for Black) -> loss = (70 - 20) / 100 = 0.50 pawns
        val analysisMap =
            mapOf(
                "baseline" to PositionAnalysis(bestMove = "Nf6", score = EvalScore(cp = 70, mate = null)),
                "Nf6" to PositionAnalysis(bestMove = "Nc3", score = EvalScore(cp = 70, mate = null)),
                "e5" to PositionAnalysis(bestMove = "exd5", score = EvalScore(cp = 20, mate = null)),
            )
        whenever(stockfishService.analyze(fen, 16, listOf("Nf6", "e5"))).thenReturn(analysisMap)

        engineAnalysisService.analyzePosition(position)

        val captor = argumentCaptor<EngineAnalysis>()
        verify(engineAnalysisRepository, times(1)).saveAndFlush(captor.capture())

        val saved = captor.firstValue
        val nf6Eval = saved.moveEvaluations.find { it.move == "Nf6" }
        val e5Eval = saved.moveEvaluations.find { it.move == "e5" }

        assertNotNull(nf6Eval)
        assertNotNull(e5Eval)

        assertEquals(0.00, nf6Eval.evalLossFromBest)
        assertEquals(0.50, e5Eval.evalLossFromBest)
    }

    @Test
    fun `analyzePosition merges historical moves and MultiPV engine candidates deduplicated`() {
        val positionId = UUID.randomUUID()
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val position = Position(id = positionId, hash = "hash_merged", fen = fen)

        // User historically played: Bc4 (good) and Qh5 (blunder)
        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("Bc4", "Qh5"))
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)).thenReturn(null)

        // Engine MultiPV top 3 returns: Bc4 (best), Bb5, d4
        val engineCandidates =
            listOf(
                EngineCandidate("Bc4", EvalScore(cp = 40, mate = null)),
                EngineCandidate("Bb5", EvalScore(cp = 35, mate = null)),
                EngineCandidate("d4", EvalScore(cp = 30, mate = null)),
            )
        whenever(stockfishService.analyzeMultiPv(fen, 16, 5)).thenReturn(engineCandidates)

        // Merged candidates: Bc4 (duplicate), Qh5 (historical only), Bb5 (engine only), d4 (engine only) -> total 4 unique moves
        val analysisMap =
            mapOf(
                "baseline" to PositionAnalysis(bestMove = "Bc4", score = EvalScore(cp = 40, mate = null)),
                "Bc4" to PositionAnalysis(bestMove = "Nf6", score = EvalScore(cp = 40, mate = null)),
                "Qh5" to PositionAnalysis(bestMove = "g6", score = EvalScore(cp = -160, mate = null)),
                "Bb5" to PositionAnalysis(bestMove = "a6", score = EvalScore(cp = 35, mate = null)),
                "d4" to PositionAnalysis(bestMove = "exd4", score = EvalScore(cp = 30, mate = null)),
            )
        whenever(stockfishService.analyze(fen, 16, listOf("Qh5"))).thenReturn(analysisMap)

        engineAnalysisService.analyzePosition(position)

        val captor = argumentCaptor<EngineAnalysis>()
        verify(engineAnalysisRepository, times(1)).saveAndFlush(captor.capture())

        val saved = captor.firstValue
        // 4 MoveEvaluations persisted: Bc4, Qh5, Bb5, d4 (Bc4 is deduplicated)
        assertEquals(4, saved.moveEvaluations.size)

        val bc4Eval = saved.moveEvaluations.find { it.move == "Bc4" }
        val qh5Eval = saved.moveEvaluations.find { it.move == "Qh5" }
        val bb5Eval = saved.moveEvaluations.find { it.move == "Bb5" }
        val d4Eval = saved.moveEvaluations.find { it.move == "d4" }

        assertNotNull(bc4Eval)
        assertNotNull(qh5Eval)
        assertNotNull(bb5Eval)
        assertNotNull(d4Eval)

        // Engine best move gets evalLoss 0.0
        assertEquals(0.00, bc4Eval.evalLossFromBest)
        // Historical blunder gets evalLoss 2.0
        assertEquals(2.00, qh5Eval.evalLossFromBest)
        // Non-best MultiPV engine move gets evalLoss 0.05
        assertEquals(0.05, bb5Eval.evalLossFromBest)
        // Non-best MultiPV engine move gets evalLoss 0.10
        assertEquals(0.10, d4Eval.evalLossFromBest)
    }

    @Test
    fun `analyzePosition reuses MultiPV scores directly and skips secondary Stockfish search when all historical moves are in MultiPV`() {
        val positionId = UUID.randomUUID()
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val position = Position(id = positionId, hash = "hash_all_multipv", fen = fen)

        // Historical move played by user: Bc4 (already in MultiPV top N)
        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("Bc4"))
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)).thenReturn(null)

        val engineCandidates =
            listOf(
                EngineCandidate("Bc4", EvalScore(cp = 40, mate = null)),
                EngineCandidate("Bb5", EvalScore(cp = 35, mate = null)),
                EngineCandidate("d4", EvalScore(cp = 30, mate = null)),
            )
        whenever(stockfishService.analyzeMultiPv(fen, 16, 5)).thenReturn(engineCandidates)

        engineAnalysisService.analyzePosition(position)

        // Secondary 1-ply search must be SKIPPED because all candidates have scores from MultiPV
        verify(stockfishService, never()).analyze(any(), any(), any())

        val captor = argumentCaptor<EngineAnalysis>()
        verify(engineAnalysisRepository, times(1)).saveAndFlush(captor.capture())

        val saved = captor.firstValue
        assertEquals("Bc4", saved.bestMove)
        assertEquals(40, saved.bestMoveEvalCp)
        assertEquals(3, saved.moveEvaluations.size)

        val bc4Eval = saved.moveEvaluations.find { it.move == "Bc4" }
        val bb5Eval = saved.moveEvaluations.find { it.move == "Bb5" }
        val d4Eval = saved.moveEvaluations.find { it.move == "d4" }

        assertNotNull(bc4Eval)
        assertNotNull(bb5Eval)
        assertNotNull(d4Eval)

        assertEquals(0.00, bc4Eval.evalLossFromBest)
        assertEquals(0.05, bb5Eval.evalLossFromBest)
        assertEquals(0.10, d4Eval.evalLossFromBest)
    }

    @Test
    fun `analyzePosition reuses MultiPV scores and evaluates ONLY non-MultiPV historical moves in secondary search`() {
        val positionId = UUID.randomUUID()
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val position = Position(id = positionId, hash = "hash_selective_search", fen = fen)

        // Historical moves played by user: Bc4 (in MultiPV) and Qh5 (blunder, NOT in MultiPV)
        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("Bc4", "Qh5"))
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)).thenReturn(null)

        val engineCandidates =
            listOf(
                EngineCandidate("Bc4", EvalScore(cp = 40, mate = null)),
                EngineCandidate("Bb5", EvalScore(cp = 35, mate = null)),
                EngineCandidate("d4", EvalScore(cp = 30, mate = null)),
            )
        whenever(stockfishService.analyzeMultiPv(fen, 16, 5)).thenReturn(engineCandidates)

        // Secondary search should be called ONLY for remaining historical blunder "Qh5"
        val remainingAnalysisMap =
            mapOf(
                "Qh5" to PositionAnalysis(bestMove = "g6", score = EvalScore(cp = -160, mate = null)),
            )
        whenever(stockfishService.analyze(fen, 16, listOf("Qh5"))).thenReturn(remainingAnalysisMap)

        engineAnalysisService.analyzePosition(position)

        // Verify stockfishService.analyze was called ONLY for ["Qh5"], NOT for Bc4, Bb5, or d4
        verify(stockfishService, times(1)).analyze(fen, 16, listOf("Qh5"))

        val captor = argumentCaptor<EngineAnalysis>()
        verify(engineAnalysisRepository, times(1)).saveAndFlush(captor.capture())

        val saved = captor.firstValue
        assertEquals("Bc4", saved.bestMove)
        assertEquals(40, saved.bestMoveEvalCp)
        assertEquals(4, saved.moveEvaluations.size)

        val bc4Eval = saved.moveEvaluations.find { it.move == "Bc4" }
        val qh5Eval = saved.moveEvaluations.find { it.move == "Qh5" }
        val bb5Eval = saved.moveEvaluations.find { it.move == "Bb5" }
        val d4Eval = saved.moveEvaluations.find { it.move == "d4" }

        assertNotNull(bc4Eval)
        assertNotNull(qh5Eval)
        assertNotNull(bb5Eval)
        assertNotNull(d4Eval)

        assertEquals(0.00, bc4Eval.evalLossFromBest)
        assertEquals(2.00, qh5Eval.evalLossFromBest)
        assertEquals(0.05, bb5Eval.evalLossFromBest)
        assertEquals(0.10, d4Eval.evalLossFromBest)
    }

    @Test
    fun `analyzePosition accepts a nullable per-call MultiPV override`() {
        val positionId = UUID.randomUUID()
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        val position = Position(id = positionId, hash = "override", fen = fen)
        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("e4"))
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)).thenReturn(null)
        whenever(stockfishService.analyzeMultiPv(fen, 16, 1))
            .thenReturn(listOf(EngineCandidate("e4", EvalScore(cp = 40, mate = null))))

        val analyzeWithOverride =
            requireNotNull(
                EngineAnalysisService::class.memberFunctions.singleOrNull {
                    it.name == "analyzePosition" && it.parameters.size == 3
                },
            ) {
                "analyzePosition must accept an optional per-call MultiPV override"
            }

        analyzeWithOverride.call(engineAnalysisService, position, 1)

        verify(stockfishService).analyzeMultiPv(fen, 16, 1)
    }

    @Test
    fun `calculateEvalLoss handles Scenario 1 Centipawn vs Centipawn`() {
        // Equal evaluation
        assertEquals(0.0, EngineAnalysisService.calculateEvalLoss(EvalScore(100, null), EvalScore(100, null)))
        // Exact mistake boundary (0.80 pawns)
        assertEquals(0.80, EngineAnalysisService.calculateEvalLoss(EvalScore(100, null), EvalScore(20, null)))
        // Better move played
        assertEquals(0.0, EngineAnalysisService.calculateEvalLoss(EvalScore(100, null), EvalScore(200, null)))
        // Cap at 20.0
        assertEquals(20.0, EngineAnalysisService.calculateEvalLoss(EvalScore(500, null), EvalScore(-2000, null)))
    }

    @Test
    fun `calculateEvalLoss handles Scenario 2 Winning Mate vs Winning Mate Prolongation`() {
        // Equal mate
        assertEquals(0.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, 1)))
        // Faster mate found
        assertEquals(0.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, 3), EvalScore(null, 1)))
        // Slight prolongation (mate 2 instead of mate 1)
        assertEquals(0.10, EngineAnalysisService.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, 2)))
        // Mate 3 instead of mate 1
        assertEquals(0.20, EngineAnalysisService.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, 3)))
        // Mate 5 instead of mate 1
        assertEquals(0.40, EngineAnalysisService.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, 5)))
        // Capped at 0.50 (< 0.80, remains acceptable move)
        assertEquals(0.50, EngineAnalysisService.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, 10)))
    }

    @Test
    fun `calculateEvalLoss handles Scenario 3 Winning Mate vs Centipawns`() {
        // Threw away forced mate to high advantage
        assertEquals(8.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, 1), EvalScore(800, null)))
        assertEquals(8.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, 1), EvalScore(500, null)))
        // Threw away forced mate to slight advantage
        assertEquals(11.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, 1), EvalScore(200, null)))
        // Threw away forced mate to equal position
        assertEquals(13.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, 1), EvalScore(0, null)))
        // Threw away forced mate to losing position (capped at 15.0)
        assertEquals(15.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, 1), EvalScore(-300, null)))
    }

    @Test
    fun `calculateEvalLoss handles Scenario 4 Winning Mate vs Losing Mate`() {
        // Blundered into mate in 1 (highest loss)
        assertEquals(19.5, EngineAnalysisService.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, -1)))
        assertEquals(19.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, -2)))
        assertEquals(17.5, EngineAnalysisService.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, -5)))
        // Capped at 15.0 for slow mate
        assertEquals(15.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, 1), EvalScore(null, -10)))
    }

    @Test
    fun `calculateEvalLoss handles Scenario 5 Centipawns vs Losing Mate`() {
        // From +2.00 into mate in 1
        assertEquals(16.5, EngineAnalysisService.calculateEvalLoss(EvalScore(200, null), EvalScore(null, -1)))
        assertEquals(14.5, EngineAnalysisService.calculateEvalLoss(EvalScore(200, null), EvalScore(null, -5)))
        // From 0.00 into mate in 1
        assertEquals(14.5, EngineAnalysisService.calculateEvalLoss(EvalScore(0, null), EvalScore(null, -1)))
        assertEquals(12.5, EngineAnalysisService.calculateEvalLoss(EvalScore(0, null), EvalScore(null, -5)))
        // From -2.00 into mate in 1
        assertEquals(14.5, EngineAnalysisService.calculateEvalLoss(EvalScore(-200, null), EvalScore(null, -1)))
        // Cap at 20.0
        assertEquals(20.0, EngineAnalysisService.calculateEvalLoss(EvalScore(1500, null), EvalScore(null, -1)))
    }

    @Test
    fun `calculateEvalLoss handles Scenario 6 Centipawns vs Winning Mate`() {
        assertEquals(0.0, EngineAnalysisService.calculateEvalLoss(EvalScore(200, null), EvalScore(null, 2)))
    }

    @Test
    fun `calculateEvalLoss handles Scenario 7 Losing Mate Defense Shortening vs Prolongation`() {
        // Equal defense
        assertEquals(0.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, -5), EvalScore(null, -5)))
        // Prolonged defense (held out longer)
        assertEquals(0.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, -5), EvalScore(null, -6)))
        // Shortened defense by 1 move (mate in 4 instead of 5 -> 1.0 >= 0.80)
        assertEquals(1.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, -5), EvalScore(null, -4)))
        // Shortened defense by 2 moves (mate in 3 instead of 5 -> 2.0)
        assertEquals(2.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, -5), EvalScore(null, -3)))
        // Shortened defense to mate in 1 (mate in 1 instead of 5 -> 4.0)
        assertEquals(4.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, -5), EvalScore(null, -1)))
        // Capped at 5.0
        assertEquals(5.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, -10), EvalScore(null, -1)))
    }

    @Test
    fun `calculateEvalLoss handles Scenario 8 Losing Mate Escape vs Inconclusive Horizon Effect`() {
        // Proven escape to winning mate
        assertEquals(0.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, -5), EvalScore(null, 2)))
        // Proven escape to equal or positive centipawns
        assertEquals(0.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, -5), EvalScore(0, null)))
        assertEquals(0.0, EngineAnalysisService.calculateEvalLoss(EvalScore(null, -5), EvalScore(150, null)))
        // Inconclusive horizon effect (negative centipawns): cannot prove escape, returns null
        kotlin.test.assertNull(EngineAnalysisService.calculateEvalLoss(EvalScore(null, -5), EvalScore(-500, null)))
    }

    @Test
    fun `calculateEvalLoss handles Precedence, Nulls, and Semantic Equality`() {
        // Null / missing evaluations strictly return null, never 0.0
        kotlin.test.assertNull(EngineAnalysisService.calculateEvalLoss(null, EvalScore(100, null)))
        kotlin.test.assertNull(EngineAnalysisService.calculateEvalLoss(EvalScore(100, null), null))
        kotlin.test.assertNull(EngineAnalysisService.calculateEvalLoss(EvalScore(null, null), EvalScore(100, null)))
        kotlin.test.assertNull(EngineAnalysisService.calculateEvalLoss(EvalScore(100, null), EvalScore(null, null)))
        // Illegal mate = 0 returns null
        kotlin.test.assertNull(EngineAnalysisService.calculateEvalLoss(EvalScore(null, 0), EvalScore(100, null)))
        kotlin.test.assertNull(EngineAnalysisService.calculateEvalLoss(EvalScore(100, null), EvalScore(null, 0)))
        // Semantic equality: mate takes precedence over cp
        assertEquals(0.0, EngineAnalysisService.calculateEvalLoss(EvalScore(500, 2), EvalScore(100, 2)))
    }

    @Test
    fun `analyzePosition full analysis populates mate evals with mutual exclusivity without fallback`() {
        val positionId = UUID.randomUUID()
        val fen = "6k1/5ppp/8/8/8/8/5PPP/4R1K1 w - - 0 1"
        val position = Position(id = positionId, hash = "mate_pos", fen = fen)

        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("Re8#", "h3"))
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)).thenReturn(null)

        val engineCandidates =
            listOf(
                EngineCandidate("Re8#", EvalScore(cp = null, mate = 1)),
                EngineCandidate("h3", EvalScore(cp = 200, mate = null)),
            )
        whenever(stockfishService.analyzeMultiPv(fen, 16, 5)).thenReturn(engineCandidates)

        engineAnalysisService.analyzePosition(position)

        val captor = argumentCaptor<EngineAnalysis>()
        verify(engineAnalysisRepository, times(1)).saveAndFlush(captor.capture())

        val saved = captor.firstValue
        assertEquals("Re8#", saved.bestMove)
        assertEquals(1, saved.bestMoveEvalMate)
        kotlin.test.assertNull(saved.bestMoveEvalCp)
        assertEquals(1, saved.baselineEvalMate)
        kotlin.test.assertNull(saved.baselineEvalCp)

        val re8Eval = saved.moveEvaluations.find { it.move == "Re8#" }
        val h3Eval = saved.moveEvaluations.find { it.move == "h3" }

        assertNotNull(re8Eval)
        assertNotNull(h3Eval)

        assertEquals(1, re8Eval.evalMate)
        kotlin.test.assertNull(re8Eval.evalCp)
        assertEquals(0.0, re8Eval.evalLossFromBest)

        kotlin.test.assertNull(h3Eval.evalMate)
        assertEquals(200, h3Eval.evalCp)
        assertEquals(11.0, h3Eval.evalLossFromBest)
    }

    @Test
    fun `completeMissingMoveEvaluations with mate baseline evaluates candidates against mate and persists evalMate without 0_0 fallback`() {
        val positionId = UUID.randomUUID()
        val fen = "8/8/8/8/8/8/8/4k2K w - - 0 1"
        val existingAnalysisId = UUID.randomUUID()
        val existingAnalysis =
            EngineAnalysis(
                id = existingAnalysisId,
                position = Position(id = positionId, hash = "losing_pos", fen = fen),
                depth = 16,
                bestMove = "Kh2",
                bestMoveEvalMate = -5,
                baselineEvalMate = -5,
            )

        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId))
            .thenReturn(listOf("Kh2", "Kf8", "h6", "g6"))
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId))
            .thenReturn(existingAnalysis)

        // MultiPV top candidates
        whenever(stockfishService.analyzeMultiPv(fen, 16, 5)).thenReturn(
            listOf(
                EngineCandidate("Kh2", EvalScore(cp = null, mate = -5)),
                EngineCandidate("Kf8", EvalScore(cp = null, mate = -3)),
            ),
        )
        // Secondary 1-ply search for remaining historical moves
        whenever(stockfishService.analyze(fen, 16, listOf("h6", "g6"))).thenReturn(
            mapOf(
                "h6" to PositionAnalysis(bestMove = "Kh8", score = EvalScore(cp = -500, mate = null)),
                "g6" to PositionAnalysis(bestMove = "Kh8", score = EvalScore(cp = null, mate = -6)),
            ),
        )
        // Repo read inside completeMissingMoveEvaluations
        whenever(engineAnalysisRepository.findByIdWithMoveEvaluations(existingAnalysisId)).thenReturn(
            EngineAnalysis(
                id = existingAnalysisId,
                position = Position(id = positionId, hash = "losing_pos", fen = fen),
                depth = 16,
                bestMove = "Kh2",
                bestMoveEvalMate = -5,
                baselineEvalMate = -5,
            ).apply {
                moveEvaluations.add(MoveEvaluation(engineAnalysis = this, move = "Kh2", evalMate = -5, evalLossFromBest = 0.0))
                moveEvaluations.add(MoveEvaluation(engineAnalysis = this, move = "Kf8", evalMate = -3, evalLossFromBest = 2.0))
                moveEvaluations.add(MoveEvaluation(engineAnalysis = this, move = "h6", evalCp = -500, evalLossFromBest = null))
                moveEvaluations.add(MoveEvaluation(engineAnalysis = this, move = "g6", evalMate = -6, evalLossFromBest = 0.0))
            },
        )

        val position = Position(id = positionId, hash = "losing_pos", fen = fen)
        engineAnalysisService.analyzePosition(position)

        verifyInsertMoveEvaluation(existingAnalysisId, "Kf8", null, -3, 2.0)
        verifyInsertMoveEvaluation(existingAnalysisId, "h6", -500, null, null)
        verifyInsertMoveEvaluation(existingAnalysisId, "g6", null, -6, 0.0)
    }

    private fun verifyInsertMoveEvaluation(
        analysisId: UUID,
        move: String,
        evalCp: Int?,
        evalMate: Int?,
        evalLossFromBest: Double?,
    ) {
        verify(engineAnalysisRepository).insertMoveEvaluationIfAbsent(
            id = any(),
            engineAnalysisId = eq(analysisId),
            move = eq(move),
            evalCp = eq(evalCp),
            evalMate = eq(evalMate),
            evalLossFromBest = eq(evalLossFromBest),
        )
    }
}
