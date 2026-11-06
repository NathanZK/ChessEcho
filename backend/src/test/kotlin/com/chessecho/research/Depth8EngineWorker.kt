package com.chessecho.research

import com.chessecho.similarity.DynamicTacticalScorer
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.move.Move
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Persistent Stockfish worker executing Depth-8 MultiPV search and bounded PV extension
 * according to SPEC-RESEARCH-DYNAMIC-SIMILARITY-DEPTH8-MULTIPV (§4.4, §5, §10).
 */
class Depth8EngineWorker(
    val binaryPath: String = "/opt/homebrew/bin/stockfish",
    val binarySha256: String = Depth8CacheKeyGenerator.DEFAULT_STOCKFISH_SHA256,
    val configuredMultiPv: Int = 1,
    val nominalDepth: Int = 8,
    val extMaxNodes: Long = 5000L,
    val extMaxPlies: Int = 10,
    val watchdogTimeoutMs: Long = 10000L,
) : AutoCloseable {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var searchesDone = 0

    private val dynamicScorer = DynamicTacticalScorer()
    private val watchdogExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Depth8EngineWorker-Watchdog").apply { isDaemon = true }
    }

    init {
        startProcess()
    }

    @Synchronized
    fun startProcess() {
        closeProcess()
        val pb = ProcessBuilder(binaryPath)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        process = proc
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
        reader = BufferedReader(InputStreamReader(proc.inputStream))

        executeWithWatchdog("Engine Initialization", watchdogTimeoutMs) {
            sendCommand("uci")
            waitForResponse { it == "uciok" }
            sendCommand("setoption name Threads value 1")
            sendCommand("setoption name Hash value 64")
            sendCommand("setoption name MultiPV value $configuredMultiPv")
            sendCommand("setoption name Ponder value false")
            sendCommand("setoption name SyzygyPath value <empty>")
            sendCommand("setoption name UCI_AnalyseMode value true")
            sendCommand("isready")
            waitForResponse { it == "readyok" }
        }
        searchesDone = 0
    }

    @Synchronized
    fun evaluate(canonicalFen: String): Depth8CachedEngineRecord {
        val normFen = Depth8CacheKeyGenerator.normalizeFen(canonicalFen)
        if (!Depth8CacheKeyGenerator.isSupportedByEngine(canonicalFen)) {
            return Depth8CachedEngineRecord(
                canonicalFen = Depth8CacheKeyGenerator.toCanonicalFen(canonicalFen),
                normalizedFen = normFen,
                depth = nominalDepth,
                multiPv = configuredMultiPv,
                bestMove = "",
                scoreCp = 0,
                principalVariations = emptyList(),
                dynamicTokens = emptyMap(),
                tokensByRank = emptyMap(),
                elapsedMs = 0L,
                timedOut = false
            )
        }

        var lastException: Exception? = null

        for (attempt in 1..3) {
            try {
                if (searchesDone >= 500 || process?.isAlive != true) {
                    startProcess()
                }
                val record = executeWithWatchdog("Evaluate $canonicalFen", watchdogTimeoutMs) {
                    doEvaluate(canonicalFen, normFen)
                }
                searchesDone++
                return record
            } catch (e: Exception) {
                lastException = e
                System.err.println("Worker evaluate attempt $attempt failed on $canonicalFen: ${e.message}. Restarting engine...")
                startProcess()
            }
        }
        throw IllegalStateException("Failed to evaluate position after 3 attempts: $normFen", lastException)
    }

    private fun doEvaluate(canonicalFen: String, normFen: String): Depth8CachedEngineRecord {
        val rd = reader ?: throw IOException("Reader not initialized")
        val startTime = System.currentTimeMillis()

        // 1. Cold start TT reset
        sendCommand("ucinewgame")
        sendCommand("isready")
        waitForResponse { it == "readyok" }

        // 2. Root Nominal Depth 8 Search
        sendCommand("position fen $normFen")
        sendCommand("go depth $nominalDepth")

        var bestMove = ""
        val pvMap = mutableMapOf<Int, MutablePvAccumulator>()

        while (true) {
            val line = rd.readLine() ?: throw IOException("Engine stream closed prematurely during root search")
            if (line.startsWith("info") && line.contains("score")) {
                val rank = parseTokenInt(line, "multipv") ?: 1
                val score = parseScore(line)
                val nodes = parseTokenLong(line, "nodes")
                val pvIdx = line.indexOf(" pv ")
                val moves = if (pvIdx != -1) {
                    line.substring(pvIdx + 4).trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                } else {
                    emptyList()
                }
                val acc = pvMap.getOrPut(rank) { MutablePvAccumulator(rank) }
                acc.scoreCp = score
                if (nodes != null) acc.nominalNodes = nodes
                if (moves.isNotEmpty()) acc.nominalMoves = moves
            } else if (line.startsWith("bestmove")) {
                val parts = line.split(" ")
                if (parts.size >= 2) {
                    bestMove = parts[1]
                }
                break
            }
        }

        val availableRanks = (1..configuredMultiPv).mapNotNull { pvMap[it] }

        // 3. Resolve Bounded PV Extension for Each Rank
        val resolvedPvLines = mutableListOf<Depth8CachedPvLine>()
        val tokensByRank = mutableMapOf<String, Map<String, Double>>()

        for (acc in availableRanks) {
            val resolvedLine = resolveRankExtension(normFen, acc)
            resolvedPvLines.add(resolvedLine)

            val rankTokens = if (resolvedLine.moves.isNotEmpty()) {
                dynamicScorer.extractDynamicTokens(normFen, resolvedLine.moves)
            } else {
                emptyMap()
            }
            tokensByRank[resolvedLine.rank.toString()] = rankTokens
        }

        val elapsed = System.currentTimeMillis() - startTime
        val rank1 = resolvedPvLines.firstOrNull { it.rank == 1 }
        val overallScore = rank1?.scoreCp ?: 0

        // Aggregate dynamic tokens using Model A (Summed TF with clamped episode flags)
        val aggregatedTokens = aggregateModelA(tokensByRank.values)

        return Depth8CachedEngineRecord(
            canonicalFen = Depth8CacheKeyGenerator.toCanonicalFen(canonicalFen),
            normalizedFen = normFen,
            depth = nominalDepth,
            multiPv = configuredMultiPv,
            bestMove = bestMove,
            scoreCp = overallScore,
            principalVariations = resolvedPvLines,
            dynamicTokens = aggregatedTokens,
            tokensByRank = tokensByRank,
            elapsedMs = elapsed,
            timedOut = false
        )
    }

    private fun resolveRankExtension(
        normFen: String,
        acc: MutablePvAccumulator,
    ): Depth8CachedPvLine {
        val board = Board()
        board.loadFromFen(normFen)

        // Replay nominal moves to horizon board B_k
        val validNominalMoves = mutableListOf<String>()
        for (uci in acc.nominalMoves) {
            val legalMove = board.legalMoves().find { it.toString() == uci } ?: break
            board.doMove(legalMove)
            validNominalMoves.add(uci)
            if (board.isMated || board.isDraw || board.isStaleMate) {
                break
            }
        }

        // Terminal check at horizon B_k
        if (board.isMated || board.isDraw || board.isStaleMate) {
            return Depth8CachedPvLine(
                rank = acc.rank,
                scoreCp = acc.scoreCp,
                moves = validNominalMoves,
                nominalDepth = nominalDepth,
                nominalMoves = validNominalMoves,
                extensionMoves = emptyList(),
                terminationReason = "TERMINAL",
                nominalNodes = acc.nominalNodes,
                extensionNodes = 0L,
                totalNodes = acc.nominalNodes
            )
        }

        // Invoke bounded extension search from B_k on persistent worker
        val extResult = runHorizonSearch(board.fen, extMaxNodes, extMaxPlies)

        val fullMoves = validNominalMoves + extResult.appliedExtensionMoves
        val totalNodes = (acc.nominalNodes ?: 0L) + extResult.extensionNodes

        return Depth8CachedPvLine(
            rank = acc.rank,
            scoreCp = acc.scoreCp,
            moves = fullMoves,
            nominalDepth = nominalDepth,
            nominalMoves = validNominalMoves,
            extensionMoves = extResult.appliedExtensionMoves,
            terminationReason = extResult.terminationReason,
            nominalNodes = acc.nominalNodes,
            extensionNodes = extResult.extensionNodes,
            totalNodes = totalNodes
        )
    }

    private fun runHorizonSearch(
        horizonFen: String,
        maxNodes: Long,
        maxPlies: Int,
    ): ExtensionRunResult {
        val rd = reader ?: throw IOException("Reader not initialized")

        // MultiPV scoping: switch worker to MultiPV 1 for single linear continuation
        if (configuredMultiPv > 1) {
            sendCommand("setoption name MultiPV value 1")
            sendCommand("isready")
            waitForResponse { it == "readyok" }
        }

        try {
            sendCommand("position fen $horizonFen")
            sendCommand("go depth $nominalDepth nodes $maxNodes")

            var extNodes = 0L
            var returnedPvMoves = emptyList<String>()

            while (true) {
                val line = rd.readLine() ?: throw IOException("Engine stream closed during horizon extension")
                if (line.startsWith("info") && line.contains(" pv ")) {
                    val nodes = parseTokenLong(line, "nodes")
                    if (nodes != null) extNodes = nodes
                    val pvIdx = line.indexOf(" pv ")
                    if (pvIdx != -1) {
                        returnedPvMoves = line.substring(pvIdx + 4).trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                    }
                } else if (line.startsWith("bestmove")) {
                    break
                }
            }

            if (returnedPvMoves.isEmpty()) {
                return ExtensionRunResult(emptyList(), "PV_EXHAUSTED", extNodes)
            }

            // Replay forcing moves
            val board = Board()
            board.loadFromFen(horizonFen)
            val applied = mutableListOf<String>()
            var terminationReason = "PV_EXHAUSTED"

            for (uciMove in returnedPvMoves) {
                if (applied.size >= maxPlies) {
                    terminationReason = "PLY_LIMIT"
                    break
                }
                val legalMove = board.legalMoves().find { it.toString() == uciMove }
                if (legalMove == null) {
                    terminationReason = "PV_EXHAUSTED"
                    break
                }

                if (!isForcingMove(board, legalMove)) {
                    terminationReason = "QUIET_STOP"
                    break
                }

                board.doMove(legalMove)
                applied.add(uciMove)

                if (board.isMated || board.isDraw || board.isStaleMate) {
                    terminationReason = "TERMINAL"
                    break
                }
            }

            // If we ran out of moves without an unforced move and hit node cap
            if (terminationReason == "PV_EXHAUSTED" && extNodes >= maxNodes && applied.isNotEmpty()) {
                terminationReason = "NODE_LIMIT"
            }

            return ExtensionRunResult(applied, terminationReason, extNodes)
        } finally {
            if (configuredMultiPv > 1) {
                sendCommand("setoption name MultiPV value $configuredMultiPv")
                sendCommand("isready")
                waitForResponse { it == "readyok" }
            }
        }
    }

    fun isForcingMove(board: Board, move: Move): Boolean {
        if (board.isKingAttacked) {
            return true
        }
        val fromPiece = board.getPiece(move.from)
        val toPiece = board.getPiece(move.to)
        val isCapture = toPiece != Piece.NONE ||
            (fromPiece.pieceType == PieceType.PAWN && move.from.file != move.to.file)
        if (isCapture) {
            return true
        }
        if (move.promotion != Piece.NONE) {
            return true
        }
        board.doMove(move)
        val givesCheck = board.isKingAttacked
        board.undoMove()
        return givesCheck
    }

    fun aggregateModelA(tokenMaps: Collection<Map<String, Double>>): Map<String, Double> {
        val aggregated = mutableMapOf<String, Double>()
        val episodeFlags = setOf("?px", "?ox", "?+", "?=", "?#", "?S")

        for (tokens in tokenMaps) {
            for ((token, freq) in tokens) {
                if (episodeFlags.contains(token) || token.startsWith("?")) {
                    aggregated[token] = 1.0 // Clamped to binary presence
                } else {
                    aggregated[token] = (aggregated[token] ?: 0.0) + freq
                }
            }
        }
        return aggregated
    }

    private fun sendCommand(cmd: String) {
        val wr = writer ?: throw IOException("Writer not initialized")
        wr.write(cmd)
        wr.newLine()
        wr.flush()
    }

    private fun waitForResponse(predicate: (String) -> Boolean): String {
        val rd = reader ?: throw IOException("Reader not initialized")
        while (true) {
            val line = rd.readLine() ?: throw IOException("Engine stream closed while waiting for response")
            if (predicate(line)) {
                return line
            }
        }
    }

    private fun <T> executeWithWatchdog(taskName: String, timeoutMs: Long, block: () -> T): T {
        val future: Future<T> = watchdogExecutor.submit(Callable { block() })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw TimeoutException("Task '$taskName' timed out after $timeoutMs ms")
        }
    }

    private fun parseTokenInt(line: String, token: String): Int? {
        val parts = line.split(" ")
        val idx = parts.indexOf(token)
        return if (idx != -1 && idx + 1 < parts.size) parts[idx + 1].toIntOrNull() else null
    }

    private fun parseTokenLong(line: String, token: String): Long? {
        val parts = line.split(" ")
        val idx = parts.indexOf(token)
        return if (idx != -1 && idx + 1 < parts.size) parts[idx + 1].toLongOrNull() else null
    }

    private fun parseScore(line: String): Int {
        val cpIdx = line.indexOf("score cp ")
        if (cpIdx != -1) {
            val after = line.substring(cpIdx + 9).trim()
            return after.split(" ")[0].toIntOrNull() ?: 0
        }
        val mateIdx = line.indexOf("score mate ")
        if (mateIdx != -1) {
            val after = line.substring(mateIdx + 11).trim()
            val mateMoves = after.split(" ")[0].toIntOrNull() ?: 0
            return if (mateMoves >= 0) 30000 - mateMoves else -30000 - mateMoves
        }
        return 0
    }

    private fun closeProcess() {
        try {
            writer?.write("quit\n")
            writer?.flush()
        } catch (_: Exception) {}
        try {
            process?.destroyForcibly()
        } catch (_: Exception) {}
        process = null
        writer = null
        reader = null
    }

    override fun close() {
        closeProcess()
        watchdogExecutor.shutdownNow()
    }

    private data class MutablePvAccumulator(
        val rank: Int,
        var scoreCp: Int = 0,
        var nominalNodes: Long? = null,
        var nominalMoves: List<String> = emptyList(),
    )

    private data class ExtensionRunResult(
        val appliedExtensionMoves: List<String>,
        val terminationReason: String,
        val extensionNodes: Long,
    )
}
