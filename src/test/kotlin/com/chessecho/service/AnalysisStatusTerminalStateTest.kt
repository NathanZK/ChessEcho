package com.chessecho.service

import com.chessecho.domain.Position
import com.chessecho.repository.PositionRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisStatusTerminalStateTest {
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
    fun `all qualifying positions produce a successful aggregate outcome`() {
        val first = Position(id = UUID.randomUUID(), hash = "first", fen = "first")
        val second = Position(id = UUID.randomUUID(), hash = "second", fen = "second")
        whenever(positionRepository.findQualifyingPositions(setOf(first.id, second.id), 5L))
            .thenReturn(listOf(first, second))

        val outcome = invokeAnalysisAndRequireOutcome(setOf(first.id, second.id))

        verify(engineAnalysisService).analyzePosition(first)
        verify(engineAnalysisService).analyzePosition(second)
        assertFalse(outcome.failedPositionIds.isNotEmpty(), "Successful analysis must not report failed positions")
    }

    @Test
    fun `per-position failure is aggregated while later qualifying positions continue`() {
        val failing = Position(id = UUID.randomUUID(), hash = "failing", fen = "failing")
        val succeeding = Position(id = UUID.randomUUID(), hash = "succeeding", fen = "succeeding")
        whenever(positionRepository.findQualifyingPositions(setOf(failing.id, succeeding.id), 5L))
            .thenReturn(listOf(failing, succeeding))
        doThrow(IllegalStateException("engine failed")).whenever(engineAnalysisService).analyzePosition(failing)

        val outcome = invokeAnalysisAndRequireOutcome(setOf(failing.id, succeeding.id))

        verify(engineAnalysisService).analyzePosition(failing)
        verify(engineAnalysisService).analyzePosition(succeeding)
        assertEquals(setOf(failing.id), outcome.failedPositionIds)
    }

    @Test
    fun `zero qualifying positions are a successful no-work aggregate outcome`() {
        val affected = UUID.randomUUID()
        whenever(positionRepository.findQualifyingPositions(setOf(affected), 5L)).thenReturn(emptyList())

        val outcome = invokeAnalysisAndRequireOutcome(setOf(affected))

        assertTrue(outcome.failedPositionIds.isEmpty(), "No qualifying positions is successful no-work")
    }

    private fun invokeAnalysisAndRequireOutcome(affectedPositionIds: Set<UUID>): AnalysisOutcomeContract {
        val method =
            EngineAnalysisOrchestrator::class.members
                .filterIsInstance<KFunction<*>>()
                .single { it.name == "analyzeAffectedPositions" && it.parameters.size == 2 }
        val result = method.call(orchestrator, affectedPositionIds)
        val outcomeType =
            method.returnType.classifier as? KClass<*>
                ?: error("EngineAnalysisOrchestrator must return an aggregate analysis outcome, not ${method.returnType}")
        check(outcomeType.simpleName != "Unit") {
            "EngineAnalysisOrchestrator must return an aggregate analysis outcome with failed position IDs"
        }

        val failedPositionIds =
            outcomeType.memberProperties.singleOrNull { it.name == "failedPositionIds" }
                ?: error("Aggregate analysis outcome must expose failedPositionIds")

        @Suppress("UNCHECKED_CAST")
        return AnalysisOutcomeContract(failedPositionIds.getter.call(result) as Set<UUID>)
    }

    private data class AnalysisOutcomeContract(
        val failedPositionIds: Set<UUID>,
    )
}
