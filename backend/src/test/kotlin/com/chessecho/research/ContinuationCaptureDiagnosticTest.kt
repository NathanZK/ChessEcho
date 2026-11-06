package com.chessecho.research

import com.github.bhlangonijr.chesslib.Board
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContinuationCaptureDiagnosticTest {

    private val evaluator = ContinuationCaptureDiagnosticEvaluator()

    @Test
    fun `synthetic immediate capture at ply 0 verifies 100 percent integrity match`() {
        // Setup: White Knight on e3, Black Queen on d5.
        // FEN: White to move, Knight can immediately capture Queen on d5.
        // 8/8/8/3q4/8/4N3/8/4K2k w - - 0 1
        // Move: e3d5 (N x Q on d5)
        val fen = "8/8/8/3q4/8/4N3/8/4K2k w - - 0 1"
        val pvMoves = listOf("e3d5", "h1g1")

        val result = evaluator.evaluateSinglePosition(
            fen = fen,
            pvMoves = pvMoves
        )

        val nxq = result.captureFamilyBreakdown["N_x_Q"]
        kotlin.test.assertNotNull(nxq)

        assertEquals(1, nxq.immediatePly0.totalEvents, "Must find 1 immediate N x Q event")
        assertEquals(1, nxq.immediatePly0.matchedEvents, "Must match root static attack term")
        assertEquals(1.0, nxq.immediatePly0.eventMatchRate, 1e-9)
        assertEquals(1, nxq.immediatePly0.uniquePositions)
        assertEquals(1, nxq.immediatePly0.matchedPositions)
        assertEquals(1.0, nxq.immediatePly0.positionMatchRate, 1e-9)

        assertEquals(0, nxq.preparatoryPlyK.totalEvents)
        assertEquals(0.0, nxq.preparatoryPlyK.eventMatchRate, 1e-9)
    }

    @Test
    fun `synthetic preparatory deflection at ply 2 verifies preparatory stratum and root-player tracking`() {
        // Setup:
        // Ply 0: White plays deflection move
        // Ply 1: Black responds
        // Ply 2: White Knight captures Black Queen
        // FEN: White King e1, White Rook a1, White Knight b1; Black King e8, Black Queen a5, Black Pawn b7.
        // Let's create a clear sequence:
        // Root FEN: White to move.
        // Move 0: a1a5 (R x Q at ply 0 - R_x_Q)
        // Or for N x Q at ply 2:
        // Initial: White Knight on f3, Black Queen on c7, Black Bishop on d6.
        // White moves 1. e4 (ply 0), Black plays 1... Qd8 (ply 1), White plays 2. Nxe5...
        // Let's use simple moves on a board:
        val board = Board()
        board.loadFromFen("r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 4 4")
        // White plays d2d3 (ply 0), Black plays d7d6 (ply 1), White plays c3d5 (ply 2 - Knight moves)
        // In our synthetic test, let's supply moves directly where move 2 is N x Q:
        // Position: White King e1, White Knight f3. Black King e8, Black Queen d8.
        // Ply 0: f3g5 (White Knight moves)
        // Ply 1: h7h6 (Black moves)
        // Ply 2: g5e6 or similar where Knight captures Queen:
        // Suppose Black Queen is on e7: Knight on g5 captures Queen on e7 at ply 2!
        val fen = "4k3/4q3/8/8/8/5N2/8/7K w - - 0 1"
        // At root: Knight is on f3, Queen is on e7.
        // Does Knight on f3 attack e7? No!
        // Move 0: f3g5 (White Knight to g5)
        // Move 1: e8f8 (Black King to f8)
        // Move 2: g5e7 (White Knight captures Queen on e7)
        val pvMoves = listOf("f3g5", "e8f8", "g5e7")

        val result = evaluator.evaluateSinglePosition(
            fen = fen,
            pvMoves = pvMoves
        )

        val nxq = result.captureFamilyBreakdown["N_x_Q"]
        kotlin.test.assertNotNull(nxq)

        assertEquals(0, nxq.immediatePly0.totalEvents, "No immediate N x Q at ply 0")
        assertEquals(1, nxq.preparatoryPlyK.totalEvents, "1 preparatory N x Q event at ply 2")
        assertEquals(0, nxq.preparatoryPlyK.matchedEvents, "Should not match root term since f3 did not attack e7")
        assertEquals(0.0, nxq.preparatoryPlyK.eventMatchRate, 1e-9)
        assertEquals(1, nxq.preparatoryPlyK.uniquePositions)
        assertEquals(0, nxq.preparatoryPlyK.matchedPositions)
        assertEquals(0.0, nxq.preparatoryPlyK.positionMatchRate, 1e-9)

        // Root player tracking: ply 2 is White's move (root side is White), so rootPlayerEvents == 1
        assertEquals(1, nxq.preparatoryPlyK.rootPlayerEvents)
        assertEquals(0, nxq.preparatoryPlyK.matchedRootPlayerEvents)
        assertEquals(0.0, nxq.preparatoryPlyK.rootPlayerEventMatchRate, 1e-9)
        assertEquals(2.0, nxq.preparatoryPlyK.meanPlyOfCapture, 1e-9)
    }

    @Test
    fun `safe zero handling when position contains no captures`() {
        val fen = "8/8/8/8/8/8/8/k6K w - - 0 1"
        val pvMoves = listOf("h1g1", "k8g8")

        val result = evaluator.evaluateSinglePosition(
            fen = fen,
            pvMoves = pvMoves
        )

        val nxq = result.captureFamilyBreakdown["N_x_Q"]
        kotlin.test.assertNotNull(nxq)

        assertEquals(0, nxq.immediatePly0.totalEvents)
        assertEquals(0.0, nxq.immediatePly0.eventMatchRate, 1e-9)
        assertEquals(0.0, nxq.immediatePly0.positionMatchRate, 1e-9)

        assertEquals(0, nxq.preparatoryPlyK.totalEvents)
        assertEquals(0.0, nxq.preparatoryPlyK.eventMatchRate, 1e-9)
        assertEquals(0.0, nxq.preparatoryPlyK.positionMatchRate, 1e-9)
        assertEquals(0.0, nxq.preparatoryPlyK.rootPlayerEventMatchRate, 1e-9)
        assertEquals(0.0, nxq.preparatoryPlyK.meanPlyOfCapture, 1e-9)
    }
}
