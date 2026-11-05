package com.chessecho.research

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

data class EngineEvaluation(
    val fen: String,
    val targetDepth: Int,
    val reachedDepth: Int,
    val bestMove: String,
    val scoreCpSideToMove: Int,
    val scoreCpWhite: Int,
    val top2GapCp: Int,
    val principalVariation: String,
    val nodes: Long,
    val elapsedMs: Long,
    val nps: Long,
    val timedOut: Boolean,
    val passedDepthIntegrity: Boolean,
)

class StockfishResearchRunner(
    private val binaryPath: String = "stockfish",
    private val hashMb: Int = 64,
    private val threads: Int = 1,
    private val maxTotalBudget: Int = 1500,
) : AutoCloseable {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    val totalAnalysesExecuted = AtomicInteger(0)
    val timeoutsEncountered = AtomicInteger(0)

    init {
        startEngine()
    }

    private fun startEngine() {
        val pb = ProcessBuilder(binaryPath)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        process = proc

        val wr = BufferedWriter(OutputStreamWriter(proc.outputStream))
        val rd = BufferedReader(InputStreamReader(proc.inputStream))
        writer = wr
        reader = rd

        sendCommand("uci")
        waitForResponse { it == "uciok" }

        sendCommand("setoption name Threads value $threads")
        sendCommand("setoption name Hash value $hashMb")
        sendCommand("setoption name MultiPV value 2")
        sendCommand("setoption name Ponder value false")
        // Clear Syzygy if present
        sendCommand("setoption name SyzygyPath value <empty>")

        sendCommand("isready")
        waitForResponse { it == "readyok" }
    }

    @Synchronized
    fun evaluatePosition(fen: String, targetDepth: Int): EngineEvaluation {
        val count = totalAnalysesExecuted.incrementAndGet()
        if (count > maxTotalBudget) {
            throw IllegalStateException("Strict Stockfish analysis budget exceeded: $maxTotalBudget position-depth invocations")
        }

        val timeoutMs = when (targetDepth) {
            8 -> 2_000L
            12 -> 5_000L
            16 -> 20_000L
            20 -> 60_000L
            else -> (targetDepth * 1_000L).coerceAtLeast(2_000L)
        }

        // Controlled Cold-Start: TT Reset & Engine Synchronization
        sendCommand("ucinewgame")
        sendCommand("isready")
        waitForResponse { it == "readyok" }

        sendCommand("position fen $fen")
        sendCommand("go depth $targetDepth")

        val startTime = System.currentTimeMillis()
        var bestMove = ""
        var maxDepthSeen = 0
        var score1 = 0
        var score2: Int? = null
        var pv1 = ""
        var nodes = 0L
        var nps = 0L
        var timedOut = false

        val rd = reader ?: throw IllegalStateException("Engine reader not available")
        val deadline = startTime + timeoutMs

        while (true) {
            if (System.currentTimeMillis() > deadline) {
                timedOut = true
                timeoutsEncountered.incrementAndGet()
                sendCommand("stop")
                // read remaining until bestmove with 1s grace period
                val stopDeadline = System.currentTimeMillis() + 1000L
                while (System.currentTimeMillis() < stopDeadline && rd.ready()) {
                    val line = rd.readLine() ?: break
                    if (line.startsWith("bestmove")) {
                        val parts = line.split(" ")
                        if (parts.size >= 2) bestMove = parts[1]
                        break
                    }
                }
                break
            }

            if (!rd.ready()) {
                Thread.sleep(5)
                continue
            }

            val line = rd.readLine() ?: break
            if (line.startsWith("info") && line.contains("score")) {
                val depth = parseTokenInt(line, "depth")
                if (depth != null && depth > maxDepthSeen) {
                    maxDepthSeen = depth
                }

                val multipv = parseTokenInt(line, "multipv") ?: 1
                val score = parseScore(line)
                val lineNodes = parseTokenLong(line, "nodes")
                val lineNps = parseTokenLong(line, "nps")

                if (lineNodes != null) nodes = lineNodes
                if (lineNps != null) nps = lineNps

                if (multipv == 1) {
                    score1 = score
                    val pvIdx = line.indexOf(" pv ")
                    if (pvIdx != -1) {
                        pv1 = line.substring(pvIdx + 4).trim()
                    }
                } else if (multipv == 2) {
                    score2 = score
                }
            } else if (line.startsWith("bestmove")) {
                val parts = line.split(" ")
                if (parts.size >= 2) {
                    bestMove = parts[1]
                }
                break
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        val isWhiteToMove = !fen.contains(" b ")
        val scoreWhite = if (isWhiteToMove) score1 else -score1
        val top2Gap = if (score2 != null) abs(score1 - score2).coerceAtMost(1000) else 1000
        val passedDepthIntegrity = !timedOut && maxDepthSeen >= targetDepth && bestMove.isNotBlank()

        return EngineEvaluation(
            fen = fen,
            targetDepth = targetDepth,
            reachedDepth = maxDepthSeen,
            bestMove = bestMove,
            scoreCpSideToMove = score1,
            scoreCpWhite = scoreWhite,
            top2GapCp = top2Gap,
            principalVariation = pv1,
            nodes = nodes,
            elapsedMs = elapsed,
            nps = nps,
            timedOut = timedOut,
            passedDepthIntegrity = passedDepthIntegrity,
        )
    }

    private fun parseScore(line: String): Int {
        val cpIdx = line.indexOf("score cp ")
        if (cpIdx != -1) {
            val after = line.substring(cpIdx + 9).trim()
            val token = after.split(" ")[0]
            val cp = token.toIntOrNull() ?: 0
            return cp.coerceIn(-1000, 1000)
        }
        val mateIdx = line.indexOf("score mate ")
        if (mateIdx != -1) {
            val after = line.substring(mateIdx + 11).trim()
            val token = after.split(" ")[0]
            val mateMoves = token.toIntOrNull() ?: 1
            return if (mateMoves > 0) 1000 else -1000
        }
        return 0
    }

    private fun parseTokenInt(line: String, token: String): Int? {
        val pattern = " $token "
        val idx = line.indexOf(pattern)
        if (idx == -1) return null
        val after = line.substring(idx + pattern.length).trim()
        val word = after.split(" ")[0]
        return word.toIntOrNull()
    }

    private fun parseTokenLong(line: String, token: String): Long? {
        val pattern = " $token "
        val idx = line.indexOf(pattern)
        if (idx == -1) return null
        val after = line.substring(idx + pattern.length).trim()
        val word = after.split(" ")[0]
        return word.toLongOrNull()
    }

    private fun sendCommand(cmd: String) {
        val wr = writer ?: throw IllegalStateException("Engine writer not available")
        wr.write(cmd)
        wr.newLine()
        wr.flush()
    }

    private fun waitForResponse(timeoutMs: Long = 5000L, predicate: (String) -> Boolean) {
        val rd = reader ?: throw IllegalStateException("Engine reader not available")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (rd.ready()) {
                val line = rd.readLine() ?: break
                if (predicate(line)) return
            } else {
                Thread.sleep(5)
            }
        }
    }

    override fun close() {
        try {
            sendCommand("quit")
            process?.waitFor(1000, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        } finally {
            process?.destroyForcibly()
        }
    }
}
