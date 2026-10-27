package com.chessecho.similarity

import java.util.UUID

interface PositionTokenExtractor {
    fun extractTerms(
        fen: String,
        includeReachability: Boolean,
    ): Map<String, Double>

    fun extractDocument(
        positionId: UUID,
        fen: String,
        includeReachability: Boolean,
    ): PositionDocument
}
