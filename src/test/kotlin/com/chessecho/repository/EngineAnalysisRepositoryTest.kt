package com.chessecho.repository

import com.chessecho.domain.EngineAnalysis
import com.chessecho.domain.MoveEvaluation
import com.chessecho.domain.Position
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@DataJpaTest
@ActiveProfiles("test")
class EngineAnalysisRepositoryTest {
    @Autowired
    private lateinit var engineAnalysisRepository: EngineAnalysisRepository

    @Autowired
    private lateinit var positionRepository: PositionRepository

    @Test
    fun `should persist and hydrate winning forced-mate columns in EngineAnalysis and MoveEvaluation`() {
        val position = positionRepository.save(Position(hash = "hash1", fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"))

        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = null,
                baselineEvalMate = 2,
                bestMove = "Qh5",
                bestMoveEvalCp = null,
                bestMoveEvalMate = 2,
                analyzedAt = Instant.now(),
            )
        val moveEval =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "Qh5",
                evalCp = null,
                evalMate = 2,
                evalLossFromBest = 0.0,
            )
        analysis.moveEvaluations.add(moveEval)

        val saved = engineAnalysisRepository.saveAndFlush(analysis)
        assertNotNull(saved.id)

        val hydrated = engineAnalysisRepository.findByPositionIdWithMoveEvaluations(position.id)
        assertNotNull(hydrated)
        assertEquals(2, hydrated?.baselineEvalMate)
        assertNull(hydrated?.baselineEvalCp)
        assertEquals(2, hydrated?.bestMoveEvalMate)
        assertNull(hydrated?.bestMoveEvalCp)

        val hydratedMoves = hydrated?.moveEvaluations
        assertEquals(1, hydratedMoves?.size)
        assertEquals("Qh5", hydratedMoves?.first()?.move)
        assertEquals(2, hydratedMoves?.first()?.evalMate)
        assertNull(hydratedMoves?.first()?.evalCp)
        assertEquals(0.0, hydratedMoves?.first()?.evalLossFromBest)
    }

    @Test
    fun `should persist and hydrate losing forced-mate and nullable eval_loss_from_best`() {
        val position = positionRepository.save(Position(hash = "hash2", fen = "8/8/8/8/8/8/8/8 w - - 0 1"))
        val analysis =
            EngineAnalysis(
                position = position,
                depth = 16,
                baselineEvalCp = null,
                baselineEvalMate = -3,
                bestMove = "e4",
                bestMoveEvalCp = null,
                bestMoveEvalMate = -3,
            )
        val moveEval =
            MoveEvaluation(
                engineAnalysis = analysis,
                move = "d4",
                evalCp = null,
                evalMate = -1,
                evalLossFromBest = null,
            )
        analysis.moveEvaluations.add(moveEval)

        val saved = engineAnalysisRepository.saveAndFlush(analysis)
        assertNotNull(saved.id)

        val reloaded = engineAnalysisRepository.findByIdWithMoveEvaluations(saved.id)
        assertNotNull(reloaded)
        assertEquals(-3, reloaded?.baselineEvalMate)
        assertNull(reloaded?.baselineEvalCp)
        assertEquals(-3, reloaded?.bestMoveEvalMate)
        assertNull(reloaded?.bestMoveEvalCp)

        val move = reloaded?.moveEvaluations?.find { it.move == "d4" }
        assertNotNull(move)
        assertEquals(-1, move?.evalMate)
        assertNull(move?.evalCp)
        assertNull(move?.evalLossFromBest)
    }
}
