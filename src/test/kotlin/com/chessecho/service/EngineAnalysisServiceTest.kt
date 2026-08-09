package com.chessecho.service

import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PositionRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EngineAnalysisServiceTest {
    private lateinit var positionRepository: PositionRepository
    private lateinit var positionOccurrenceRepository: PositionOccurrenceRepository
    private lateinit var engineAnalysisRepository: EngineAnalysisRepository
    private lateinit var stockfishService: StockfishService
    private lateinit var engineAnalysisService: EngineAnalysisService

    @BeforeEach
    fun setup() {
        positionRepository = mock()
        positionOccurrenceRepository = mock()
        engineAnalysisRepository = mock()
        stockfishService = mock()
        engineAnalysisService =
            EngineAnalysisService(
                positionRepository,
                positionOccurrenceRepository,
                engineAnalysisRepository,
                stockfishService,
            )
    }

    @Test
    fun `analyzePosition performs full analysis for new position`() {
        val positionId = UUID.randomUUID()
        val fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        val position = Position(id = positionId, hash = "hash1", fen = fen)

        whenever(positionRepository.findById(positionId)).thenReturn(Optional.of(position))
        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("e4", "Nf3"))
        whenever(engineAnalysisRepository.findByPositionId(positionId)).thenReturn(null)

        val analysisMap =
            mapOf(
                "baseline" to PositionAnalysis(bestMove = "e4", score = EvalScore(cp = 40, mate = null)),
                "e4" to PositionAnalysis(bestMove = "e5", score = EvalScore(cp = 40, mate = null)),
                "Nf3" to PositionAnalysis(bestMove = "d6", score = EvalScore(cp = -10, mate = null)),
            )
        whenever(stockfishService.analyze(fen, 16, listOf("e4", "Nf3"))).thenReturn(analysisMap)

        engineAnalysisService.analyzePosition(positionId)

        val captor = argumentCaptor<EngineAnalysis>()
        verify(engineAnalysisRepository, times(1)).save(captor.capture())

        val saved = captor.firstValue
        assertEquals("e4", saved.bestMove)
        assertEquals(40, saved.bestMoveEvalCp)
        assertEquals(2, saved.moveEvaluations.size)
    }

    @Test
    fun `analyzePosition performs incremental analysis for missing moves on existing position`() {
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

        whenever(positionRepository.findById(positionId)).thenReturn(Optional.of(position))
        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("e4", "c4"))
        whenever(engineAnalysisRepository.findByPositionId(positionId)).thenReturn(existingAnalysis)
        whenever(engineAnalysisRepository.findEvaluatedMovesByPositionId(positionId)).thenReturn(listOf("e4"))

        val missingAnalysisMap =
            mapOf(
                "c4" to PositionAnalysis(bestMove = "e5", score = EvalScore(cp = 10, mate = null)),
            )
        whenever(stockfishService.analyze(fen, 16, listOf("c4"))).thenReturn(missingAnalysisMap)

        engineAnalysisService.analyzePosition(positionId)

        // Only missing move "c4" should be sent to Stockfish
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

        whenever(positionRepository.findById(positionId)).thenReturn(Optional.of(position))
        whenever(positionOccurrenceRepository.findDistinctMovesByPositionId(positionId)).thenReturn(listOf("e4"))
        whenever(engineAnalysisRepository.findByPositionId(positionId)).thenReturn(existingAnalysis)
        whenever(engineAnalysisRepository.findEvaluatedMovesByPositionId(positionId)).thenReturn(listOf("e4"))

        engineAnalysisService.analyzePosition(positionId)

        verify(stockfishService, never()).analyze(any(), any(), any())
        verify(engineAnalysisRepository, never()).save(any())
    }
}
