package com.chessecho.service

import com.chessecho.domain.AppUser
import com.chessecho.domain.Game
import com.chessecho.domain.PositionOccurrence
import com.chessecho.domain.PuzzleSchedulingEvent
import com.chessecho.domain.SchedulingEventType
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.UserPositionWeaknessRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class PuzzleSchedulingEventDispatcher(
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val userPositionWeaknessRepository: UserPositionWeaknessRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    private val puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository,
) {
    fun emitSchedulingEvents(
        savedGames: List<Game>,
        initiatingUser: AppUser?,
        affectedPositionIds: Set<UUID>,
    ) {
        if (savedGames.isEmpty() || affectedPositionIds.isEmpty()) return

        val savedGameIds = savedGames.map { it.id }.toSet()
        val accountId = savedGames.first().chessAccount.id
        val occurrences = fetchRelevantOccurrences(accountId, affectedPositionIds)

        occurrences
            .filter { it.game.id in savedGameIds }
            .forEach { occurrence ->
                dispatchEventsForOccurrence(occurrence, initiatingUser)
            }
    }

    private fun fetchRelevantOccurrences(
        accountId: UUID,
        positionIds: Set<UUID>,
    ): List<PositionOccurrence> =
        positionOccurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
            accountId,
            "WHITE",
            positionIds,
        ) +
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorAndPositionIdIn(
                accountId,
                "BLACK",
                positionIds,
            )

    private fun dispatchEventsForOccurrence(
        occurrence: PositionOccurrence,
        initiatingUser: AppUser?,
    ) {
        val weakness =
            userPositionWeaknessRepository.findByChessAccountIdAndPositionIdAndPlayerColor(
                occurrence.chessAccount.id,
                occurrence.position.id,
                occurrence.playerColor,
            ) ?: return

        val occurredAt = occurrence.game.playedAt ?: occurrence.game.createdAt
        claimSchedulingEvent(
            PuzzleSchedulingEvent(
                appUser = initiatingUser,
                chessAccount = occurrence.chessAccount,
                position = occurrence.position,
                playerColor = occurrence.playerColor,
                eventType = SchedulingEventType.GAME_REENCOUNTERED,
                sourceOccurrence = occurrence,
                occurredAt = occurredAt,
            ),
        )

        val loss = calculateEvaluationLoss(occurrence) ?: return
        claimSchedulingEvent(
            PuzzleSchedulingEvent(
                appUser = initiatingUser,
                chessAccount = occurrence.chessAccount,
                position = occurrence.position,
                playerColor = occurrence.playerColor,
                eventType =
                    if (loss >= WeaknessCalculationService.DEFAULT_MIN_EVAL_LOSS) {
                        SchedulingEventType.GAME_MISTAKE
                    } else {
                        SchedulingEventType.GAME_HANDLED_SUCCESSFULLY
                    },
                sourceOccurrence = occurrence,
                occurredAt = occurredAt,
            ),
        )
    }

    private fun calculateEvaluationLoss(occurrence: PositionOccurrence): Double? {
        val analysis = engineAnalysisRepository.findByPositionIdWithMoveEvaluations(occurrence.position.id)
        val moveEvaluation = analysis?.moveEvaluations?.firstOrNull { it.move == occurrence.movePlayed }
        return moveEvaluation?.weaknessEvaluationLoss(
            analysis?.bestMoveEvalCp,
            analysis?.bestMoveEvalMate,
        )
    }

    fun claimSchedulingEvent(event: PuzzleSchedulingEvent) {
        try {
            puzzleSchedulingEventRepository.saveAndFlush(event)
        } catch (ex: DataIntegrityViolationException) {
            val sourceOccurrence = requireNotNull(event.sourceOccurrence)
            puzzleSchedulingEventRepository.findExistingClaim(
                event.appUser?.id,
                sourceOccurrence.id,
                event.eventType,
            ) ?: throw ex
        }
    }
}
