package com.chessprobe.similarity.clustering

import java.util.UUID

data class PositionClusterVector(
    val positionId: UUID,
    val canonicalFen: String,
    val rawTerms: Map<String, Double>,
    val weightedTerms: Map<String, Double>,
    val pawnSubvectorNorm: Double,
    val pieceSubvectorNorm: Double,
    val totalNorm: Double,
)

fun isPawnToken(term: String): Boolean {
    if (term.isEmpty()) return false
    return when (term[0]) {
        'P', 'p' -> term.startsWith("P(") || term.startsWith("p(")
        'I', 'F', 'l', 'S' -> true
        '\'' -> term.startsWith("'F")
        'w', 'b' -> term.startsWith("w[") || term.startsWith("b[")
        else -> false
    }
}
