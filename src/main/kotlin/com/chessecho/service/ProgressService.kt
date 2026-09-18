package com.chessecho.service

import com.chessecho.domain.PlayerColor
import com.chessecho.dto.ProgressPoint
import com.chessecho.dto.ProgressResponse
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProgressService(
    private val chessAccountRepository: ChessAccountRepository,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
) {
    @Transactional(readOnly = true)
    fun getProgress(
        positionId: UUID,
        playerColor: PlayerColor,
        principal: AuthenticatedPrincipal,
    ): ProgressResponse {
        val account =
            chessAccountRepository.findAllByUserIdOrderByCreatedAtAsc(principal.appUserId)
                .firstOrNull { account ->
                    positionOccurrenceRepository
                        .findProgressOccurrences(account.id, positionId, playerColor.name)
                        .isNotEmpty()
                }
                ?: throw AccountNotFoundException("No owned account has progress for position $positionId")

        val occurrences = positionOccurrenceRepository.findProgressOccurrences(account.id, positionId, playerColor.name)
        val analysis = engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)
        val evaluations = analysis?.moveEvaluations?.associateBy { it.move }.orEmpty()
        val threshold = 0.8
        val observations =
            occurrences.map { occurrence ->
                val loss = evaluations[occurrence.movePlayed]?.evalLossFromBest ?: 0.0
                val won =
                    when (occurrence.playerColor) {
                        "WHITE" -> occurrence.game.result.equals("1-0", ignoreCase = true)
                        "BLACK" -> occurrence.game.result.equals("0-1", ignoreCase = true)
                        else -> false
                    }
                Observation(occurrence.game.playedAt ?: occurrence.createdAt, loss >= threshold, won)
            }
        val points =
            observations
                .runningFold(Accumulator()) { accumulator, observation ->
                    accumulator.add(observation)
                }.drop(1)
                .map { it.toPoint() }
        val first = points.firstOrNull()
        val last = points.lastOrNull()
        val mistakeChange =
            first?.let {
                if (it.mistakeRate == 0.0) null else (last!!.mistakeRate - it.mistakeRate) / it.mistakeRate * 100
            }
        val winChange =
            first?.let {
                if (it.winRate == 0.0) null else (last!!.winRate - it.winRate) / it.winRate * 100
            }
        val assessment =
            when {
                points.size < 2 -> "More played encounters are needed to assess progress."
                mistakeChange != null && mistakeChange < 0 -> "You are making fewer mistakes at this position."
                mistakeChange != null && mistakeChange > 0 -> "You are making more mistakes at this position."
                else -> "Your performance at this position is stable."
            }
        return ProgressResponse(positionId, playerColor.name, points, mistakeChange, winChange, assessment)
    }

    private data class Observation(val occurredAt: java.time.Instant, val mistake: Boolean, val win: Boolean)

    private data class Accumulator(
        val occurredAt: java.time.Instant? = null,
        val attempts: Int = 0,
        val mistakes: Int = 0,
        val wins: Int = 0,
    ) {
        fun add(observation: Observation) =
            copy(
                occurredAt = observation.occurredAt,
                attempts = attempts + 1,
                mistakes = mistakes + if (observation.mistake) 1 else 0,
                wins = wins + if (observation.win) 1 else 0,
            )

        fun toPoint() =
            ProgressPoint(
                occurredAt = occurredAt ?: java.time.Instant.EPOCH,
                mistakeRate = mistakes.toDouble() / attempts * 100,
                winRate = wins.toDouble() / attempts * 100,
                attempts = attempts,
            )
    }
}
