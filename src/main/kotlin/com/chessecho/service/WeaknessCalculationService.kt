package com.chessecho.service

import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.dto.AcceptableMove
import com.chessecho.dto.MoveBreakdown
import com.chessecho.dto.PracticalEvidenceCohort
import com.chessecho.dto.PracticalEvidenceResponse
import com.chessecho.dto.PracticalEvidenceScopeType
import com.chessecho.dto.WeaknessResponse
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.max

@Service
class WeaknessCalculationService(
    private val chessAccountRepository: ChessAccountRepository,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    private val practicalEvidenceService: PracticalEvidenceService,
    private val weaknessPriorityPolicy: WeaknessPriorityPolicy,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEFAULT_MIN_EVAL_LOSS = 0.8
        const val DEFAULT_MIN_TIMES_REACHED = 5
        const val DEFAULT_MIN_MISTAKE_COUNT = 3
    }

    @Transactional(readOnly = true)
    fun getWeaknesses(
        platform: Platform,
        username: String,
        playerColor: PlayerColor,
        minEvalLoss: Double = DEFAULT_MIN_EVAL_LOSS,
        minMistakeCount: Int = DEFAULT_MIN_MISTAKE_COUNT,
        minTimesReached: Int = DEFAULT_MIN_TIMES_REACHED,
    ): List<WeaknessResponse> {
        val startTime = System.currentTimeMillis()
        require(minEvalLoss >= 0.0) { "minEvalLoss must be non-negative" }

        val account =
            chessAccountRepository.findByPlatformAndUsernameIgnoreCase(platform.name, username)
                ?: run {
                    log.info(
                        "Weakness calculation failed: account not found for platform={} playerColor={} minEvalLoss={}",
                        platform,
                        playerColor,
                        minEvalLoss,
                    )
                    throw NoSuchElementException("Chess account not found")
                }

        val color = playerColor.name
        val totalOccurrences = positionOccurrenceRepository.countByChessAccountId(account.id)
        val colorFilteredOccurrences =
            positionOccurrenceRepository.countByChessAccountIdAndPlayerColorOrBoth(
                account.id,
                color,
            )
        val aggregations =
            positionOccurrenceRepository.findWeaknessAggregations(
                chessAccountId = account.id,
                playerColor = color,
                minEvalLoss = minEvalLoss,
                minTimesReached = minTimesReached,
                minMistakeCount = minMistakeCount.toLong(),
            )

        if (aggregations.isEmpty()) {
            logCompletion(
                startTime = startTime,
                accountId = account.id.toString(),
                platform = platform,
                playerColor = playerColor,
                minEvalLoss = minEvalLoss,
                minTimesReached = minTimesReached,
                minMistakeCount = minMistakeCount,
                totalOccurrences = totalOccurrences,
                colorFilteredOccurrences = colorFilteredOccurrences,
                qualifyingPositions = 0,
                result = emptyList(),
            )
            return emptyList()
        }

        val positionIds = aggregations.map { it.positionId }.toSet()
        val occurrences =
            positionOccurrenceRepository.findByChessAccountIdAndPlayerColorOrBothAndPositionIdIn(
                chessAccountId = account.id,
                playerColor = color,
                positionIds = positionIds,
            )
        val groupedOccurrences =
            occurrences.groupBy {
                ScopedPositionKey(it.position.id, it.playerColor)
            }
        val groupedAnalyses =
            engineAnalysisRepository
                .findByPositionIdInWithMoveEvaluations(positionIds)
                .associateBy { it.position.id }

        val drafts = mutableListOf<ObjectiveWeaknessDraft>()
        for (aggregation in aggregations) {
            val scopedKey =
                ScopedPositionKey(
                    aggregation.positionId,
                    aggregation.playerColor,
                )
            val scopedOccurrences = groupedOccurrences[scopedKey] ?: continue
            val analysis = groupedAnalyses[aggregation.positionId] ?: continue
            val sortedOccurrences =
                scopedOccurrences.sortedByDescending {
                    it.game.playedAt ?: it.createdAt
                }
            val lastSeenAt =
                sortedOccurrences.firstOrNull()?.let {
                    it.game.playedAt ?: it.createdAt
                }
            val bestMoveEvalCp = analysis.bestMoveEvalCp
            var unweightedTotalLoss = 0.0
            var priorityScore = 0.0
            var mistakeCount = 0
            val mistakeUrls = mutableListOf<String>()
            val moveStats = mutableMapOf<String, Pair<Double, Int>>()

            for (occurrence in sortedOccurrences) {
                val moveEvaluation =
                    analysis.moveEvaluations.find {
                        it.move == occurrence.movePlayed
                    } ?: continue
                val evalLoss =
                    moveEvaluation.evalLossFromBest
                        ?: calculateEvalLoss(bestMoveEvalCp, moveEvaluation.evalCp)
                val (previousLoss, previousCount) =
                    moveStats.getOrDefault(occurrence.movePlayed, 0.0 to 0)
                moveStats[occurrence.movePlayed] =
                    previousLoss + evalLoss to previousCount + 1

                if (evalLoss >= minEvalLoss) {
                    unweightedTotalLoss += evalLoss
                    val occurrenceDate = occurrence.game.playedAt ?: occurrence.createdAt
                    val daysOld = ChronoUnit.DAYS.between(occurrenceDate, Instant.now())
                    val weight = max(0.1, 1.0 - (daysOld / 365.0))
                    priorityScore += evalLoss * weight
                    mistakeCount++
                    mistakeUrls.add(gameUrl(account.platform, occurrence.game.platformGameId))
                }
            }

            if (mistakeCount < minMistakeCount) {
                continue
            }

            val acceptableMoves =
                analysis.moveEvaluations
                    .map { moveEvaluation ->
                        val loss =
                            moveEvaluation.evalLossFromBest
                                ?: calculateEvalLoss(bestMoveEvalCp, moveEvaluation.evalCp)
                        AcceptableMove(move = moveEvaluation.move, evalLoss = loss)
                    }.filter { it.evalLoss < minEvalLoss }
                    .sortedBy { it.evalLoss }
            val movesPlayed =
                moveStats.entries
                    .map { (move, stats) ->
                        MoveBreakdown(
                            move = move,
                            timesPlayed = stats.second,
                            averageLoss = stats.first / stats.second,
                        )
                    }.filter { it.averageLoss >= minEvalLoss }
                    .sortedWith(
                        compareByDescending<MoveBreakdown> { it.timesPlayed }
                            .thenByDescending { it.averageLoss },
                    ).take(3)
            val rawRate = mistakeCount.toDouble() / aggregation.timesReached
            val objectivePriority = priorityScore * rawRate
            if (!(objectivePriority > 0.0)) {
                continue
            }

            drafts.add(
                ObjectiveWeaknessDraft(
                    positionId = aggregation.positionId,
                    fen = aggregation.fen,
                    playerColor = aggregation.playerColor,
                    timesReached = aggregation.timesReached,
                    mistakeCount = mistakeCount,
                    mistakeRate = kotlin.math.round(rawRate * 10000.0) / 100.0,
                    averageLoss =
                        if (mistakeCount > 0) {
                            unweightedTotalLoss / mistakeCount
                        } else {
                            aggregation.averageLoss ?: 0.0
                        },
                    priority = objectivePriority,
                    bestMove = aggregation.bestMove,
                    acceptableMoves = acceptableMoves,
                    movesPlayed = movesPlayed,
                    gameUrls = mistakeUrls.distinct().take(10),
                    evalCp = aggregation.baselineEvalCp,
                    lastSeenAt = lastSeenAt,
                ),
            )
        }

        val scopes =
            buildSet {
                drafts.forEach { draft ->
                    add(draft.positionScope(account.id))
                    draft.movesPlayed.forEach { move ->
                        add(draft.decisionScope(account.id, move.move))
                    }
                }
            }
        val evidenceByScope =
            if (scopes.isEmpty()) {
                emptyMap()
            } else {
                practicalEvidenceService.summarize(
                    chessAccountId = account.id,
                    accountUsername = account.username,
                    occurrences = occurrences,
                    scopes = scopes,
                    asOf = Instant.now(),
                )
            }

        val result =
            drafts
                .map { draft ->
                    val positionScope = draft.positionScope(account.id)
                    val positionSummary = evidenceByScope[positionScope]
                    val priorityDecision =
                        weaknessPriorityPolicy.evaluate(
                            ObjectiveEvidenceState.INACCURATE,
                            draft.priority,
                            positionSummary,
                        )
                    val positionEvidence =
                        practicalEvidenceResponse(
                            positionSummary ?: emptySummary(positionScope),
                            priorityDecision,
                        )
                    val movesPlayed =
                        draft.movesPlayed.map { move ->
                            val decisionScope =
                                draft.decisionScope(account.id, move.move)
                            val decisionSummary = evidenceByScope[decisionScope]
                            val decisionAssessment =
                                if (decisionSummary == null) {
                                    missingDecisionAssessment(priorityDecision)
                                } else {
                                    weaknessPriorityPolicy.assess(decisionSummary)
                                }
                            move.copy(
                                practicalEvidence =
                                    practicalEvidenceResponse(
                                        decisionSummary ?: emptySummary(decisionScope),
                                        decisionAssessment,
                                        rankingApplied = false,
                                    ),
                            )
                        }

                    WeaknessResponse(
                        positionId = draft.positionId,
                        fen = draft.fen,
                        timesReached = draft.timesReached,
                        mistakeCount = draft.mistakeCount,
                        mistakeRate = draft.mistakeRate,
                        averageLoss = draft.averageLoss,
                        priority = draft.priority,
                        bestMove = draft.bestMove,
                        acceptableMoves = draft.acceptableMoves,
                        movesPlayed = movesPlayed,
                        gameUrls = draft.gameUrls,
                        evalCp = draft.evalCp,
                        lastSeenAt = draft.lastSeenAt,
                        playerColor = draft.playerColor,
                        recommendationPriority = priorityDecision.recommendationPriority,
                        objectiveEvidenceState = ObjectiveEvidenceState.INACCURATE,
                        evidenceCombination = priorityDecision.evidenceCombination,
                        practicalEvidence = positionEvidence,
                    )
                }.sortedWith(
                    compareByDescending<WeaknessResponse> { it.recommendationPriority }
                        .thenByDescending { it.priority }
                        .thenBy { it.positionId.toString() }
                        .thenBy { it.playerColor },
                )

        logCompletion(
            startTime = startTime,
            accountId = account.id.toString(),
            platform = platform,
            playerColor = playerColor,
            minEvalLoss = minEvalLoss,
            minTimesReached = minTimesReached,
            minMistakeCount = minMistakeCount,
            totalOccurrences = totalOccurrences,
            colorFilteredOccurrences = colorFilteredOccurrences,
            qualifyingPositions = aggregations.size,
            result = result,
        )
        return result
    }

    private fun practicalEvidenceResponse(
        summary: PracticalEvidenceSummary,
        decision: WeaknessPriorityDecision,
        rankingApplied: Boolean = decision.rankingApplied,
    ): PracticalEvidenceResponse =
        PracticalEvidenceResponse(
            scope =
                if (summary.scope.decisionSan == null) {
                    PracticalEvidenceScopeType.POSITION
                } else {
                    PracticalEvidenceScopeType.DECISION
                },
            decisionSan = summary.scope.decisionSan,
            candidateGames = summary.candidateGames,
            eligibleGames = summary.eligibleGames,
            ineligibleGames = summary.ineligibleGames,
            excludedGames = summary.excludedGames,
            wins = summary.wins,
            draws = summary.draws,
            losses = summary.losses,
            sideCorroborationConflictGames = summary.sideCorroborationConflictGames,
            scoreRate = summary.scoreRate,
            comparatorMethod = decision.comparatorMethod,
            comparatorScoreRate = decision.comparatorScoreRate,
            confidenceMethod = decision.confidenceMethod,
            confidenceLowerBound = decision.confidenceLowerBound,
            confidenceUpperBound = decision.confidenceUpperBound,
            confidenceState = decision.confidenceState,
            practicalAssessment = decision.practicalAssessment,
            sampleFloor = decision.sampleFloor,
            meaningfulDifference = decision.meaningfulDifference,
            observationWindowDays = decision.observationWindowDays,
            cohort = PracticalEvidenceCohort.STANDARD_ALL_IMPORTED_TIME_CONTROLS,
            policyVersion = decision.policyVersion,
            configurationState = decision.configurationState,
            rankingApplied = rankingApplied,
        )

    private fun emptySummary(scope: PracticalEvidenceScope): PracticalEvidenceSummary =
        PracticalEvidenceSummary(
            scope = scope,
            candidateGames = 0,
            eligibleGames = 0,
            ineligibleGames = 0,
            excludedGames = 0,
            wins = 0,
            draws = 0,
            losses = 0,
            sideCorroborationConflictGames = 0,
            scoreRate = null,
        )

    private fun missingDecisionAssessment(positionDecision: WeaknessPriorityDecision): WeaknessPriorityDecision =
        positionDecision.copy(
            recommendationPriority = 0.0,
            confidenceState =
                if (positionDecision.configurationState == PracticalConfigurationState.CALIBRATED) {
                    PracticalConfidenceState.INSUFFICIENT
                } else {
                    PracticalConfidenceState.INCONCLUSIVE
                },
            confidenceLowerBound = null,
            confidenceUpperBound = null,
            practicalAssessment = null,
            evidenceCombination = null,
            rankingApplied = false,
        )

    private fun gameUrl(
        platform: String,
        platformGameId: String,
    ): String =
        when {
            platformGameId.startsWith("http") -> platformGameId
            platform == "CHESS_COM" -> "https://www.chess.com/game/live/$platformGameId"
            platform == "LICHESS" -> "https://lichess.org/$platformGameId"
            else -> platformGameId
        }

    private fun logCompletion(
        startTime: Long,
        accountId: String,
        platform: Platform,
        playerColor: PlayerColor,
        minEvalLoss: Double,
        minTimesReached: Int,
        minMistakeCount: Int,
        totalOccurrences: Long,
        colorFilteredOccurrences: Long,
        qualifyingPositions: Int,
        result: List<WeaknessResponse>,
    ) {
        val evidence = result.map { it.practicalEvidence }
        val policyVersion =
            evidence.firstOrNull()?.policyVersion
                ?: weaknessPriorityPolicy.policyVersion
        log.info(
            "Weakness calculation pipeline: accountId={} platform={} playerColor={} minEvalLoss={} " +
                "minTimesReached={} minMistakeCount={} totalOccurrences={} colorFilteredOccurrences={} " +
                "qualifyingPositions={} finalResultCount={} practicalEligibleGames={} practicalIneligibleGames={} " +
                "practicalExcludedGames={} sideCorroborationConflictGames={} rankingEligibleCandidates={} " +
                "rankingAppliedCandidates={} rankingEnabled={} policyVersion={} durationMs={}",
            accountId,
            platform,
            playerColor,
            minEvalLoss,
            minTimesReached,
            minMistakeCount,
            totalOccurrences,
            colorFilteredOccurrences,
            qualifyingPositions,
            result.size,
            evidence.sumOf { it.eligibleGames },
            evidence.sumOf { it.ineligibleGames },
            evidence.sumOf { it.excludedGames },
            evidence.sumOf { it.sideCorroborationConflictGames },
            evidence.count {
                it.confidenceState == PracticalConfidenceState.RANKING_ELIGIBLE
            },
            evidence.count { it.rankingApplied },
            weaknessPriorityPolicy.rankingEnabled,
            policyVersion,
            System.currentTimeMillis() - startTime,
        )
    }

    /**
     * Fallback calculation for evaluation loss if evalLossFromBest is null.
     */
    private fun calculateEvalLoss(
        bestMoveEvalCp: Int?,
        resultCp: Int?,
    ): Double {
        if (bestMoveEvalCp == null || resultCp == null) return 0.0
        return maxOf(0.0, (bestMoveEvalCp - resultCp) / 100.0)
    }

    private data class ScopedPositionKey(
        val positionId: UUID,
        val playerColor: String,
    )

    private data class ObjectiveWeaknessDraft(
        val positionId: UUID,
        val fen: String,
        val playerColor: String,
        val timesReached: Int,
        val mistakeCount: Int,
        val mistakeRate: Double,
        val averageLoss: Double,
        val priority: Double,
        val bestMove: String?,
        val acceptableMoves: List<AcceptableMove>,
        val movesPlayed: List<MoveBreakdown>,
        val gameUrls: List<String>,
        val evalCp: Int?,
        val lastSeenAt: Instant?,
    ) {
        fun positionScope(chessAccountId: UUID): PracticalEvidenceScope =
            PracticalEvidenceScope(
                chessAccountId = chessAccountId,
                positionId = positionId,
                playerColor = playerColor,
            )

        fun decisionScope(
            chessAccountId: UUID,
            san: String,
        ): PracticalEvidenceScope =
            PracticalEvidenceScope(
                chessAccountId = chessAccountId,
                positionId = positionId,
                playerColor = playerColor,
                decisionSan = san,
            )
    }
}
