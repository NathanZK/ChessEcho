package com.chessecho.service

import com.chessecho.domain.PlayerColor
import com.chessecho.domain.SchedulingEventType
import com.chessecho.dto.ProgressBaseline
import com.chessecho.dto.ProgressIntervalState
import com.chessecho.dto.ProgressPoint
import com.chessecho.dto.ProgressResponse
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class ProgressService(
    private val chessAccountRepository: ChessAccountRepository,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    private val gameOutcomeNormalizer: GameOutcomeNormalizer,
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
        val checkpoints =
            puzzleSchedulingEventRepository
                .findHistory(principal.appUserId, account.id, listOf(positionId), playerColor.name)
                .asSequence()
                .filter { it.eventType == SchedulingEventType.SOLVED }
                .map { Checkpoint(it.id, it.occurredAt) }
                .sortedWith(compareBy<Checkpoint> { it.occurredAt }.thenBy { it.id.toString() })
                .toList()

        val analysis = engineAnalysisRepository.findByPositionIdWithMoveEvaluations(positionId)
        val evaluations = analysis?.moveEvaluations?.associateBy { it.move }.orEmpty()
        val baselineAccumulator = Accumulator()
        val intervalAccumulators = checkpoints.map { IntervalAccumulator(it, Accumulator()) }
        val datedOccurrences =
            occurrences
                .mapNotNull { occurrence ->
                    occurrence.game.playedAt?.let { DatedOccurrence(occurrence, it) }
                }.sortedWith(
                    compareBy<DatedOccurrence> { it.playedAt }
                        .thenBy { it.occurrence.createdAt }
                        .thenBy { it.occurrence.id.toString() },
                )

        var latestCheckpointIndex = -1
        datedOccurrences.forEach { datedOccurrence ->
            while (
                latestCheckpointIndex + 1 < checkpoints.size &&
                !checkpoints[latestCheckpointIndex + 1].occurredAt.isAfter(datedOccurrence.playedAt)
            ) {
                latestCheckpointIndex++
            }

            val occurrence = datedOccurrence.occurrence
            val loss = evaluations[occurrence.movePlayed]?.evalLossFromBest ?: 0.0
            val won =
                gameOutcomeNormalizer.normalize(
                    occurrence.game,
                    occurrence.playerColor,
                    account.username,
                ).outcome == PracticalOutcome.WIN
            val accumulator =
                if (latestCheckpointIndex < 0) {
                    baselineAccumulator
                } else {
                    intervalAccumulators[latestCheckpointIndex].accumulator
                }
            accumulator.add(datedOccurrence.playedAt, loss >= MISTAKE_THRESHOLD, won)
        }

        val baseline = baselineAccumulator.toBaseline()
        val points =
            intervalAccumulators.mapNotNull { interval ->
                interval.accumulator.toPoint(
                    checkpointId = interval.checkpoint.id,
                    open = interval.checkpoint == checkpoints.lastOrNull(),
                )
            }
        val latestMeasured = intervalAccumulators.lastOrNull { it.accumulator.attempts > 0 }?.accumulator
        val mistakeChange = relativeChange(baseline?.mistakeRate, latestMeasured?.mistakeRate())
        val winChange = relativeChange(baseline?.winRate, latestMeasured?.winRate())
        val assessment =
            when {
                baseline == null || latestMeasured == null ->
                    "More played encounters are needed to assess progress."
                mistakeChange != null && mistakeChange < 0 -> "You are making fewer mistakes at this position."
                mistakeChange != null && mistakeChange > 0 -> "You are making more mistakes at this position."
                else -> "Your performance at this position is stable."
            }
        val currentIntervalState =
            when {
                checkpoints.isEmpty() -> ProgressIntervalState.NO_CHECKPOINT
                intervalAccumulators.last().accumulator.attempts == 0 -> ProgressIntervalState.OPEN_AWAITING_EVIDENCE
                else -> ProgressIntervalState.MEASURED_OPEN
            }

        return ProgressResponse(
            positionId = positionId,
            playerColor = playerColor.name,
            baseline = baseline,
            points = points,
            currentIntervalState = currentIntervalState,
            excludedUndatedEncounters = occurrences.count { it.game.playedAt == null },
            mistakeRateChange = mistakeChange,
            winRateChange = winChange,
            assessment = assessment,
        )
    }

    private fun relativeChange(
        baselineRate: Double?,
        measuredRate: Double?,
    ): Double? =
        if (baselineRate == null || measuredRate == null || baselineRate == 0.0) {
            null
        } else {
            (measuredRate - baselineRate) / baselineRate * 100
        }

    private data class Checkpoint(val id: UUID, val occurredAt: Instant)

    private data class DatedOccurrence(val occurrence: com.chessecho.domain.PositionOccurrence, val playedAt: Instant)

    private data class IntervalAccumulator(val checkpoint: Checkpoint, val accumulator: Accumulator)

    private class Accumulator {
        var occurredAt: Instant? = null
            private set
        var attempts: Int = 0
            private set
        private var mistakes: Int = 0
        private var wins: Int = 0

        fun add(
            playedAt: Instant,
            mistake: Boolean,
            win: Boolean,
        ) {
            occurredAt = occurredAt?.let { maxOf(it, playedAt) } ?: playedAt
            attempts++
            if (mistake) mistakes++
            if (win) wins++
        }

        fun mistakeRate(): Double = mistakes.toDouble() / attempts * 100

        fun winRate(): Double = wins.toDouble() / attempts * 100

        fun toBaseline(): ProgressBaseline? =
            occurredAt?.let {
                ProgressBaseline(
                    occurredAt = it,
                    mistakeRate = mistakeRate(),
                    winRate = winRate(),
                    sourceEncounterCount = attempts,
                )
            }

        fun toPoint(
            checkpointId: UUID,
            open: Boolean,
        ): ProgressPoint? =
            occurredAt?.let {
                ProgressPoint(
                    checkpointId = checkpointId,
                    occurredAt = it,
                    mistakeRate = mistakeRate(),
                    winRate = winRate(),
                    attempts = attempts,
                    open = open,
                )
            }
    }

    private companion object {
        const val MISTAKE_THRESHOLD = 0.8
    }
}
