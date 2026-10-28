package com.chessecho.similarity

interface PositionSimilarityCandidateGenerator {
    // Retained strictly for backward compatibility with external/test callers; production service must never invoke this default.
    fun generateCandidates(
        queryTerms: Set<String>,
        maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
    ): List<PositionDocument> = generateCandidates(queryTerms, activeTurn = "w", maxCandidates = maxCandidates)

    fun generateCandidates(
        queryTerms: Set<String>,
        activeTurn: String,
        maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
    ): List<PositionDocument>

    companion object {
        const val DEFAULT_MAX_CANDIDATES = 200
    }
}
