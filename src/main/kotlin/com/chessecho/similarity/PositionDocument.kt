package com.chessecho.similarity

import java.util.UUID

data class PositionDocument(
    val positionId: UUID,
    val fen: String,
    val canonicalFen: String,
    val terms: Map<String, Double>,
    val documentLength: Double = terms.values.sum(),
)
