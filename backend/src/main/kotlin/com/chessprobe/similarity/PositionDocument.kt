package com.chessprobe.similarity

import java.util.UUID

enum class PositionAnalysisOutcome {
    EVALUATED_WITH_TOKENS,
    EVALUATED_ZERO_TOKENS,
    INCOMPLETE_RESOLUTION,
    UNANALYZED,
    PENDING,
    FAILED,
}

data class PositionDocument(
    val positionId: UUID,
    val fen: String,
    val canonicalFen: String,
    val terms: Map<String, Double>,
    val documentLength: Double = terms.values.sum(),
    val sideToMove: String = parseSideToMove(canonicalFen),
    val analysisOutcome: PositionAnalysisOutcome =
        if (terms.isNotEmpty()) {
            PositionAnalysisOutcome.EVALUATED_WITH_TOKENS
        } else {
            PositionAnalysisOutcome.EVALUATED_ZERO_TOKENS
        },
) {
    companion object {
        fun parseSideToMove(fen: String): String =
            fen
                .trim()
                .split("\\s+".toRegex())
                .getOrElse(1) { "w" }
                .lowercase()

        fun canonicalizeFen(fen: String): String {
            val fields = fen.trim().split("\\s+".toRegex())
            return if (fields.size >= 4) {
                "${fields[0]} ${fields[1]} ${fields[2]} ${fields[3]}"
            } else {
                fen.trim()
            }
        }
    }
}
