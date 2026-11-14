package com.chessprobe.service

import java.io.BufferedReader

data class FullPvAnalysisResult(
    val achievedDepth: Int,
    val moves: List<String>,
    val evalCentipawns: Int? = null,
    val isMate: Boolean = false,
    val mateDepth: Int? = null,
)

object FullPvParser {
    fun parseFullPvOutput(reader: BufferedReader): FullPvAnalysisResult {
        var result = FullPvAnalysisResult(0, emptyList())
        while (true) {
            val line = reader.readLine() ?: break
            if (line.startsWith("bestmove")) break
            extractFullPvLine(line)?.let { result = it }
        }
        return result
    }

    fun extractFullPvLine(line: String): FullPvAnalysisResult? {
        val parsed = StockfishService.parsePvUpdate(line) ?: return null
        if (parsed.rank != 1) return null

        val score = StockfishService.parseEngineScore(line, invert = false)
        return FullPvAnalysisResult(parsed.achievedDepth, parsed.moves, score?.cp, score?.mate != null, score?.mate)
    }
}
