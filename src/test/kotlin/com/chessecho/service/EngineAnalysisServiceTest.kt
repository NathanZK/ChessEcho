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
}
