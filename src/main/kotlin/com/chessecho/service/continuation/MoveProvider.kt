package com.chessecho.service.continuation

data class ContinuationCandidate(
    val move: String,
    val resultingFen: String,
    val providerType: String,
    val evalCp: Int? = null,
    val evalLoss: Double? = null,
    val timesPlayed: Int? = null,
)

interface MoveProvider {
    val providerType: String

    /**
     * Obtains continuation candidate moves for a given position in FEN format.
     *
     * @param fen The chess position in FEN notation.
     * @return List of [ContinuationCandidate] containing candidate moves, resulting FENs, and provider metadata.
     */
    fun getContinuationCandidates(fen: String): List<ContinuationCandidate>
}
