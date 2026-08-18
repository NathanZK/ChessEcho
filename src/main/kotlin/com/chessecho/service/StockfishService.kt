package com.chessecho.service

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.MoveList
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

data class EvalScore(val cp: Int?, val mate: Int?)

data class PositionAnalysis(
    val bestMove: String,
    val score: EvalScore,
)

data class EngineCandidate(
    val move: String,
    val score: EvalScore,
)

@Service
class StockfishService {
    private val logger = LoggerFactory.getLogger(StockfishService::class.java)

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

        sendCommand("uci")
        var line: String?
        while (true) {
            line = reader.readLine()
            if (line == "uciok" || line == null) break
        }

        sendCommand("setoption name MultiPV value $multiPv")
        sendCommand("isready")
        while (true) {
            line = reader.readLine()
            if (line == "readyok" || line == null) break
        }

        sendCommand("position fen $fen")
        sendCommand("go depth $depth")

        val rankMap = mutableMapOf<Int, Pair<String, EvalScore>>()

        while (true) {
            line = reader.readLine() ?: break

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

        sendCommand("quit")
        process.waitFor(5, TimeUnit.SECONDS)
        if (process.isAlive) {
            process.destroyForcibly()
        }

        return rankMap.entries
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
     * Executes the `go depth` UCI command for a specific FEN and optional move sequence.
     *
     * @param fen The baseline FEN position
     * @param uciMove An optional move in UCI coordinate notation to evaluate the resulting position
     * @param depth The depth to run the engine to
     * @param reader The BufferedReader connected to Stockfish's stdout
     * @param sendCommand A function to send commands to Stockfish's stdin
     * @return The analysis results containing best move and evaluation score
     */
    private fun runGoDepth(
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

            if (line.startsWith("info depth $depth ") || line.startsWith("info depth $depth\t")) {
                // Invert score for historical moves (1 ply later) to normalize to baseline player-to-move perspective
                val invertScore = uciMove != null
                val parsedScore = parseEngineScore(line, invertScore)
                if (parsedScore != null) {
                    if (parsedScore.cp != null) cp = parsedScore.cp
                    if (parsedScore.mate != null) mate = parsedScore.mate
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
            if (scoreIdx != -1 && scoreIdx + 2 < tokens.size) {
                val type = tokens[scoreIdx + 1]
                val value = tokens[scoreIdx + 2].toIntOrNull() ?: return null

                return if (type == "cp") {
                    EvalScore(cp = if (invert) -value else value, mate = null)
                } else if (type == "mate") {
                    EvalScore(cp = null, mate = if (invert) -value else value)
                } else {
                    null
                }
            }
            return null
        }
    }
}
