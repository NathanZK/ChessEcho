package com.chessprobe.dto

import com.chessprobe.domain.DecisionSeverity
import com.chessprobe.domain.PositionWeaknessSeverity
import java.time.Instant
import java.util.UUID

enum class GameAnalysisStatus {
    COMPLETE,
    CAPACITY_EXCEEDED,
}

data class AnalyzedPlyDto(
    val plyNumber: Int,
    val movePlayed: String,
    val playerColor: String,
    val preMoveFen: String,
    val decisionTimeMs: Long?,
    val decisionSeverity: DecisionSeverity?,
    val bestMove: String?,
    val bestEvalCp: Int?,
    val evalCp: Int?,
    val evalLoss: Double?,
    val weaknessSeverity: PositionWeaknessSeverity?,
)

data class GameAnalysisResponseDto(
    val gameId: UUID,
    val whiteUsername: String,
    val blackUsername: String,
    val timeControl: String?,
    val playedAt: Instant?,
    val result: String?,
    val analysisStatus: GameAnalysisStatus,
    val plies: List<AnalyzedPlyDto>,
)
