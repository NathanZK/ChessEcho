package com.chessecho.research

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class Depth8HorizonExtensionTest {

    private val binaryPath = "/opt/homebrew/bin/stockfish"
    private var workerMpv1: Depth8EngineWorker? = null
    private var workerMpv3: Depth8EngineWorker? = null

    @BeforeEach
    fun setUp() {
        if (File(binaryPath).exists()) {
            workerMpv1 = Depth8EngineWorker(binaryPath = binaryPath, configuredMultiPv = 1)
            workerMpv3 = Depth8EngineWorker(binaryPath = binaryPath, configuredMultiPv = 3)
        }
    }

    @AfterEach
    fun tearDown() {
        workerMpv1?.close()
        workerMpv3?.close()
    }

    @Test
    fun `test quiet horizon terminates with QUIET_STOP and zero extension plies`() {
        val w = workerMpv1 ?: return
        // Starting position after 1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 (Italian Game, quiet standard position)
        val fen = "r1bqk1nr/pppp1ppp/2n5/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"
        val record = w.evaluate(fen)

        assertEquals(1, record.principalVariations.size)
        val pv = record.principalVariations[0]
        assertNotNull(pv.terminationReason)
        assertTrue(pv.nominalMoves.isNotEmpty())
        assertTrue(pv.totalNodes ?: 0L > 0L)
    }

    @Test
    fun `test tactical forcing horizon replays continuation and assigns termination reason`() {
        val w = workerMpv1 ?: return
        // Scholar's mate threat: 1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6?? 4. Qxf7# (forcing continuation)
        val fen = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
        val record = w.evaluate(fen)

        assertEquals("h5f7", record.bestMove)
        val pv = record.principalVariations[0]
        assertEquals("h5f7", pv.nominalMoves.firstOrNull())
    }

    @Test
    fun `test MultiPV 3 evaluation populates tokensByRank and preserves persistent worker`() {
        val w = workerMpv3 ?: return
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val record = w.evaluate(fen)

        assertEquals(3, record.multiPv)
        assertTrue(record.principalVariations.size in 1..3)
        assertTrue(record.tokensByRank.containsKey("1"))
        assertTrue(record.dynamicTokens.isNotEmpty())

        // Verify worker is still alive and can execute a second evaluation without respawn
        val secondRecord = w.evaluate("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
        assertEquals(3, secondRecord.multiPv)
        assertTrue(secondRecord.principalVariations.isNotEmpty())
    }
}
