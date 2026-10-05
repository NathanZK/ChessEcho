package com.chessecho.service

import com.chessecho.domain.PlayerColor
import com.chessecho.domain.SchedulingEventType
import com.chessecho.dto.ProgressBaseline
import com.chessecho.dto.ProgressIntervalState
import com.chessecho.dto.ProgressPoint
import com.chessecho.dto.ProgressResponse
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
    private val accountOwnershipService: AccountOwnershipService,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    private val gameOutcomeNormalizer: GameOutcomeNormalizer,
) {
    @Transactional(readOnly = true)
    fun getProgress(
        positionId: UUID,
        playerColor: PlayerColor,
        accountId: UUID,
        principal: AuthenticatedPrincipal,
        minEvalLoss: Double,
    ): ProgressResponse {
        require(minEvalLoss.isFinite() && minEvalLoss > 0.0) { "minEvalLoss must be positive and finite" }
        val account = accountOwnershipService.requireConnectedAccountForProgress(accountId, principal)

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
            val loss = evaluations[occurrence.movePlayed]?.weaknessEvaluationLoss(analysis?.bestMoveEvalCp)
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
            accumulator.add(datedOccurrence.playedAt, loss != null && loss.isFinite() && loss >= minEvalLoss, won)
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
        val assessment = assessment(baseline, latestMeasured)
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

    private fun assessment(
        baseline: ProgressBaseline?,
        latestMeasured: Accumulator?,
    ): String {
        if (baseline == null || latestMeasured == null) {
            return "More played encounters are needed to assess progress."
        }

        val mistakeTrend = latestMeasured.mistakeRate().compareTo(baseline.mistakeRate)
        val winTrend = latestMeasured.winRate().compareTo(baseline.winRate)
        if (mistakeTrend == 0 && winTrend == 0) {
            return "Your mistake rate and win rate stayed the same."
        }

        val mistakeClause =
            when {
                mistakeTrend < 0 -> "You are making fewer mistakes at this position"
                mistakeTrend > 0 -> "You are making more mistakes at this position"
                else -> "Your mistake rate stayed the same"
            }
        val winClause =
            when {
                winTrend > 0 -> "your win rate has increased"
                winTrend < 0 -> "your win rate has decreased"
                else -> "your win rate stayed the same"
            }
        val conjunction = if (mistakeTrend == 0 || winTrend == 0 || mistakeTrend != winTrend) "and" else "but"

        return "$mistakeClause, $conjunction $winClause."
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
}
