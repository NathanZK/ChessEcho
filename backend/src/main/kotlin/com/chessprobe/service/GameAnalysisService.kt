package com.chessprobe.service

import com.chessprobe.domain.Position
import com.chessprobe.domain.PositionOccurrence
import com.chessprobe.domain.TimeControl
import com.chessprobe.dto.AnalyzedPlyDto
import com.chessprobe.dto.GameAnalysisResponseDto
import com.chessprobe.dto.GameAnalysisStatus
import com.chessprobe.repository.EngineAnalysisRepository
import com.chessprobe.repository.GameRepository
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.service.auth.AuthenticatedPrincipal
import com.chessprobe.service.similarity.SimilarityConcurrencyManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class GameAnalysisService(
    private val gameRepository: GameRepository,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    private val engineAnalysisService: EngineAnalysisService,
    private val similarityConcurrencyManager: SimilarityConcurrencyManager,
    private val decisionTimingPolicy: DecisionTimingPolicy,
) {
    @Autowired(required = false)
    private var accountOwnershipService: AccountOwnershipService? = null

    @Transactional
    fun analyzeGame(
        gameId: UUID,
        principal: AuthenticatedPrincipal?,
    ): GameAnalysisResponseDto {
        val game =
            gameRepository
                .findById(gameId)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found: $gameId") }

        accountOwnershipService?.resolveSharedAccount(game.chessAccount.id, principal)

        val occurrences = positionOccurrenceRepository.findAllByGameIdOrderByPlyNumberAsc(gameId)
        val positionIds = occurrences.map { it.position.id }.toSet()
        val existingAnalyses =
            if (positionIds.isEmpty()) {
                mutableMapOf()
            } else {
                engineAnalysisRepository
                    .findByPositionIdInWithMoveEvaluations(positionIds)
                    .associateBy { it.position.id }
                    .toMutableMap()
            }

        val missingPositions = findMissingPositions(occurrences, existingAnalyses)
        val status = analyzeMissingPositions(missingPositions, existingAnalyses)

        val timeControl = TimeControl.fromExternal(game.timeControl)
        val plies =
            occurrences.map { occ ->
                buildPlyDto(occ, existingAnalyses[occ.position.id], timeControl)
            }

        return GameAnalysisResponseDto(
            gameId = game.id,
            whiteUsername = game.whiteUsername ?: "",
            blackUsername = game.blackUsername ?: "",
            timeControl = game.timeControl,
            playedAt = game.playedAt,
            result = game.result,
            analysisStatus = status,
            plies = plies,
        )
    }

    private fun findMissingPositions(
        occurrences: List<PositionOccurrence>,
        existingAnalyses: Map<UUID, com.chessprobe.domain.EngineAnalysis>,
    ): List<Position> {
        val missing = mutableListOf<Position>()
        for (occ in occurrences) {
            val analysis = existingAnalyses[occ.position.id]
            val hasMoveEvaluation = analysis?.moveEvaluations?.any { it.move == occ.movePlayed } == true
            if ((analysis == null || !hasMoveEvaluation) && missing.none { it.id == occ.position.id }) {
                missing.add(occ.position)
            }
        }
        return missing
    }

    private fun analyzeMissingPositions(
        missingPositions: List<Position>,
        existingAnalyses: MutableMap<UUID, com.chessprobe.domain.EngineAnalysis>,
    ): GameAnalysisStatus {
        if (missingPositions.isEmpty()) return GameAnalysisStatus.COMPLETE

        var status = GameAnalysisStatus.COMPLETE
        similarityConcurrencyManager.withPermitOrFallback(
            onPermitAcquired = {
                for (pos in missingPositions) {
                    engineAnalysisService.analyzePosition(pos)
                    val freshlyAnalyzed = engineAnalysisRepository.findByPositionId(pos.id)
                    if (freshlyAnalyzed != null) {
                        existingAnalyses[pos.id] = freshlyAnalyzed
                    }
                }
                true
            },
            onCapacityExceeded = {
                status = GameAnalysisStatus.CAPACITY_EXCEEDED
                false
            },
        )
        return status
    }

    private fun buildPlyDto(
        occ: PositionOccurrence,
        analysis: com.chessprobe.domain.EngineAnalysis?,
        timeControl: TimeControl?,
    ): AnalyzedPlyDto {
        val moveEval = analysis?.moveEvaluations?.firstOrNull { it.move == occ.movePlayed }
        val bestScore =
            if (analysis?.bestMoveEvalCp != null || analysis?.bestMoveEvalMate != null) {
                EvalScore(analysis.bestMoveEvalCp, analysis.bestMoveEvalMate)
            } else {
                null
            }
        val moveScore =
            if (moveEval?.evalCp != null || moveEval?.evalMate != null) {
                EvalScore(moveEval.evalCp, moveEval.evalMate)
            } else {
                null
            }

        val evalLoss = moveEval?.evalLossFromBest ?: EvaluationLossPolicy.calculateEvalLoss(bestScore, moveScore)
        val weaknessSeverity = EvaluationLossPolicy.classifySeverity(evalLoss)
        val decisionSeverity =
            if (occ.decisionTimeMs != null) {
                decisionTimingPolicy.classify(timeControl, occ.decisionTimeMs)
            } else {
                null
            }

        return AnalyzedPlyDto(
            plyNumber = occ.plyNumber,
            movePlayed = occ.movePlayed,
            playerColor = occ.playerColor,
            preMoveFen = occ.position.fen,
            decisionTimeMs = occ.decisionTimeMs,
            decisionSeverity = decisionSeverity,
            bestMove = analysis?.bestMove,
            bestEvalCp = analysis?.bestMoveEvalCp,
            evalCp = moveEval?.evalCp,
            evalLoss = evalLoss,
            weaknessSeverity = weaknessSeverity,
        )
    }
}
