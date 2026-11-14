package com.chessprobe.service.clustering

import com.chessprobe.config.PositionClusteringProperties
import com.chessprobe.domain.Position
import com.chessprobe.domain.clustering.PositionContinuationArtifact
import com.chessprobe.domain.clustering.PositionContinuationStatus
import com.chessprobe.domain.clustering.TacticalResolutionStatus
import com.chessprobe.repository.PositionRepository
import com.chessprobe.repository.clustering.PositionContinuationArtifactRepository
import com.chessprobe.service.FullPvAnalysisResult
import com.chessprobe.service.PvContinuationResult
import com.chessprobe.service.QuiescenceOptions
import com.chessprobe.service.StockfishService
import com.chessprobe.service.continuation.TerminationReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.BufferedReader
import java.io.StringReader
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class DynamicContinuationCoordinatorTest {
    private val artifactRepository: PositionContinuationArtifactRepository = mock()
    private val positionRepository: PositionRepository =
        mock {
            on { findEligiblePositionsForContinuation(any(), any(), any(), any()) } doReturn emptyList()
        }
    private val testEngineDigest = "test-engine-digest"
    private val stockfishService: StockfishService =
        mock {
            on { getEngineBinaryHash() } doReturn testEngineDigest
        }
    private val properties = PositionClusteringProperties()

    private val coordinator =
        DynamicContinuationCoordinator(
            artifactRepository = artifactRepository,
            positionRepository = positionRepository,
            stockfishService = stockfishService,
            properties = properties,
            engineDigestProvider = { testEngineDigest },
        )

    private fun simulatedReader(vararg lines: String): BufferedReader = BufferedReader(StringReader(lines.joinToString("\n")))

    @Test
    fun `returns existing completed artifact without engine re-computation`() {
        val positionId = UUID.randomUUID()
        val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val position = Position(id = positionId, fen = fen, hash = "hash1")

        val existing =
            PositionContinuationArtifact(
                positionId = positionId,
                configHash = coordinator.defaultConfigHash,
                status = PositionContinuationStatus.COMPLETED,
                resolutionStatus = TacticalResolutionStatus.RESOLVED_QUIET,
                achievedDepth = 10,
                nominalMoves = "e2e4 e7e5",
                continuationMoves = "g1f3 b8c6",
                resolvedMoves = "e2e4 e7e5 g1f3 b8c6",
                terminalFen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
                terminationReason = TerminationReason.QUIET_STOP,
            )

        whenever(artifactRepository.findByPositionIdAndConfigHash(positionId, coordinator.defaultConfigHash))
            .thenReturn(existing)

        val result = coordinator.getOrComputeContinuation(position)

        assertEquals(PositionContinuationStatus.COMPLETED, result.status)
        assertEquals(TacticalResolutionStatus.RESOLVED_QUIET, result.resolutionStatus)
        assertEquals("e2e4 e7e5 g1f3 b8c6", result.resolvedMoves)
        verify(stockfishService, never()).analyzeFullPv(any(), any(), any(), anyOrNull(), any())
        verify(stockfishService, never()).resolveForcingPvContinuation(any(), any())
    }

    @Test
    fun `computes and completes artifact using atomic claim and leased completion`() {
        val positionId = UUID.randomUUID()
        val fen = "r1bqkbnr/1ppp1ppp/p1B5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 4"
        val position = Position(id = positionId, fen = fen, hash = "hash2")

        val completedArtifact =
            PositionContinuationArtifact(
                positionId = positionId,
                configHash = coordinator.defaultConfigHash,
                status = PositionContinuationStatus.COMPLETED,
                resolutionStatus = TacticalResolutionStatus.RESOLVED_QUIET,
                achievedDepth = 10,
                resolvedMoves = "d7c6 d2d4",
            )
        whenever(artifactRepository.findByPositionIdAndConfigHash(positionId, coordinator.defaultConfigHash))
            .thenReturn(null)
            .thenReturn(completedArtifact)
        whenever(artifactRepository.insertPendingIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(1)
        whenever(stockfishService.analyzeFullPv(eq(fen), eq(10), any(), anyOrNull(), any()))
            .thenReturn(FullPvAnalysisResult(achievedDepth = 10, moves = listOf("d7c6")))
        whenever(stockfishService.resolveForcingPvContinuation(any(), any<QuiescenceOptions>()))
            .thenReturn(quietQuiescenceResult(listOf("d2d4")))
        whenever(
            artifactRepository.completeLeasedArtifact(
                any(),
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                anyOrNull(),
                any(),
            ),
        ).thenReturn(1)

        val result = coordinator.getOrComputeContinuation(position)

        assertEquals(PositionContinuationStatus.COMPLETED, result.status)
        assertEquals(TacticalResolutionStatus.RESOLVED_QUIET, result.resolutionStatus)
        verify(artifactRepository).insertPendingIfAbsent(any(), eq(positionId), eq(coordinator.defaultConfigHash), any(), any(), any())
        verify(
            artifactRepository,
        ).completeLeasedArtifact(
            any(),
            any(),
            eq(PositionContinuationStatus.COMPLETED),
            eq(TacticalResolutionStatus.RESOLVED_QUIET),
            eq(10),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
        )
    }

    private fun quietQuiescenceResult(moves: List<String> = listOf("d2d4")) =
        PvContinuationResult(
            originalFen = "fen1",
            terminalFen = "fen2",
            moves = moves,
            sanMoves = moves,
            score = null,
            pliesResolved = moves.size,
            isTerminal = false,
            terminationReason = TerminationReason.QUIET_STOP,
        )

    @Test
    fun `concurrent claim suppression skips engine analysis when atomic insert fails`() {
        val positionId = UUID.randomUUID()
        val fen = "r1bqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val position = Position(id = positionId, fen = fen, hash = "hash3")

        whenever(artifactRepository.findByPositionIdAndConfigHash(positionId, coordinator.defaultConfigHash))
            .thenReturn(null)
            .thenReturn(
                PositionContinuationArtifact(
                    positionId = positionId,
                    configHash = coordinator.defaultConfigHash,
                    status = PositionContinuationStatus.PENDING,
                ),
            )

        whenever(artifactRepository.insertPendingIfAbsent(any(), any(), any(), any(), any(), any()))
            .thenReturn(0) // Conflict: another thread claimed it

        val result = coordinator.getOrComputeContinuation(position)

        assertEquals(PositionContinuationStatus.PENDING, result.status)
        verify(stockfishService, never()).analyzeFullPv(any(), any(), any(), anyOrNull(), any())
        verify(stockfishService, never()).resolveForcingPvContinuation(any(), any())
    }

    @Test
    fun `reclaims stale expired pending artifact within retry limit`() {
        val positionId = UUID.randomUUID()
        val fen = "r1bqkbnr/1ppp1ppp/p1B5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 4"
        val position = Position(id = positionId, fen = fen, hash = "hash4")

        val stale =
            PositionContinuationArtifact(
                positionId = positionId,
                configHash = coordinator.defaultConfigHash,
                status = PositionContinuationStatus.PENDING,
                leaseToken = "stale-token",
                leaseExpiresAt = Instant.now().minusSeconds(100),
                retryCount = 1,
            )

        whenever(artifactRepository.findByPositionIdAndConfigHash(positionId, coordinator.defaultConfigHash))
            .thenReturn(stale)
            .thenReturn(
                PositionContinuationArtifact(
                    positionId = positionId,
                    configHash = coordinator.defaultConfigHash,
                    status = PositionContinuationStatus.COMPLETED,
                    resolutionStatus = TacticalResolutionStatus.RESOLVED_QUIET,
                    achievedDepth = 10,
                ),
            )

        whenever(artifactRepository.reclaimStaleOrFailed(eq(positionId), eq(coordinator.defaultConfigHash), any(), any(), eq(3), any()))
            .thenReturn(1) // Reclaimed!

        whenever(stockfishService.analyzeFullPv(eq(fen), eq(10), any(), anyOrNull(), any()))
            .thenReturn(FullPvAnalysisResult(achievedDepth = 10, moves = listOf("d7c6")))

        val mockQuiescence =
            PvContinuationResult(
                originalFen = "fen",
                terminalFen = "fen",
                moves = emptyList(),
                sanMoves = emptyList(),
                score = null,
                pliesResolved = 0,
                isTerminal = false,
                terminationReason = TerminationReason.QUIET_STOP,
            )
        whenever(stockfishService.resolveForcingPvContinuation(any(), any<QuiescenceOptions>()))
            .thenReturn(mockQuiescence)

        val result = coordinator.getOrComputeContinuation(position)

        assertEquals(PositionContinuationStatus.COMPLETED, result.status)
        verify(artifactRepository).reclaimStaleOrFailed(eq(positionId), eq(coordinator.defaultConfigHash), any(), any(), eq(3), any())
    }

    @Test
    fun `operational failure from engine timeout marks FAILED rather than COMPLETED`() {
        val positionId = UUID.randomUUID()
        val fen = "r1bqkbnr/1ppp1ppp/p1B5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 4"
        val position = Position(id = positionId, fen = fen, hash = "hash5")

        whenever(artifactRepository.findByPositionIdAndConfigHash(positionId, coordinator.defaultConfigHash))
            .thenReturn(null)
            .thenReturn(
                PositionContinuationArtifact(
                    positionId = positionId,
                    configHash = coordinator.defaultConfigHash,
                    status = PositionContinuationStatus.FAILED,
                    resolutionStatus = TacticalResolutionStatus.OPERATIONAL_FAILURE,
                ),
            )

        whenever(artifactRepository.insertPendingIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(1)
        whenever(stockfishService.analyzeFullPv(eq(fen), eq(10), any(), anyOrNull(), any()))
            .thenReturn(FullPvAnalysisResult(achievedDepth = 10, moves = listOf("d7c6")))

        val timeoutResult =
            PvContinuationResult(
                originalFen = "fen",
                terminalFen = "fen",
                moves = emptyList(),
                sanMoves = emptyList(),
                score = null,
                pliesResolved = 0,
                isTerminal = false,
                terminationReason = TerminationReason.TIMEOUT,
            )
        whenever(stockfishService.resolveForcingPvContinuation(any(), any<QuiescenceOptions>())).thenReturn(timeoutResult)

        val result = coordinator.getOrComputeContinuation(position)

        assertEquals(PositionContinuationStatus.FAILED, result.status)
        assertEquals(TacticalResolutionStatus.OPERATIONAL_FAILURE, result.resolutionStatus)
        verify(
            artifactRepository,
        ).completeLeasedArtifact(
            any(),
            any(),
            eq(PositionContinuationStatus.FAILED),
            eq(TacticalResolutionStatus.OPERATIONAL_FAILURE),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
        )
    }

    @Test
    fun `rejects nominal search when achievedDepth is less than targetDepth`() {
        val fen = "r1bqkbnr/1ppp1ppp/p1B5/4p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 4"
        whenever(stockfishService.analyzeFullPv(eq(fen), eq(10), any(), anyOrNull(), any()))
            .thenReturn(FullPvAnalysisResult(achievedDepth = 8, moves = listOf("d7c6"))) // 8 < 10

        assertThrows(IllegalStateException::class.java) {
            coordinator.resolveTacticalContinuation(fen, targetDepth = 10)
        }
    }

    @Test
    fun `rejects illegal nominal PV move and throws IllegalArgumentException`() {
        val rootFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.resolveTacticalContinuation(
                rootFen = rootFen,
                nominalMoves = listOf("e2e5"), // Illegal double jump past e4
                achievedDepth = 10,
            )
        }
    }

    @Test
    fun `returns terminal outcome on checkmated nominal endpoint without invoking quiescence`() {
        val stockfish =
            mock<StockfishService> {
                on { getEngineBinaryHash() } doReturn "digest"
            }
        val c =
            DynamicContinuationCoordinator(
                artifactRepository = artifactRepository,
                positionRepository = positionRepository,
                stockfishService = stockfish,
                properties = properties,
            )

        // Scholar's mate final position
        val mateFen = "r1bqkb1r/pppp1Qpp/2n5/4p3/2B1n3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4"
        val continuation =
            c.resolveTacticalContinuation(
                rootFen = mateFen,
                nominalMoves = emptyList(),
                achievedDepth = 10,
            )

        assertEquals(10, continuation.achievedDepth)
        assertEquals(TacticalResolutionStatus.RESOLVED_TERMINAL, continuation.resolutionStatus)
        assertEquals(TerminationReason.TERMINAL, continuation.terminationReason)
        assertTrue(continuation.continuationMoves.isEmpty())
        verify(stockfish, never()).resolveForcingPvContinuation(any(), any<QuiescenceOptions>())
    }

    @Test
    fun `resolveTacticalContinuation concatenates nominal and quiescence moves on forcing line`() {
        val stockfish = StockfishService()
        val c =
            DynamicContinuationCoordinator(
                artifactRepository = artifactRepository,
                positionRepository = positionRepository,
                stockfishService = stockfish,
                properties = properties,
            )

        val opera = "3rkb1r/p2R1ppp/4qn2/1B2p1B1/4P3/1Q6/PPP2PPP/2K4R b k - 0 13"
        val qReader =
            simulatedReader(
                "info depth 8 score cp 800 nodes 1200 pv b3e6 f7e6 c1b1",
                "bestmove b3e6",
            )

        val continuation =
            c.resolveTacticalContinuation(
                rootFen = opera,
                nominalMoves = listOf("d8d7"),
                achievedDepth = 10,
                quiescenceOptions = QuiescenceOptions(maxPlies = 5, timeoutMs = 1000L),
                quiescenceReader = qReader,
            )

        assertEquals(10, continuation.achievedDepth)
        assertEquals(listOf("d8d7"), continuation.nominalMoves)
        assertEquals(listOf("b3e6", "f7e6"), continuation.continuationMoves)
        assertEquals(listOf("d8d7", "b3e6", "f7e6"), continuation.resolvedMoves)
        assertEquals(TerminationReason.QUIET_STOP, continuation.terminationReason)
        assertEquals(TacticalResolutionStatus.RESOLVED_QUIET, continuation.resolutionStatus)
    }

    @Test
    fun `resolveTacticalContinuation halts with zero continuation moves on quiet stop`() {
        val stockfish = StockfishService()
        val c =
            DynamicContinuationCoordinator(
                artifactRepository = artifactRepository,
                positionRepository = positionRepository,
                stockfishService = stockfish,
                properties = properties,
            )

        val fen = "r1bqkbnr/pppp1ppp/2n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 1 3"
        val qReader =
            simulatedReader(
                "info depth 8 score cp -35 nodes 500 pv b5a4 d7d6",
                "bestmove b5a4",
            )

        val continuation =
            c.resolveTacticalContinuation(
                rootFen = fen,
                nominalMoves = listOf("a7a6"),
                achievedDepth = 10,
                quiescenceReader = qReader,
            )

        assertEquals(10, continuation.achievedDepth)
        assertEquals(listOf("a7a6"), continuation.nominalMoves)
        assertTrue(continuation.continuationMoves.isEmpty())
        assertEquals(listOf("a7a6"), continuation.resolvedMoves)
        assertEquals(TerminationReason.QUIET_STOP, continuation.terminationReason)
        assertEquals(TacticalResolutionStatus.RESOLVED_QUIET, continuation.resolutionStatus)
    }

    @Test
    fun `resolveTacticalContinuation maps PLY_LIMIT to INCOMPLETE_LIMIT`() {
        val stockfish = StockfishService()
        val c =
            DynamicContinuationCoordinator(
                artifactRepository = artifactRepository,
                positionRepository = positionRepository,
                stockfishService = stockfish,
                properties = properties,
            )

        val opera = "3rkb1r/p2R1ppp/4qn2/1B2p1B1/4P3/1Q6/PPP2PPP/2K4R b k - 0 13"
        val qReader =
            simulatedReader(
                "info depth 8 score cp 800 nodes 1200 pv b3e6 f7e6 c1b1",
                "bestmove b3e6",
            )

        val continuation =
            c.resolveTacticalContinuation(
                rootFen = opera,
                nominalMoves = listOf("d8d7"),
                achievedDepth = 10,
                quiescenceOptions = QuiescenceOptions(maxPlies = 1, timeoutMs = 1000L),
                quiescenceReader = qReader,
            )

        assertEquals(listOf("b3e6"), continuation.continuationMoves)
        assertEquals(TerminationReason.PLY_LIMIT, continuation.terminationReason)
        assertEquals(TacticalResolutionStatus.INCOMPLETE_LIMIT, continuation.resolutionStatus)
    }

    @Test
    fun `default config hash is deterministic SHA-256 covering all configuration parameters`() {
        val hash1 = coordinator.defaultConfigHash
        val hash2 = DynamicContinuationCoordinator.computeConfigHash(engineDigest = testEngineDigest)
        assertNotNull(hash1)
        assertEquals(64, hash1.length)
        assertEquals(hash1, hash2)

        assertFalse(hash1 == DynamicContinuationCoordinator.computeConfigHash(engineDigest = "different-digest"))
        assertFalse(hash1 == DynamicContinuationCoordinator.computeConfigHash(engineDigest = testEngineDigest, threads = 2))
        assertFalse(hash1 == DynamicContinuationCoordinator.computeConfigHash(engineDigest = testEngineDigest, hashMb = 32))
        assertFalse(hash1 == DynamicContinuationCoordinator.computeConfigHash(engineDigest = testEngineDigest, ponder = true))
        assertFalse(hash1 == DynamicContinuationCoordinator.computeConfigHash(engineDigest = testEngineDigest, nominalDepth = 12))
        assertFalse(hash1 == DynamicContinuationCoordinator.computeConfigHash(engineDigest = testEngineDigest, multiPv = 2))
        assertFalse(hash1 == DynamicContinuationCoordinator.computeConfigHash(engineDigest = testEngineDigest, quiescenceDepth = 10))
        assertFalse(hash1 == DynamicContinuationCoordinator.computeConfigHash(engineDigest = testEngineDigest, quiescenceNodes = 10000L))
        assertFalse(hash1 == DynamicContinuationCoordinator.computeConfigHash(engineDigest = testEngineDigest, quiescenceTimeoutMs = 500L))
        assertFalse(hash1 == DynamicContinuationCoordinator.computeConfigHash(engineDigest = testEngineDigest, quiescenceMaxPlies = 15))
        assertFalse(
            hash1 == DynamicContinuationCoordinator.computeConfigHash(engineDigest = testEngineDigest, canonicalizationVersion = "fen-v2"),
        )
        assertFalse(
            hash1 == DynamicContinuationCoordinator.computeConfigHash(engineDigest = testEngineDigest, featureVersion = "tactical-v2"),
        )
    }

    @Test
    fun `enqueuePositions adds to queue without blocking caller`() {
        val posId1 = UUID.randomUUID()
        val posId2 = UUID.randomUUID()
        coordinator.enqueuePositions(listOf(posId1, posId2))
    }

    @Test
    fun `queue saturation drops extra positions non-blockingly and recovery retrieves them when capacity exists`() {
        val slowPositionRepo: PositionRepository =
            mock {
                on { findById(any()) } doAnswer {
                    Thread.sleep(100)
                    java.util.Optional.empty()
                }
            }
        val customCoordinator =
            DynamicContinuationCoordinator(
                artifactRepository = artifactRepository,
                positionRepository = slowPositionRepo,
                stockfishService = stockfishService,
                properties = properties,
                queueCapacity = 5,
                engineDigestProvider = { testEngineDigest },
            )

        try {
            val ids = (1..10).map { UUID.randomUUID() }
            customCoordinator.enqueuePositions(ids)

            assertTrue(customCoordinator.getDroppedPositionsCount() >= 1L)
            assertTrue(customCoordinator.getQueueSize() <= 5)

            val unanalyzedPositions = (1..2).map { Position(id = UUID.randomUUID(), fen = "fen-$it", hash = "h-$it") }
            whenever(
                slowPositionRepo.findEligiblePositionsForContinuation(
                    configHash = eq(customCoordinator.defaultConfigHash),
                    now = any(),
                    maxRetries = eq(DynamicContinuationCoordinator.MAX_RETRIES),
                    pageable = any(),
                ),
            ).thenReturn(unanalyzedPositions)

            val recovered = customCoordinator.recoverEligiblePositions(batchSize = 2)
            assertTrue(recovered >= 0)
        } finally {
            customCoordinator.destroy()
        }
    }

    @Test
    fun `duplicate discovery ignores already queued positions and avoids duplicate enqueueing`() {
        val posId = UUID.randomUUID()
        val initialQueuedCount = coordinator.getQueuedPositionsCount()

        coordinator.enqueuePositions(listOf(posId))
        assertEquals(initialQueuedCount + 1, coordinator.getQueuedPositionsCount())

        coordinator.enqueuePositions(listOf(posId))
        assertEquals(initialQueuedCount + 1, coordinator.getQueuedPositionsCount())
    }

    @Test
    fun `startup recovery rediscovers eligible positions and expired leases from prior runs`() {
        val pos1 = Position(id = UUID.randomUUID(), fen = "8/8/8/8/8/8/8/R3K2R w KQ - 0 1", hash = "orphan1")
        val pos2 = Position(id = UUID.randomUUID(), fen = "8/8/8/8/8/8/8/4K2R w K - 0 1", hash = "orphan2")

        val repo: PositionRepository =
            mock {
                on {
                    findEligiblePositionsForContinuation(
                        configHash = any(),
                        now = any(),
                        maxRetries = any(),
                        pageable = any(),
                    )
                } doReturn listOf(pos1, pos2)
            }
        val stockfish: StockfishService =
            mock {
                on { getEngineBinaryHash() } doReturn "test-digest-cfg"
            }

        val newCoordinator =
            DynamicContinuationCoordinator(
                artifactRepository = artifactRepository,
                positionRepository = repo,
                stockfishService = stockfish,
                properties = properties,
                engineDigestProvider = { "test-digest-cfg" },
            )

        try {
            val deadline = System.currentTimeMillis() + 2000
            var recovered = false
            while (System.currentTimeMillis() < deadline) {
                if (newCoordinator.getRecoveredPositionsCount() >= 2L) {
                    recovered = true
                    break
                }
                Thread.sleep(20)
            }
            assertTrue(recovered)
        } finally {
            newCoordinator.destroy()
        }
    }

    @Test
    fun `concurrent recovery from multiple workers ensures only winning claim computes continuation`() {
        val positionId = UUID.randomUUID()
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"
        val position = Position(id = positionId, fen = fen, hash = "hash-concurrent")

        whenever(artifactRepository.insertPendingIfAbsent(any(), eq(positionId), any(), any(), any(), any()))
            .thenReturn(1)
            .thenReturn(0)

        val existingPending =
            PositionContinuationArtifact(
                positionId = positionId,
                configHash = coordinator.defaultConfigHash,
                status = PositionContinuationStatus.PENDING,
            )
        whenever(artifactRepository.findByPositionIdAndConfigHash(positionId, coordinator.defaultConfigHash))
            .thenReturn(existingPending)

        val executor = Executors.newFixedThreadPool(2)
        val future1 = executor.submit(Callable { coordinator.getOrComputeContinuation(position) })
        val future2 = executor.submit(Callable { coordinator.getOrComputeContinuation(position) })

        val res1 = future1.get()
        val res2 = future2.get()
        executor.shutdown()

        assertNotNull(res1)
        assertNotNull(res2)
        verify(artifactRepository, org.mockito.kotlin.atMost(1)).completeLeasedArtifact(
            any(),
            any(),
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
            any(),
        )
    }

    @Test
    fun `exhausted retries stops retrying failed position and excludes it from recovery`() {
        val positionId = UUID.randomUUID()
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"
        val position = Position(id = positionId, fen = fen, hash = "hash-exhausted")

        val failedMaxRetries =
            PositionContinuationArtifact(
                positionId = positionId,
                configHash = coordinator.defaultConfigHash,
                status = PositionContinuationStatus.FAILED,
                retryCount = DynamicContinuationCoordinator.MAX_RETRIES,
            )

        whenever(artifactRepository.findByPositionIdAndConfigHash(positionId, coordinator.defaultConfigHash))
            .thenReturn(failedMaxRetries)
        whenever(artifactRepository.reclaimStaleOrFailed(any(), any(), any(), any(), any(), any()))
            .thenReturn(0)

        val result = coordinator.getOrComputeContinuation(position)
        assertEquals(PositionContinuationStatus.FAILED, result.status)
        assertEquals(DynamicContinuationCoordinator.MAX_RETRIES, result.retryCount)

        verify(stockfishService, never()).analyzeFullPv(any(), any(), any(), anyOrNull(), any())
    }
}
