package com.chessecho.dto

import com.chessecho.domain.ContinuationMode
import com.chessecho.service.continuation.ContinuationCandidate
import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ContinuationResponse(
    val fen: String,
    val requestedMode: ContinuationMode,
    val effectiveProvider: String,
    val candidates: List<ContinuationCandidate>,
)
