package com.chessecho.domain

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class DomainValidationTest {
    private val dummyPosition = Position(hash = "dummy-hash", fen = "8/8/8/8/8/8/8/8 w - - 0 1")

    @Test
    fun `EngineAnalysis constructor rejects baselineEvalMate = 0`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                EngineAnalysis(
                    position = dummyPosition,
                    depth = 16,
                    baselineEvalCp = null,
                    baselineEvalMate = 0,
                    bestMove = "e4",
                    bestMoveEvalCp = null,
                    bestMoveEvalMate = 1,
                    analyzedAt = Instant.now(),
                )
            }
        assertEquals("baselineEvalMate cannot be 0", ex.message)
    }

    @Test
    fun `EngineAnalysis constructor rejects bestMoveEvalMate = 0`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                EngineAnalysis(
                    position = dummyPosition,
                    depth = 16,
                    baselineEvalCp = null,
                    baselineEvalMate = 1,
                    bestMove = "e4",
                    bestMoveEvalCp = null,
                    bestMoveEvalMate = 0,
                    analyzedAt = Instant.now(),
                )
            }
        assertEquals("bestMoveEvalMate cannot be 0", ex.message)
    }

    @Test
    fun `EngineAnalysis constructor rejects simultaneous cp and mate for baseline`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                EngineAnalysis(
                    position = dummyPosition,
                    depth = 16,
                    baselineEvalCp = 100,
                    baselineEvalMate = 1,
                    bestMove = "e4",
                    bestMoveEvalCp = null,
                    bestMoveEvalMate = 1,
                    analyzedAt = Instant.now(),
                )
            }
        assertEquals("baselineEvalCp and baselineEvalMate are mutually exclusive", ex.message)
    }

    @Test
    fun `EngineAnalysis constructor rejects simultaneous cp and mate for bestMove`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                EngineAnalysis(
                    position = dummyPosition,
                    depth = 16,
                    baselineEvalCp = null,
                    baselineEvalMate = 1,
                    bestMove = "e4",
                    bestMoveEvalCp = 100,
                    bestMoveEvalMate = 1,
                    analyzedAt = Instant.now(),
                )
            }
        assertEquals("bestMoveEvalCp and bestMoveEvalMate are mutually exclusive", ex.message)
    }

    @Test
    fun `MoveEvaluation constructor rejects evalMate = 0`() {
        val analysis =
            EngineAnalysis(
                position = dummyPosition,
                depth = 16,
                baselineEvalCp = null,
                baselineEvalMate = 1,
                bestMove = "e4",
                bestMoveEvalCp = null,
                bestMoveEvalMate = 1,
            )
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                MoveEvaluation(
                    engineAnalysis = analysis,
                    move = "e4",
                    evalCp = null,
                    evalMate = 0,
                    evalLossFromBest = 0.0,
                )
            }
        assertEquals("evalMate cannot be 0", ex.message)
    }

    @Test
    fun `MoveEvaluation constructor rejects simultaneous cp and mate`() {
        val analysis =
            EngineAnalysis(
                position = dummyPosition,
                depth = 16,
                baselineEvalCp = 100,
                bestMove = "e4",
                bestMoveEvalCp = 100,
            )
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                MoveEvaluation(
                    engineAnalysis = analysis,
                    move = "e4",
                    evalCp = 100,
                    evalMate = 1,
                    evalLossFromBest = 0.0,
                )
            }
        assertEquals("evalCp and evalMate are mutually exclusive", ex.message)
    }

    @Test
    fun `constructors accept valid mate-only or cp-only evaluations`() {
        assertDoesNotThrow {
            val mateAnalysis =
                EngineAnalysis(
                    position = dummyPosition,
                    depth = 16,
                    baselineEvalCp = null,
                    baselineEvalMate = -3,
                    bestMove = "e4",
                    bestMoveEvalCp = null,
                    bestMoveEvalMate = -3,
                )
            MoveEvaluation(
                engineAnalysis = mateAnalysis,
                move = "e4",
                evalCp = null,
                evalMate = -3,
                evalLossFromBest = 0.0,
            )
        }

        assertDoesNotThrow {
            val cpAnalysis =
                EngineAnalysis(
                    position = dummyPosition,
                    depth = 16,
                    baselineEvalCp = 150,
                    baselineEvalMate = null,
                    bestMove = "e4",
                    bestMoveEvalCp = 150,
                    bestMoveEvalMate = null,
                )
            MoveEvaluation(
                engineAnalysis = cpAnalysis,
                move = "e4",
                evalCp = 150,
                evalMate = null,
                evalLossFromBest = 0.0,
            )
        }
    }
}
