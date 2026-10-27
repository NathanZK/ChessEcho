package com.chessecho.service.continuation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ContinuationCandidateModelTest {
    @Test
    fun `TerminationReason enum contains all specified values`() {
        val expected =
            setOf(
                "QUIET_STOP",
                "TERMINAL",
                "PV_EXHAUSTED",
                "PLY_LIMIT",
                "NODE_LIMIT",
                "TIMEOUT",
                "ENGINE_ERROR",
            )
        val actual = TerminationReason.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `ContinuationCandidate defaults continuation fields cleanly`() {
        val candidate =
            ContinuationCandidate(
                move = "Nf3",
                resultingFen = "rnbqkb1r/pppppppp/5n2/8/8/5N2/PPPPPPPP/RNBQKB1R w KQkq - 1 2",
                providerType = "STOCKFISH",
            )

        assertEquals("Nf3", candidate.move)
        assertEquals("rnbqkb1r/pppppppp/5n2/8/8/5N2/PPPPPPPP/RNBQKB1R w KQkq - 1 2", candidate.resultingFen)
        assertEquals(emptyList<String>(), candidate.continuationMoves)
        assertEquals(emptyList<String>(), candidate.continuationSanMoves)
        assertEquals(candidate.resultingFen, candidate.terminalFen)
        assertEquals(0, candidate.pliesResolved)
        assertFalse(candidate.isTerminal)
        assertNull(candidate.terminationReason)
    }

    @Test
    fun `ContinuationCandidate retains explicit continuation fields`() {
        val candidate =
            ContinuationCandidate(
                move = "Bxc6",
                resultingFen = "r1bqkbnr/1ppp1ppp/p1B5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 4",
                providerType = "STOCKFISH",
                evalCp = 25,
                evalLoss = 0.05,
                timesPlayed = null,
                continuationMoves = listOf("d7c6"),
                continuationSanMoves = listOf("dxc6"),
                terminalFen = "r1bqkbnr/1pp2ppp/p1p5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 5",
                pliesResolved = 1,
                isTerminal = false,
                terminationReason = TerminationReason.QUIET_STOP,
            )

        assertEquals(listOf("d7c6"), candidate.continuationMoves)
        assertEquals(listOf("dxc6"), candidate.continuationSanMoves)
        assertEquals("r1bqkbnr/1pp2ppp/p1p5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 5", candidate.terminalFen)
        assertEquals(1, candidate.pliesResolved)
        assertFalse(candidate.isTerminal)
        assertEquals(TerminationReason.QUIET_STOP, candidate.terminationReason)
    }
}
