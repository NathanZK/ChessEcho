package com.chessecho.service

import com.chessecho.service.continuation.TerminationReason
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class EvalScore(val cp: Int?, val mate: Int?)

data class PositionAnalysis(
    val bestMove: String,
    val score: EvalScore,
)

data class EngineCandidate(
    val move: String,
    val score: EvalScore,
)

data class QuiescenceOptions(
    val maxDepth: Int = 8,
    val maxNodes: Long = 5000L,
    val timeoutMs: Long = 250L,
    val maxPlies: Int = 10,
)

data class PvContinuationResult(
    val originalFen: String,
    val terminalFen: String,
    val moves: List<String>,
    val sanMoves: List<String>,
    val score: EvalScore?,
    val pliesResolved: Int,
    val isTerminal: Boolean,
    val terminationReason: TerminationReason,
)

@Service
class StockfishService(
    private val processStarter: (List<String>) -> Process = { ProcessBuilder(it).start() },
) {
    private val logger = LoggerFactory.getLogger(StockfishService::class.java)

    private val engineExecutor =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable).apply { isDaemon = true }
        }

    /**
     * Executes engine MultiPV analysis for a given FEN position to determine the top-N engine candidate moves.
     *
     * @param fen The baseline FEN position
     * @param depth The depth to run Stockfish to
     * @param multiPv The number of top principal variation lines to search
     * @return List of [EngineCandidate] containing SAN move and normalized evaluation score
     */
    fun analyzeMultiPv(
        fen: String,
        depth: Int,
        multiPv: Int,
    ): List<EngineCandidate> {
        val process = ProcessBuilder("stockfish").start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val writer = OutputStreamWriter(process.outputStream)

        fun sendCommand(cmd: String) {
            writer.write("$cmd\n")
            writer.flush()
        }

        val rankMap =
            try {
                sendCommand("uci")
                waitForToken(reader, "uciok")
                sendCommand("setoption name MultiPV value $multiPv")
                sendCommand("isready")
                waitForToken(reader, "readyok")
                sendCommand("position fen $fen")
                sendCommand("go depth $depth")
                collectMultiPvRanks(reader)
            } finally {
                terminateProcess(process, ::sendCommand)
            }

        return toEngineCandidates(fen, rankMap)
    }

    private fun waitForToken(
        reader: BufferedReader,
        token: String,
    ) {
        while (true) {
            val line = reader.readLine() ?: break
            if (line == token) break
        }
    }

    private fun collectMultiPvRanks(reader: BufferedReader): Map<Int, Pair<String, EvalScore>> {
        val rankMap = mutableMapOf<Int, Pair<String, EvalScore>>()
        while (true) {
            val line = reader.readLine() ?: break

            if (line.startsWith("info ") && line.contains(" multipv ")) {
                val parsed = parseMultiPvLine(line)
                if (parsed != null) {
                    val (rank, uciMove, score) = parsed
                    rankMap[rank] = Pair(uciMove, score)
                }
            }

            if (line.startsWith("bestmove")) {
                break
            }
        }
        return rankMap
    }

    private fun terminateProcess(
        process: Process,
        sendCommand: (String) -> Unit,
    ) {
        try {
            sendCommand("quit")
            process.waitFor(5, TimeUnit.SECONDS)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    private fun toEngineCandidates(
        fen: String,
        rankMap: Map<Int, Pair<String, EvalScore>>,
    ): List<EngineCandidate> =
        rankMap.entries
            .sortedBy { it.key }
            .mapNotNull { entry ->
                val rank = entry.key
                val (uciMove, score) = entry.value
                val sanMove = convertUciToSan(fen, uciMove)
                if (sanMove.isBlank() || sanMove == "(none)") {
                    null
                } else {
                    val evalStr = score.cp?.let { "${it}cp" } ?: score.mate?.let { "mate $it" } ?: "N/A"
                    logger.debug("MultiPV rank output: rank={} uciMove={} sanMove={} score={}", rank, uciMove, sanMove, evalStr)
                    EngineCandidate(move = sanMove, score = score)
                }
            }

    /**
     * Analyzes an untouched base FEN position independently to determine its baseline evaluation and engine best move,
     * and separately evaluates the resulting position after each historical SAN move.
     *
     * Evaluation Perspective Normalization:
     * Stockfish UCI outputs scores relative to whichever side is to move in the evaluated position.
     * - Baseline (`uciMove == null`): Evaluates the untouched position where the user is to move. Score is returned as-is.
     * - Historical Move (`uciMove != null`): Evaluates the position 1 ply later where the opponent is to move. The score
     *   is inverted (`invert = true`) so that all returned scores are normalized to the baseline player-to-move perspective.
     *   This allows direct subtraction (`bestMoveEvalCp - moveEvalCp`) when computing evaluation loss.
     */
    fun analyze(
        fen: String,
        depth: Int,
        historicalSanMoves: List<String>,
    ): Map<String, PositionAnalysis> {
        val process = ProcessBuilder("stockfish").start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val writer = OutputStreamWriter(process.outputStream)

        fun sendCommand(cmd: String) {
            writer.write("$cmd\n")
            writer.flush()
        }

        sendCommand("uci")
        var line: String?
        while (true) {
            line = reader.readLine()
            if (line == "uciok" || line == null) break
        }

        val results = mutableMapOf<String, PositionAnalysis>()

        // 1. Analyze baseline position independently (untouched FEN with no move applied)
        val baselineAnalysis = runGoDepth(fen, null, depth, reader, ::sendCommand)
        results["baseline"] = baselineAnalysis.copy(bestMove = convertUciToSan(fen, baselineAnalysis.bestMove))

        // 2. Separately analyze each user-played historical SAN move from the same base FEN
        val board = Board()
        board.loadFromFen(fen)
        for (sanMove in historicalSanMoves) {
            try {
                if (board.doMove(sanMove)) {
                    val move = board.undoMove()
                    val uciMove = move.toString()
                    val moveAnalysis = runGoDepth(fen, uciMove, depth, reader, ::sendCommand)

                    board.doMove(move)
                    results[sanMove] = moveAnalysis.copy(bestMove = convertUciToSan(board.fen, moveAnalysis.bestMove))
                    board.undoMove()
                } else {
                    logger.warn("Could not parse SAN: $sanMove in FEN: $fen")
                }
            } catch (e: Exception) {
                logger.error("Failed to parse/analyze move $sanMove", e)
            }
        }

        sendCommand("quit")
        process.waitFor(5, TimeUnit.SECONDS)
        if (process.isAlive) {
            process.destroyForcibly()
        }
        return results
    }

    /**
     * Evaluates a single SAN move played from position [fen] at depth [depth].
     * Normalizes the score to the perspective of the player making the move in [fen].
     */
    fun evaluateSingleMove(
        fen: String,
        sanMove: String,
        depth: Int = 16,
    ): PositionAnalysis? {
        val board = Board()
        try {
            board.loadFromFen(fen)
            if (!board.doMove(sanMove)) {
                return null
            }
            val move = board.undoMove()
            val uciMove = move.toString()

            val process = ProcessBuilder("stockfish").start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val writer = OutputStreamWriter(process.outputStream)

            fun sendCommand(cmd: String) {
                writer.write("$cmd\n")
                writer.flush()
            }

            sendCommand("uci")
            var line: String?
            while (true) {
                line = reader.readLine()
                if (line == "uciok" || line == null) break
            }

            val moveAnalysis = runGoDepth(fen, uciMove, depth, reader, ::sendCommand)

            sendCommand("quit")
            process.waitFor(5, TimeUnit.SECONDS)
            if (process.isAlive) {
                process.destroyForcibly()
            }
            return moveAnalysis
        } catch (e: Exception) {
            logger.error("Failed to evaluate single move $sanMove in FEN $fen", e)
            return null
        }
    }

    /**
     * Executes the `go depth` UCI command for a specific FEN and optional move sequence.
     *
     * @param fen The baseline FEN position
     * @param uciMove An optional move in UCI coordinate notation to evaluate the resulting position
     * @param depth The depth to run the engine to
     * @param reader The BufferedReader connected to Stockfish's stdout
     * @param sendCommand A function to send commands to Stockfish's stdin
     * @return The analysis results containing best move and evaluation score
     */
    internal fun runGoDepth(
        fen: String,
        uciMove: String?,
        depth: Int,
        reader: BufferedReader,
        sendCommand: (String) -> Unit,
    ): PositionAnalysis {
        val movesStr = if (uciMove == null) "" else " moves $uciMove"
        sendCommand("position fen $fen$movesStr")
        sendCommand("go depth $depth")

        var bestMove = ""
        var cp: Int? = null
        var mate: Int? = null

        while (true) {
            val line = reader.readLine() ?: break

            if (line.startsWith("info ") && line.contains(" score ")) {
                val parsedScore = parseEngineScore(line, invert = uciMove != null)
                if (parsedScore != null) {
                    cp = parsedScore.cp
                    mate = parsedScore.mate
                }
            }

            if (line.startsWith("bestmove")) {
                bestMove = line.split("\\s+".toRegex()).getOrNull(1) ?: ""
                break
            }
        }
        return PositionAnalysis(bestMove = bestMove, score = EvalScore(cp, mate))
    }

    private fun convertUciToSan(
        fen: String,
        uci: String,
    ): String {
        if (uci.isBlank() || uci == "(none)") return ""
        val board = Board()
        board.loadFromFen(fen)
        val legalMoves = board.legalMoves()
        val move = legalMoves.find { it.toString() == uci } ?: return uci
        val ml = MoveList(fen)
        ml.add(move)
        return ml.toSan().trim()
    }

    /**
     * Resolves a tactical continuation from a position using bounded Stockfish search and forcing PV replay.
     */
    fun resolveForcingPvContinuation(
        fen: String,
        options: QuiescenceOptions = QuiescenceOptions(),
    ): PvContinuationResult {
        val board = validateAndLoadBoard(fen)
        val terminalRoot = checkTerminalRoot(board, fen)
        if (terminalRoot != null) {
            return terminalRoot
        }

        val context = SubprocessContext(processStarter)
        val task =
            engineExecutor.submit<PvContinuationResult> {
                try {
                    context.start()
                    val output = executeSearchWithOutput(context, fen, options)
                    replayForcingPv(board, fen, output.pvMoves, output.score, options, output.nodeLimitReached)
                } finally {
                    context.close()
                }
            }

        return try {
            task.get(options.timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            task.cancel(true)
            context.close()
            logger.debug("Forcing PV search timed out after {}ms for FEN {}", options.timeoutMs, fen, e)
            buildFallbackResult(fen, TerminationReason.TIMEOUT)
        } catch (e: Exception) {
            task.cancel(true)
            context.close()
            logger.warn("Forcing PV search failed for FEN {}", fen, e)
            buildFallbackResult(fen, TerminationReason.ENGINE_ERROR)
        }
    }

    /**
     * Resolves a tactical continuation from a position with a provided reader and sendCommand,
     * enabling deterministic unit testing of the search output parsing and move replay.
     */
    fun resolveForcingPvContinuation(
        fen: String,
        options: QuiescenceOptions = QuiescenceOptions(),
        reader: BufferedReader,
        sendCommand: (String) -> Unit = {},
    ): PvContinuationResult {
        val board = validateAndLoadBoard(fen)
        val terminalRoot = checkTerminalRoot(board, fen)
        if (terminalRoot != null) {
            return terminalRoot
        }
        sendCommand("uci\nisready\nposition fen $fen\ngo depth ${options.maxDepth} nodes ${options.maxNodes}")
        val output = readSearchOutput(reader, sendCommand, options)
        return replayForcingPv(board, fen, output.pvMoves, output.score, options, output.nodeLimitReached)
    }

    private fun validateAndLoadBoard(fen: String): Board {
        if (fen.isBlank() || fen.split(" ").size < 4) {
            throw IllegalArgumentException("Invalid FEN: $fen")
        }
        val board = Board()
        try {
            board.loadFromFen(fen)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid FEN: $fen", e)
        }
        return board
    }

    private fun checkTerminalRoot(
        board: Board,
        fen: String,
    ): PvContinuationResult? {
        if (!board.isMated && !board.isDraw && !board.isStaleMate) {
            return null
        }
        return buildFallbackResult(fen, TerminationReason.TERMINAL).copy(isTerminal = true)
    }

    private class SubprocessContext(
        private val processStarter: (List<String>) -> Process,
    ) : AutoCloseable {
        private val logger = LoggerFactory.getLogger(SubprocessContext::class.java)
        private val lock = Any()

        @Volatile
        private var closed = false

        var process: Process? = null
        var reader: BufferedReader? = null
        var writer: OutputStreamWriter? = null

        fun start() {
            synchronized(lock) {
                if (closed) return
                val proc = processStarter(listOf("stockfish"))
                process = proc
                reader = BufferedReader(InputStreamReader(proc.inputStream))
                writer = OutputStreamWriter(proc.outputStream)
            }
        }

        fun send(cmd: String) {
            val w = writer ?: throw IllegalStateException("Writer unavailable")
            w.write("$cmd\n")
            w.flush()
        }

        fun isClosed(): Boolean = closed

        override fun close() {
            synchronized(lock) {
                closed = true
                try {
                    reader?.close()
                } catch (e: Exception) {
                    logger.debug("Reader close exception", e)
                }
                try {
                    writer?.close()
                } catch (e: Exception) {
                    logger.debug("Writer close exception", e)
                }
                val proc = process
                if (proc != null) {
                    proc.destroyForcibly()
                    try {
                        proc.waitFor()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        logger.debug("Interrupted during process wait", e)
                    }
                }
            }
        }
    }

    private data class SearchOutput(
        val pvMoves: List<String>,
        val score: EvalScore?,
        val nodeLimitReached: Boolean,
    )

    private fun executeSearchWithOutput(
        context: SubprocessContext,
        fen: String,
        options: QuiescenceOptions,
    ): SearchOutput {
        if (context.isClosed()) {
            return SearchOutput(emptyList(), null, false)
        }
        context.send("uci\nisready\nposition fen $fen\ngo depth ${options.maxDepth} nodes ${options.maxNodes}")
        val r = context.reader ?: throw IllegalStateException("Reader unavailable")
        return readSearchOutput(r, context::send, options)
    }

    private fun readSearchOutput(
        reader: BufferedReader,
        sendCommand: (String) -> Unit,
        options: QuiescenceOptions,
    ): SearchOutput {
        var state = SearchOutput(emptyList(), null, false)
        while (true) {
            val line = reader.readLine() ?: break
            if (line.startsWith("info ")) {
                state = parseSearchInfo(line, options.maxNodes, state)
            } else if (line.startsWith("bestmove")) {
                break
            }
        }
        sendCommand("quit")
        return state
    }

    private fun parseSearchInfo(
        line: String,
        maxNodes: Long,
        current: SearchOutput,
    ): SearchOutput {
        val score = parseEngineScore(line, invert = true) ?: current.score
        val nodeLimitReached = current.nodeLimitReached || isNodeLimitLine(line, maxNodes)
        val extractedPv = extractPvMoves(line)
        val pvMoves = if (extractedPv.isNotEmpty()) extractedPv else current.pvMoves
        return SearchOutput(pvMoves, score, nodeLimitReached)
    }

    private fun isNodeLimitLine(
        line: String,
        maxNodes: Long,
    ): Boolean {
        if (!line.contains(" nodes ")) return false
        val tokens = line.split("\\s+".toRegex())
        val idx = tokens.indexOf("nodes")
        if (idx == -1 || idx + 1 >= tokens.size) return false
        val nodes = tokens[idx + 1].toLongOrNull() ?: return false
        return nodes >= maxNodes
    }

    private fun extractPvMoves(line: String): List<String> {
        if (!line.contains(" pv ")) return emptyList()
        val tokens = line.split("\\s+".toRegex())
        val idx = tokens.indexOf("pv")
        if (idx == -1 || idx + 1 >= tokens.size) return emptyList()
        return tokens.subList(idx + 1, tokens.size).filter { it.isNotBlank() }
    }

    private fun replayForcingPv(
        board: Board,
        originalFen: String,
        pvMoves: List<String>,
        score: EvalScore?,
        options: QuiescenceOptions,
        nodeLimitReached: Boolean,
    ): PvContinuationResult {
        if (pvMoves.isEmpty()) {
            val reason = if (nodeLimitReached) TerminationReason.NODE_LIMIT else TerminationReason.QUIET_STOP
            return buildFallbackResult(originalFen, reason).copy(score = score)
        }

        val appliedUci = mutableListOf<String>()
        val appliedSan = mutableListOf<String>()
        var terminationReason = TerminationReason.PV_EXHAUSTED
        var isTerminal = false

        for (uciMove in pvMoves) {
            if (appliedUci.size >= options.maxPlies) {
                terminationReason = TerminationReason.PLY_LIMIT
                break
            }
            val outcome = applyNextForcingMove(board, uciMove, appliedUci, appliedSan)
            if (outcome != null) {
                terminationReason = outcome.first
                isTerminal = outcome.second
                break
            }
        }

        return PvContinuationResult(
            originalFen = originalFen,
            terminalFen = board.fen,
            moves = appliedUci,
            sanMoves = appliedSan,
            score = score,
            pliesResolved = appliedUci.size,
            isTerminal = isTerminal,
            terminationReason = terminationReason,
        )
    }

    private fun applyNextForcingMove(
        board: Board,
        uciMove: String,
        appliedUci: MutableList<String>,
        appliedSan: MutableList<String>,
    ): Pair<TerminationReason, Boolean>? {
        val move =
            board.legalMoves().find { it.toString() == uciMove }
                ?: return Pair(TerminationReason.PV_EXHAUSTED, false)

        if (!isForcingMove(board, move)) {
            return Pair(TerminationReason.QUIET_STOP, false)
        }

        val ml = MoveList(board.fen)
        ml.add(move)
        val san = ml.toSan().trim()

        board.doMove(move)
        appliedUci.add(uciMove)
        appliedSan.add(san)

        if (board.isMated || board.isDraw || board.isStaleMate) {
            return Pair(TerminationReason.TERMINAL, true)
        }
        return null
    }

    private fun buildFallbackResult(
        fen: String,
        reason: TerminationReason,
    ): PvContinuationResult =
        PvContinuationResult(
            originalFen = fen,
            terminalFen = fen,
            moves = emptyList(),
            sanMoves = emptyList(),
            score = null,
            pliesResolved = 0,
            isTerminal = false,
            terminationReason = reason,
        )

    private fun isForcingMove(
        board: Board,
        move: Move,
    ): Boolean {
        if (board.isKingAttacked) {
            return true
        }
        val isCapture =
            board.getPiece(move.to) != Piece.NONE ||
                (board.getPiece(move.from).pieceType == PieceType.PAWN && move.from.file != move.to.file)
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

    companion object {
        fun parseMultiPvLine(line: String): Triple<Int, String, EvalScore>? {
            if (!line.contains(" multipv ") || !line.contains(" pv ")) return null
            val tokens = line.split("\\s+".toRegex())

            val multipvIdx = tokens.indexOf("multipv")
            if (multipvIdx == -1 || multipvIdx + 1 >= tokens.size) return null
            val rank = tokens[multipvIdx + 1].toIntOrNull() ?: return null

            val pvIdx = tokens.indexOf("pv")
            if (pvIdx == -1 || pvIdx + 1 >= tokens.size) return null
            val uciMove = tokens[pvIdx + 1]

            val score = parseEngineScore(line, invert = false) ?: return null

            return Triple(rank, uciMove, score)
        }

        fun parseEngineScore(
            line: String,
            invert: Boolean = false,
        ): EvalScore? {
            if (!line.contains(" score ")) return null
            val tokens = line.split("\\s+".toRegex())
            val scoreIdx = tokens.indexOf("score")
            if (scoreIdx == -1 || scoreIdx + 2 >= tokens.size) return null

            val type = tokens[scoreIdx + 1]
            val value = tokens[scoreIdx + 2].toIntOrNull() ?: return null

            return when (type) {
                "cp" -> parseCpScore(value, invert)
                "mate" -> parseMateScore(value, invert)
                else -> null
            }
        }

        private fun parseCpScore(
            value: Int,
            invert: Boolean,
        ): EvalScore = EvalScore(cp = if (invert) -value else value, mate = null)

        private fun parseMateScore(
            value: Int,
            invert: Boolean,
        ): EvalScore? =
            if (!invert) {
                if (value == 0) null else EvalScore(cp = null, mate = value)
            } else {
                when {
                    value == 0 -> EvalScore(cp = null, mate = 1)
                    value < 0 -> EvalScore(cp = null, mate = -value + 1)
                    else -> EvalScore(cp = null, mate = -value)
                }
            }
    }
}
