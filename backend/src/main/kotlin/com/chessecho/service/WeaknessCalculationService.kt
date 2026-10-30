package com.chessecho.service

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.Platform
import com.chessecho.domain.PlayerColor
import com.chessecho.domain.PositionOccurrence
import com.chessecho.dto.PracticalEvidenceCohort
import com.chessecho.dto.PracticalEvidenceResponse
import com.chessecho.dto.PracticalEvidenceScopeType
import com.chessecho.dto.TimeControlStats
import com.chessecho.dto.WeaknessResponse
import com.chessecho.repository.ChessAccountRepository
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.repository.PuzzleSchedulingEventRepository
import com.chessecho.repository.WeaknessAggregation
import com.chessecho.service.auth.AuthenticatedPrincipal
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import java.time.Instant
import java.util.UUID

@Service
class WeaknessCalculationService(
    private val chessAccountRepository: ChessAccountRepository,
    private val positionOccurrenceRepository: PositionOccurrenceRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    private val practicalEvidenceService: PracticalEvidenceService,
    private val weaknessPriorityPolicy: WeaknessPriorityPolicy,
) {
    @Autowired(required = false)
    private var accountOwnershipService: AccountOwnershipService? = null

    @Autowired(required = false)
    private var puzzleSchedulingEventRepository: PuzzleSchedulingEventRepository? = null

    @Autowired(required = false)
    private var adaptivePuzzleSchedulingPolicy: AdaptivePuzzleSchedulingPolicy? = null

    private val log = LoggerFactory.getLogger(javaClass)
    private val objectiveWeaknessAnalyzer: ObjectiveWeaknessAnalyzer = ObjectiveWeaknessAnalyzer()

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
        principal: AuthenticatedPrincipal? = null,
        accountId: UUID? = null,
    ): List<WeaknessResponse> {
        val startTime = System.currentTimeMillis()
        require(minEvalLoss >= 0.0) { "minEvalLoss must be non-negative" }

        val account = resolveAccount(platform, username, playerColor, minEvalLoss, principal, accountId)
        val color = playerColor.name
        val totalOccurrences = positionOccurrenceRepository.countByChessAccountId(account.id)
        val colorFilteredOccurrences =
            positionOccurrenceRepository.countByChessAccountIdAndPlayerColorOrBoth(account.id, color)
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
        val drafts = buildDrafts(aggregations, occurrences, positionIds, minEvalLoss, minMistakeCount, platform, account)
        val baselineResult = enrichDraftsWithPracticalEvidence(drafts, occurrences, account)
        val result = sortWeaknesses(baselineResult, principal, account, positionIds, color, Instant.now())

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

    private fun resolveAccount(
        platform: Platform,
        username: String,
        playerColor: PlayerColor,
        minEvalLoss: Double,
        principal: AuthenticatedPrincipal?,
        accountId: UUID?,
    ): ChessAccount {
        val requestAttributes = RequestContextHolder.getRequestAttributes()
        val ownership = accountOwnershipService
        return if (ownership != null && accountId != null) {
            ownership.resolveSharedAccount(accountId, principal)
        } else if (ownership != null && (requestAttributes != null || principal != null)) {
            ownership.resolvePrivateRead(platform, username, principal)
        } else {
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
        }
    }

    private fun buildDrafts(
        aggregations: List<WeaknessAggregation>,
        occurrences: List<PositionOccurrence>,
        positionIds: Set<UUID>,
        minEvalLoss: Double,
        minMistakeCount: Int,
        platform: Platform,
        account: ChessAccount,
    ): List<ObjectiveWeaknessDraft> {
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
            val scopedKey = ScopedPositionKey(aggregation.positionId, aggregation.playerColor)
            val scopedOccurrences = groupedOccurrences[scopedKey] ?: continue
            val analysis = groupedAnalyses[aggregation.positionId] ?: continue
            val draft =
                objectiveWeaknessAnalyzer.buildDraft(
                    aggregation = aggregation,
                    scopedOccurrences = scopedOccurrences,
                    analysis = analysis,
                    minEvalLoss = minEvalLoss,
                    minMistakeCount = minMistakeCount,
                    platform = platform,
                    account = account,
                ) ?: continue
            drafts.add(draft)
        }
        return drafts
    }

    private fun enrichDraftsWithPracticalEvidence(
        drafts: List<ObjectiveWeaknessDraft>,
        occurrences: List<PositionOccurrence>,
        account: ChessAccount,
    ): List<WeaknessResponse> {
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

        val occurrencesByScopedKey =
            occurrences.groupBy {
                ScopedPositionKey(it.position.id, it.playerColor)
            }

        return drafts.map { draft ->
            val scopedOccurrences =
                occurrencesByScopedKey[ScopedPositionKey(draft.positionId, draft.playerColor)].orEmpty()
            val timeControlSummaries =
                practicalEvidenceService.summarizeByTimeControl(
                    chessAccountId = account.id,
                    accountUsername = account.username,
                    occurrences = scopedOccurrences,
                    playerColor = draft.playerColor,
                    asOf = Instant.now(),
                    positionId = draft.positionId,
                )
            enrichSingleDraft(draft, account.id, evidenceByScope, timeControlSummaries)
        }
    }

    private fun enrichSingleDraft(
        draft: ObjectiveWeaknessDraft,
        accountId: UUID,
        evidenceByScope: Map<PracticalEvidenceScope, PracticalEvidenceSummary>,
        timeControlSummaries: Map<String, TimeControlPracticalTally> = emptyMap(),
    ): WeaknessResponse {
        val positionScope = draft.positionScope(accountId)
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
                val decisionScope = draft.decisionScope(accountId, move.move)
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

        val enrichedTimeControlStats = buildEnrichedTimeControlStats(draft.timeControlStats, timeControlSummaries)

        return WeaknessResponse(
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
            openingContext = draft.openingContext,
            evalCp = draft.evalCp,
            lastSeenAt = draft.lastSeenAt,
            playerColor = draft.playerColor,
            recommendationPriority = priorityDecision.recommendationPriority,
            objectiveEvidenceState = ObjectiveEvidenceState.INACCURATE,
            evidenceCombination = priorityDecision.evidenceCombination,
            practicalEvidence = positionEvidence,
            timeControlStats = enrichedTimeControlStats,
            phaseStats = draft.phaseStats,
        )
    }

    private fun buildEnrichedTimeControlStats(
        existingStats: Map<String, TimeControlStats>?,
        timeControlSummaries: Map<String, TimeControlPracticalTally>,
    ): Map<String, TimeControlStats>? {
        if (existingStats == null && timeControlSummaries.isEmpty()) {
            return null
        }
        val stats = existingStats.orEmpty().toMutableMap()
        for ((tc, tcSummary) in timeControlSummaries) {
            val existing = stats[tc]
            stats[tc] =
                (
                    existing ?: TimeControlStats(
                        timesReached = tcSummary.eligibleGames,
                        mistakeCount = 0,
                        mistakeRate = 0.0,
                        averageLoss = 0.0,
                    )
                ).copy(
                    eligibleGames = tcSummary.eligibleGames,
                    wins = tcSummary.wins,
                    draws = tcSummary.draws,
                    losses = tcSummary.losses,
                    scoreRate = tcSummary.scoreRate,
                )
        }
        return stats.takeIf { it.isNotEmpty() }
    }

    private fun sortWeaknesses(
        baselineResult: List<WeaknessResponse>,
        principal: AuthenticatedPrincipal?,
        account: ChessAccount,
        positionIds: Set<UUID>,
        color: String,
        now: Instant,
    ): List<WeaknessResponse> {
        val eventRepository = puzzleSchedulingEventRepository
        val schedulingPolicy = adaptivePuzzleSchedulingPolicy
        if (principal == null || eventRepository == null || schedulingPolicy == null) {
            return baselineResult.sortedWith(
                compareByDescending<WeaknessResponse> { it.recommendationPriority }
                    .thenByDescending { it.priority }
                    .thenBy { it.positionId.toString() }
                    .thenBy { it.playerColor },
            )
        }

        val schedulingHistory =
            eventRepository
                .findHistory(principal.appUserId, account.id, positionIds, color)
                .groupBy { it.position.id to it.playerColor }

        return baselineResult
            .map { weakness ->
                val events =
                    schedulingHistory[weakness.positionId to weakness.playerColor]
                        .orEmpty()
                        .map { event ->
                            SchedulingEvent(
                                type = event.eventType,
                                occurredAt = event.occurredAt,
                                sourceOccurrenceId = event.sourceOccurrence?.id,
                            )
                        }
                weakness to
                    schedulingPolicy.schedule(
                        weakness.recommendationPriority,
                        weakness.positionId,
                        events,
                        now,
                    )
            }.sortedWith(
                compareByDescending<Pair<WeaknessResponse, Double>> { it.second }
                    .thenByDescending { it.first.recommendationPriority }
                    .thenByDescending { it.first.priority }
                    .thenBy { it.first.positionId.toString() }
                    .thenBy { it.first.playerColor },
            ).map { it.first }
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

    private data class ScopedPositionKey(
        val positionId: UUID,
        val playerColor: String,
    )
}
