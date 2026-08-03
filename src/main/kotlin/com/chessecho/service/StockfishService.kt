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

@Service
class StockfishService {
    private val logger = LoggerFactory.getLogger(StockfishService::class.java)

    /**
     * Analyzes a base FEN position and also the resulting positions after a list of historical SAN moves.
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

        // Analyze baseline
        val baselineAnalysis = runGoDepth(fen, null, depth, reader, ::sendCommand)
        results["baseline"] = baselineAnalysis.copy(bestMove = convertUciToSan(fen, baselineAnalysis.bestMove))

        // Convert SAN moves to UCI coordinate moves using kchesslib
        val board = Board()
        board.loadFromFen(fen)
        for (sanMove in historicalSanMoves) {
            try {
                // board.doMove(String) parses SAN moves. We apply and undo to get the UCI string.
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
                val isWhiteToMove = fen.split(" ").getOrNull(1) == "w"
                val evaluatingWhite = if (uciMove == null) isWhiteToMove else !isWhiteToMove
                val parsedScore = parseEngineScore(line, evaluatingWhite)
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
        fun parseEngineScore(
            line: String,
            evaluatingWhite: Boolean,
        ): EvalScore? {
            if (!line.contains(" score ")) return null
            val tokens = line.split("\\s+".toRegex())
            val scoreIdx = tokens.indexOf("score")
            if (scoreIdx != -1 && scoreIdx + 2 < tokens.size) {
                val type = tokens[scoreIdx + 1]
                val value = tokens[scoreIdx + 2].toIntOrNull() ?: return null

                return if (type == "cp") {
                    EvalScore(cp = if (evaluatingWhite) value else -value, mate = null)
                } else if (type == "mate") {
                    EvalScore(cp = null, mate = if (evaluatingWhite) value else -value)
                } else {
                    null
                }
            }
            return null
        }
    }
}
