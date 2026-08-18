package com.chessecho.service

import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
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
        engineAnalysisService =
            EngineAnalysisService(
                positionOccurrenceRepository,
                engineAnalysisRepository,
                stockfishService,
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
        verify(engineAnalysisRepository, times(1)).save(captor.capture())

        val saved = captor.firstValue
        assertEquals("e4", saved.bestMove)
        assertEquals(40, saved.bestMoveEvalCp)
        assertEquals(2, saved.moveEvaluations.size)
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
        verify(engineAnalysisRepository, times(1)).save(captor.capture())

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

        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("e4", "c4"))
        whenever(engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)).thenReturn(existingAnalysis)

        val missingAnalysisMap =
            mapOf(
                "c4" to PositionAnalysis(bestMove = "e5", score = EvalScore(cp = 10, mate = null)),
            )
        whenever(stockfishService.analyze(fen, 16, listOf("c4"))).thenReturn(missingAnalysisMap)

        engineAnalysisService.analyzePosition(position)

        // Only missing move "c4" should be sent to Stockfish, baseline is NOT recomputed
        verify(stockfishService, times(1)).analyze(fen, 16, listOf("c4"))

        val captor = argumentCaptor<EngineAnalysis>()
        verify(engineAnalysisRepository, times(1)).save(captor.capture())

        val saved = captor.firstValue
        assertEquals(2, saved.moveEvaluations.size)
        assertNotNull(saved.moveEvaluations.find { it.move == "c4" })
        assertEquals(0.30, saved.moveEvaluations.find { it.move == "c4" }?.evalLossFromBest)
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
        verify(engineAnalysisRepository, times(1)).save(captor.capture())

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
        verify(engineAnalysisRepository, times(1)).save(captor.capture())

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
        verify(engineAnalysisRepository, times(1)).save(captor.capture())

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
        verify(engineAnalysisRepository, times(1)).save(captor.capture())

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
        verify(engineAnalysisRepository, times(1)).save(captor.capture())

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
}
