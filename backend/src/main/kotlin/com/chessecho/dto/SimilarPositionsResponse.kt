package com.chessecho.dto

import java.time.Instant
import java.util.UUID

data class SimilarPositionsResponse(
    val queryFen: String,
    val candidatesEvaluated: Int,
    val shortlistedCandidatesCount: Int,
    val engineCompletedCount: Int,
    val dynamicSimilarityApplied: Boolean,
    val fallbackReason: String?,
    val queryStatus: String?,
    val queryAchievedDepth: Int,
    val queryPrincipalVariations: List<PrincipalVariationDto>,
    val results: List<SimilarPositionResult>,
)

data class PrincipalVariationDto(
    val rank: Int,
    val moves: List<String>,
    val achievedDepth: Int,
)

data class SimilarPositionResult(
    val rank: Int,
    val score: Double,
    val staticScore: Double,
    val dynamicScore: Double,
    val positionId: UUID,
    val fen: String,
    val canonicalFen: String,
    val searchStatus: String?,
    val achievedDepth: Int,
    val principalVariations: List<PrincipalVariationDto>,
    val occurrences: List<SimilarPositionOccurrenceDto>,
)

data class SimilarPositionOccurrenceDto(
    val id: UUID,
    val platformGameId: String,
    val playedMove: String,
    val plyNumber: Int,
    val playerColor: String,
    val playedAt: Instant?,
    val opponentUsername: String?,
    val timeControl: String?,
    val gameResult: String?,
    val chessComUrl: String?,
)
