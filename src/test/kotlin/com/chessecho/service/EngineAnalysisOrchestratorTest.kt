package com.chessecho.service

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
        val posId1 = UUID.randomUUID()
        val posId2 = UUID.randomUUID()
        val posId3 = UUID.randomUUID()
        val affected = setOf(posId1, posId2, posId3)

        // Only posId1 and posId2 have >= 5 occurrences
        whenever(positionRepository.findQualifyingPositionIds(affected, 5L)).thenReturn(listOf(posId1, posId2))

        orchestrator.analyzeAffectedPositions(affected)

        // Batch query called once
        verify(positionRepository, times(1)).findQualifyingPositionIds(affected, 5L)
        // Position analysis triggered only for qualifying positions
        verify(engineAnalysisService, times(1)).analyzePosition(posId1)
        verify(engineAnalysisService, times(1)).analyzePosition(posId2)
        verify(engineAnalysisService, never()).analyzePosition(posId3)
    }

    @Test
    fun `one position failing does not prevent other positions from being analyzed`() {
        val posId1 = UUID.randomUUID()
        val posId2 = UUID.randomUUID()
        val affected = setOf(posId1, posId2)

        whenever(positionRepository.findQualifyingPositionIds(affected, 5L)).thenReturn(listOf(posId1, posId2))
        doThrow(RuntimeException("Stockfish error for posId1")).whenever(engineAnalysisService).analyzePosition(posId1)

        orchestrator.analyzeAffectedPositions(affected)

        // posId1 failed, but posId2 must still be analyzed
        verify(engineAnalysisService, times(1)).analyzePosition(posId1)
        verify(engineAnalysisService, times(1)).analyzePosition(posId2)
    }

    @Test
    fun `returns early when affected positions is empty`() {
        orchestrator.analyzeAffectedPositions(emptySet())

        verify(positionRepository, never()).findQualifyingPositionIds(any(), any())
        verify(engineAnalysisService, never()).analyzePosition(any())
    }
}
