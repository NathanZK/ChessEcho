package com.chessecho.similarity

interface PositionSimilarityCandidateGenerator {
    fun generateCandidates(
        queryTerms: Set<String>,
        maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
    ): List<PositionDocument>

    companion object {
        const val DEFAULT_MAX_CANDIDATES = 200
    }
}
