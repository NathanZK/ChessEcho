package com.chessecho.service

import com.chessecho.domain.Position
import com.chessecho.repository.PositionRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.reflect.full.memberFunctions

class EngineAnalysisOrchestratorTest {
    private lateinit var positionRepository: PositionRepository
    private lateinit var engineAnalysisService: EngineAnalysisService
    private lateinit var orchestrator: EngineAnalysisOrchestrator

    @BeforeEach
    fun setup() {
        positionRepository = mock()
        engineAnalysisService = mock()
        orchestrator = EngineAnalysisOrchestrator(positionRepository, engineAnalysisService)
    }

    @Test
    fun `analyzeAffectedPositions uses single batch query to filter qualifying positions`() {
        val pos1 = Position(id = UUID.randomUUID(), hash = "h1", fen = "f1")
        val pos2 = Position(id = UUID.randomUUID(), hash = "h2", fen = "f2")
        val pos3Id = UUID.randomUUID()
        val affected = setOf(pos1.id, pos2.id, pos3Id)

        // Only pos1 and pos2 have >= 5 occurrences
        whenever(positionRepository.findQualifyingPositions(affected, 5L)).thenReturn(listOf(pos1, pos2))

        orchestrator.analyzeAffectedPositions(affected)

        // Batch query called once
        verify(positionRepository, times(1)).findQualifyingPositions(affected, 5L)
        // Position analysis triggered only for qualifying position entities
        verify(engineAnalysisService, times(1)).analyzePosition(pos1)
        verify(engineAnalysisService, times(1)).analyzePosition(pos2)
    }

    @Test
    fun `analyzeAffectedPositions uses configured custom minimum occurrence threshold`() {
        val customOrchestrator = EngineAnalysisOrchestrator(positionRepository, engineAnalysisService, minOccurrences = 3L)
        val pos1 = Position(id = UUID.randomUUID(), hash = "h1", fen = "f1")
        val affected = setOf(pos1.id)

        whenever(positionRepository.findQualifyingPositions(affected, 3L)).thenReturn(listOf(pos1))

        customOrchestrator.analyzeAffectedPositions(affected)

        verify(positionRepository, times(1)).findQualifyingPositions(affected, 3L)
        verify(engineAnalysisService, times(1)).analyzePosition(pos1)
    }

    @Test
    fun `positions below threshold are not analyzed`() {
        val pos1Id = UUID.randomUUID()
        val affected = setOf(pos1Id)

        whenever(positionRepository.findQualifyingPositions(affected, 5L)).thenReturn(emptyList())

        orchestrator.analyzeAffectedPositions(affected)

        verify(positionRepository, times(1)).findQualifyingPositions(affected, 5L)
        verify(engineAnalysisService, never()).analyzePosition(any())
    }

    @Test
    fun `one position failing does not prevent other positions from being analyzed`() {
        val pos1 = Position(id = UUID.randomUUID(), hash = "h1", fen = "f1")
        val pos2 = Position(id = UUID.randomUUID(), hash = "h2", fen = "f2")
        val affected = setOf(pos1.id, pos2.id)

        whenever(positionRepository.findQualifyingPositions(affected, 5L)).thenReturn(listOf(pos1, pos2))
        doThrow(RuntimeException("Stockfish error for pos1")).whenever(engineAnalysisService).analyzePosition(pos1)

        orchestrator.analyzeAffectedPositions(affected)

        // pos1 failed, but pos2 must still be analyzed
        verify(engineAnalysisService, times(1)).analyzePosition(pos1)
        verify(engineAnalysisService, times(1)).analyzePosition(pos2)
    }

    @Test
    fun `returns early when affected positions is empty`() {
        orchestrator.analyzeAffectedPositions(emptySet())

        verify(positionRepository, never()).findQualifyingPositions(any(), any())
        verify(engineAnalysisService, never()).analyzePosition(any())
    }

    @Test
    fun `analysis orchestration accepts a nullable per-job MultiPV override`() {
        val position = Position(id = UUID.randomUUID(), hash = "h1", fen = "f1")
        whenever(positionRepository.findQualifyingPositions(setOf(position.id), 5L)).thenReturn(listOf(position))
        val analyzeWithOverride =
            requireNotNull(
                EngineAnalysisOrchestrator::class.memberFunctions.singleOrNull {
                    it.name == "analyzeAffectedPositions" && it.parameters.size == 3
                },
            ) {
                "analyzeAffectedPositions must accept the persisted MultiPV override"
            }
        val positionAnalysis =
            requireNotNull(
                EngineAnalysisService::class.memberFunctions.singleOrNull {
                    it.name == "analyzePosition" && it.parameters.size == 3
                },
            ) {
                "analyzePosition must accept an optional per-call MultiPV override"
            }

        analyzeWithOverride.call(orchestrator, setOf(position.id), 1)

        positionAnalysis.call(verify(engineAnalysisService), position, 1)
    }
}
