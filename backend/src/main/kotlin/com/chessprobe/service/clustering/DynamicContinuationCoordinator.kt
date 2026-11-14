package com.chessprobe.service.clustering

import com.chessprobe.config.PositionClusteringProperties
import com.chessprobe.domain.Position
import com.chessprobe.domain.clustering.PositionContinuationArtifact
import com.chessprobe.domain.clustering.PositionContinuationStatus
import com.chessprobe.domain.clustering.TacticalResolutionStatus
import com.chessprobe.repository.PositionRepository
import com.chessprobe.repository.clustering.PositionContinuationArtifactRepository
import com.chessprobe.service.PvContinuationResult
import com.chessprobe.service.QuiescenceOptions
import com.chessprobe.service.StockfishExecutableResolver
import com.chessprobe.service.StockfishService
import com.chessprobe.service.continuation.TerminationReason
import com.github.bhlangonijr.chesslib.Board
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.BufferedReader
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class TacticalContinuation(
    val achievedDepth: Int,
    val nominalMoves: List<String>,
    val continuationMoves: List<String>,
    val resolvedMoves: List<String>,
    val terminalFen: String,
    val terminationReason: TerminationReason,
    val resolutionStatus: TacticalResolutionStatus,
)

@Service
class DynamicContinuationCoordinator(
    private val artifactRepository: PositionContinuationArtifactRepository,
    private val positionRepository: PositionRepository,
    private val stockfishService: StockfishService,
    private val properties: PositionClusteringProperties,
    @Value("\${chess.stockfish.path:stockfish}")
    private val stockfishPath: String = "stockfish",
    val queueCapacity: Int = 10000,
    private val engineDigestProvider: (() -> String)? = null,
) : DisposableBean {
    private val logger = LoggerFactory.getLogger(DynamicContinuationCoordinator::class.java)

    val defaultConfigHash: String =
        computeConfigHash(
            engineDigest = resolveDefaultEngineDigest(),
        )

    private fun resolveDefaultEngineDigest(): String {
        val customDigest = engineDigestProvider?.invoke()
        if (customDigest != null) return customDigest
        val resolved = StockfishExecutableResolver.resolveExecutableFile(stockfishPath)
        return if (resolved != null) {
            StockfishExecutableResolver.computeFileSha256(resolved)
        } else {
            StockfishExecutableResolver.computeFallbackDigest(stockfishPath)
        }
    }

    private val precomputeQueue = LinkedBlockingQueue<UUID>(queueCapacity)
    private val queuedPositions = ConcurrentHashMap.newKeySet<UUID>()
    private val droppedPositionsCount = AtomicLong(0)
    private val recoveredPositionsCount = AtomicLong(0)

    @Volatile
    private var isShuttingDown = false

    fun getDroppedPositionsCount(): Long = droppedPositionsCount.get()

    fun getRecoveredPositionsCount(): Long = recoveredPositionsCount.get()

    fun getQueueSize(): Int = precomputeQueue.size

    fun getQueuedPositionsCount(): Int = queuedPositions.size

    private val precomputeExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            val thread = Thread(r, "dynamic-continuation-precompute")
            thread.isDaemon = true
            thread
        }

    init {
        precomputeExecutor.submit {
            try {
                recoverEligiblePositions(batchSize = 100)
            } catch (e: Exception) {
                logger.warn("Initial startup recovery sweep encountered an error", e)
            }
            processPrecomputeQueue()
        }
    }

    override fun destroy() {
        isShuttingDown = true
        precomputeExecutor.shutdown()
        try {
            if (!precomputeExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                precomputeExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            logger.info("Interrupted during executor termination, forcing shutdown", e)
            precomputeExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    fun enqueuePositions(positionIds: Collection<UUID>) {
        if (isShuttingDown) return
        for (id in positionIds) {
            if (queuedPositions.add(id)) {
                val accepted = precomputeQueue.offer(id)
                if (!accepted) {
                    queuedPositions.remove(id)
                    droppedPositionsCount.incrementAndGet()
                    logger.warn("Precompute queue full (capacity={}), dropping position {}", queueCapacity, id)
                }
            }
        }
    }

    fun recoverEligiblePositions(batchSize: Int = 100): Int {
        if (isShuttingDown) return 0
        val pageable = PageRequest.of(0, batchSize)
        val now = Instant.now()
        val eligible =
            runCatching {
                positionRepository.findEligiblePositionsForContinuation(
                    configHash = defaultConfigHash,
                    now = now,
                    maxRetries = MAX_RETRIES,
                    pageable = pageable,
                )
            }.getOrNull() ?: emptyList()
        if (eligible.isEmpty()) return 0

        var count = 0
        for (pos in eligible) {
            if (isShuttingDown) break
            if (queuedPositions.add(pos.id)) {
                if (precomputeQueue.offer(pos.id)) {
                    count++
                } else {
                    queuedPositions.remove(pos.id)
                    break
                }
            }
        }
        if (count > 0) {
            recoveredPositionsCount.addAndGet(count.toLong())
            logger.info("Recovered {} eligible positions into precompute queue for config {}", count, defaultConfigHash)
        }
        return count
    }

    @Scheduled(
        fixedDelayString = "\${chessprobe.continuation.recovery.interval-ms:30000}",
        initialDelayString = "\${chessprobe.continuation.recovery.initial-delay-ms:10000}",
    )
    fun scheduledRecoverySweep() {
        try {
            recoverEligiblePositions(batchSize = 100)
        } catch (e: Exception) {
            logger.error("Error during scheduled continuation recovery sweep", e)
        }
    }

    private fun processPrecomputeQueue() {
        while (!Thread.currentThread().isInterrupted && !isShuttingDown) {
            try {
                processSingleQueueItem()
            } catch (e: InterruptedException) {
                logger.info("Precompute worker interrupted, shutting down", e)
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                logger.error("Error processing precompute queue item", e)
            }
        }
    }

    private fun processSingleQueueItem() {
        val positionId = precomputeQueue.poll(2, TimeUnit.SECONDS)
        if (positionId != null) {
            queuedPositions.remove(positionId)
            val position = positionRepository.findById(positionId).orElse(null)
            if (position != null) {
                getOrComputeContinuation(position)
            }
        } else {
            recoverEligiblePositions(batchSize = 100)
        }
    }

    fun resolveTacticalContinuation(
        rootFen: String,
        nominalMoves: List<String>,
        achievedDepth: Int,
        quiescenceOptions: QuiescenceOptions = defaultQuiescenceOptions(),
        quiescenceReader: BufferedReader? = null,
        quiescenceSendCommand: (String) -> Unit = {},
    ): TacticalContinuation {
        val board = Board()
        board.loadFromFen(rootFen)
        for (uci in nominalMoves) {
            val move =
                board.legalMoves().find { it.toString() == uci }
                    ?: throw IllegalArgumentException("Illegal nominal PV move '$uci' for FEN ${board.fen}")
            board.doMove(move)
        }
        val postNominalFen = board.fen

        if (board.isMated || board.isDraw || board.isStaleMate) {
            return TacticalContinuation(
                achievedDepth = achievedDepth,
                nominalMoves = nominalMoves,
                continuationMoves = emptyList(),
                resolvedMoves = nominalMoves,
                terminalFen = postNominalFen,
                terminationReason = TerminationReason.TERMINAL,
                resolutionStatus = TacticalResolutionStatus.RESOLVED_TERMINAL,
            )
        }

        val qResult = executeQuiescence(postNominalFen, quiescenceOptions, quiescenceReader, quiescenceSendCommand)
        val resolutionStatus = mapTerminationReason(qResult.terminationReason)

        return TacticalContinuation(
            achievedDepth = achievedDepth,
            nominalMoves = nominalMoves,
            continuationMoves = qResult.moves,
            resolvedMoves = nominalMoves + qResult.moves,
            terminalFen = qResult.terminalFen,
            terminationReason = qResult.terminationReason,
            resolutionStatus = resolutionStatus,
        )
    }

    private fun executeQuiescence(
        fen: String,
        options: QuiescenceOptions,
        reader: BufferedReader?,
        sendCommand: (String) -> Unit,
    ): PvContinuationResult =
        if (reader != null) {
            stockfishService.resolveForcingPvContinuation(
                fen = fen,
                options = options,
                reader = reader,
                sendCommand = sendCommand,
            )
        } else {
            stockfishService.resolveForcingPvContinuation(
                fen = fen,
                options = options,
            )
        }

    private fun mapTerminationReason(reason: TerminationReason): TacticalResolutionStatus =
        when (reason) {
            TerminationReason.QUIET_STOP -> TacticalResolutionStatus.RESOLVED_QUIET
            TerminationReason.TERMINAL -> TacticalResolutionStatus.RESOLVED_TERMINAL
            TerminationReason.PV_EXHAUSTED,
            TerminationReason.PLY_LIMIT,
            TerminationReason.NODE_LIMIT,
            -> TacticalResolutionStatus.INCOMPLETE_LIMIT
            TerminationReason.TIMEOUT,
            TerminationReason.ENGINE_ERROR,
            -> TacticalResolutionStatus.OPERATIONAL_FAILURE
        }

    fun resolveTacticalContinuation(
        rootFen: String,
        targetDepth: Int = 10,
        quiescenceOptions: QuiescenceOptions = defaultQuiescenceOptions(),
    ): TacticalContinuation {
        val nominal = stockfishService.analyzeFullPv(rootFen, targetDepth, quiescenceOptions.timeoutMs * 4)
        if (nominal.achievedDepth < targetDepth) {
            throw IllegalStateException("Nominal search aborted: achievedDepth=${nominal.achievedDepth} < targetDepth=$targetDepth")
        }
        return resolveTacticalContinuation(
            rootFen = rootFen,
            nominalMoves = nominal.moves,
            achievedDepth = nominal.achievedDepth,
            quiescenceOptions = quiescenceOptions,
        )
    }

    @Transactional
    fun getOrComputeContinuation(
        position: Position,
        configHash: String = defaultConfigHash,
    ): PositionContinuationArtifact {
        val existing = artifactRepository.findByPositionIdAndConfigHash(position.id, configHash)
        if (existing != null && existing.status == PositionContinuationStatus.COMPLETED) {
            return existing
        }

        val artifactId = existing?.id ?: UUID.randomUUID()
        val leaseToken = UUID.randomUUID().toString()
        val now = Instant.now()
        val leaseExpiresAt = now.plusSeconds(LEASE_DURATION_SECONDS)

        val claimed = claimOrReclaim(existing, artifactId, position.id, configHash, leaseToken, leaseExpiresAt, now)
        if (!claimed) {
            return artifactRepository.findByPositionIdAndConfigHash(position.id, configHash)
                ?: PositionContinuationArtifact(
                    id = artifactId,
                    positionId = position.id,
                    configHash = configHash,
                    status = PositionContinuationStatus.PENDING,
                )
        }

        val targetId = artifactRepository.findByPositionIdAndConfigHash(position.id, configHash)?.id ?: artifactId
        return executeAndPersistContinuation(position, targetId, leaseToken, configHash)
    }

    private fun claimOrReclaim(
        existing: PositionContinuationArtifact?,
        artifactId: UUID,
        positionId: UUID,
        configHash: String,
        leaseToken: String,
        leaseExpiresAt: Instant,
        now: Instant,
    ): Boolean =
        if (existing == null) {
            artifactRepository.insertPendingIfAbsent(
                id = artifactId,
                positionId = positionId,
                configHash = configHash,
                leaseToken = leaseToken,
                leaseExpiresAt = leaseExpiresAt,
                now = now,
            ) > 0
        } else {
            artifactRepository.reclaimStaleOrFailed(
                positionId = positionId,
                configHash = configHash,
                leaseToken = leaseToken,
                leaseExpiresAt = leaseExpiresAt,
                maxRetries = MAX_RETRIES,
                now = now,
            ) > 0
        }

    private fun executeAndPersistContinuation(
        position: Position,
        targetId: UUID,
        leaseToken: String,
        configHash: String,
    ): PositionContinuationArtifact =
        try {
            val continuation =
                resolveTacticalContinuation(
                    rootFen = position.fen,
                    targetDepth = 10,
                    quiescenceOptions = defaultQuiescenceOptions(),
                )

            val status =
                if (continuation.resolutionStatus == TacticalResolutionStatus.OPERATIONAL_FAILURE) {
                    PositionContinuationStatus.FAILED
                } else {
                    PositionContinuationStatus.COMPLETED
                }

            artifactRepository.completeLeasedArtifact(
                id = targetId,
                leaseToken = leaseToken,
                status = status,
                resolutionStatus = continuation.resolutionStatus,
                achievedDepth = continuation.achievedDepth,
                nominalMoves = continuation.nominalMoves.joinToString(" "),
                continuationMoves = continuation.continuationMoves.joinToString(" "),
                resolvedMoves = continuation.resolvedMoves.joinToString(" "),
                terminalFen = continuation.terminalFen,
                terminationReason = continuation.terminationReason,
                now = Instant.now(),
            )
            artifactRepository.findByPositionIdAndConfigHash(position.id, configHash)
                ?: error("Artifact missing after completeLeasedArtifact")
        } catch (e: Exception) {
            logger.warn("Failed to compute dynamic continuation for position {}", position.id, e)
            artifactRepository.failLeasedArtifact(
                id = targetId,
                leaseToken = leaseToken,
                status = PositionContinuationStatus.FAILED,
                resolutionStatus = TacticalResolutionStatus.OPERATIONAL_FAILURE,
                errorMessage = e.message?.take(500),
                now = Instant.now(),
            )
            artifactRepository.findByPositionIdAndConfigHash(position.id, configHash)
                ?: error("Artifact missing after failLeasedArtifact")
        }

    @Transactional
    fun evaluateBatch(
        positions: List<Position>,
        configHash: String = defaultConfigHash,
    ): Map<UUID, PositionContinuationArtifact> {
        if (positions.isEmpty()) return emptyMap()

        val positionIds = positions.map { it.id }.toSet()
        val cached =
            artifactRepository
                .findByPositionIdInAndConfigHash(positionIds, configHash)
                .filter { it.status == PositionContinuationStatus.COMPLETED }
                .associateBy { it.positionId }

        val resultMap = cached.toMutableMap()
        val remaining = positions.filter { !cached.containsKey(it.id) }

        for (pos in remaining) {
            val artifact = getOrComputeContinuation(pos, configHash)
            resultMap[pos.id] = artifact
        }

        return resultMap
    }

    companion object {
        const val LEASE_DURATION_SECONDS = 60L
        const val MAX_RETRIES = 3

        fun defaultQuiescenceOptions(): QuiescenceOptions =
            QuiescenceOptions(
                maxDepth = 8,
                maxNodes = 5000L,
                timeoutMs = 250L,
                maxPlies = 10,
            )

        fun computeConfigHash(
            engineDigest: String = StockfishService.computeFallbackDigest("stockfish"),
            threads: Int = StockfishService.UCI_THREADS,
            hashMb: Int = StockfishService.UCI_HASH_MB,
            ponder: Boolean = StockfishService.UCI_PONDER,
            nominalDepth: Int = 10,
            multiPv: Int = 1,
            quiescenceDepth: Int = 8,
            quiescenceNodes: Long = 5000L,
            quiescenceTimeoutMs: Long = 250L,
            quiescenceMaxPlies: Int = 10,
            canonicalizationVersion: String = "fen-v1",
            featureVersion: String = "tactical-v1",
        ): String {
            val raw =
                "stockfish|bin:$engineDigest|threads:$threads|hash:$hashMb|ponder:$ponder|" +
                    "d:$nominalDepth|mpv:$multiPv|qd:$quiescenceDepth|" +
                    "qn:$quiescenceNodes|qt:$quiescenceTimeoutMs|qp:$quiescenceMaxPlies|" +
                    "canon:$canonicalizationVersion|feat:$featureVersion"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
