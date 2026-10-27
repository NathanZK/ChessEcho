package com.chessecho.service

import com.chessecho.config.PracticalEvidenceProperties
import com.chessecho.domain.PositionOccurrence
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class PracticalEvidenceScope(
    val chessAccountId: UUID,
    val positionId: UUID,
    val playerColor: String,
    val decisionSan: String? = null,
)

data class PracticalEvidenceSummary(
    val scope: PracticalEvidenceScope,
    val candidateGames: Int,
    val eligibleGames: Int,
    val ineligibleGames: Int,
    val excludedGames: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val sideCorroborationConflictGames: Int,
    val scoreRate: Double?,
)

internal data class ScopeOutcomeTally(
    val wins: Int = 0,
    val draws: Int = 0,
    val losses: Int = 0,
    val ineligibleGames: Int = 0,
    val excludedGames: Int = 0,
    val sideCorroborationConflictGames: Int = 0,
) {
    fun toSummary(
        scope: PracticalEvidenceScope,
        candidateGames: Int,
    ): PracticalEvidenceSummary {
        val eligibleGames = wins + draws + losses
        return PracticalEvidenceSummary(
            scope = scope,
            candidateGames = candidateGames,
            eligibleGames = eligibleGames,
            ineligibleGames = ineligibleGames,
            excludedGames = excludedGames,
            wins = wins,
            draws = draws,
            losses = losses,
            sideCorroborationConflictGames = sideCorroborationConflictGames,
            scoreRate =
                if (eligibleGames == 0) {
                    null
                } else {
                    (wins + 0.5 * draws) / eligibleGames
                },
        )
    }
}

@Service
class PracticalEvidenceService(
    private val gameOutcomeNormalizer: GameOutcomeNormalizer,
    private val properties: PracticalEvidenceProperties,
) {
    fun summarize(
        chessAccountId: UUID,
        accountUsername: String,
        occurrences: List<PositionOccurrence>,
        scopes: Set<PracticalEvidenceScope>,
        asOf: Instant,
    ): Map<PracticalEvidenceScope, PracticalEvidenceSummary> {
        val normalizationCache = mutableMapOf<NormalizationKey, NormalizedGameOutcome>()
        val occurrencesByScope =
            occurrences.groupBy {
                OccurrenceScopeKey(
                    chessAccountId = it.chessAccount.id,
                    positionId = it.position.id,
                    playerColor = it.playerColor,
                )
            }
        val windowStart =
            properties.observationWindowDays?.let {
                asOf.minus(it, ChronoUnit.DAYS)
            }

        return scopes.associateWith { scope ->
            val candidateOccurrences = filterCandidateOccurrences(scope, occurrencesByScope, chessAccountId)
            val tally =
                accumulateScopeTally(
                    candidateOccurrences = candidateOccurrences,
                    scope = scope,
                    accountUsername = accountUsername,
                    windowStart = windowStart,
                    normalizationCache = normalizationCache,
                )
            tally.toSummary(scope, candidateOccurrences.size)
        }
    }

    internal fun filterCandidateOccurrences(
        scope: PracticalEvidenceScope,
        occurrencesByScope: Map<OccurrenceScopeKey, List<PositionOccurrence>>,
        chessAccountId: UUID,
    ): List<PositionOccurrence> {
        if (scope.chessAccountId != chessAccountId) return emptyList()

        val key =
            OccurrenceScopeKey(
                chessAccountId = scope.chessAccountId,
                positionId = scope.positionId,
                playerColor = scope.playerColor,
            )
        return occurrencesByScope[key]
            .orEmpty()
            .asSequence()
            .filter { it.chessAccount.id == chessAccountId }
            .filter { it.position.id == scope.positionId }
            .filter { it.playerColor == scope.playerColor }
            .filter { scope.decisionSan == null || it.movePlayed == scope.decisionSan }
            .distinctBy { it.game.id }
            .toList()
    }

    internal fun accumulateScopeTally(
        candidateOccurrences: List<PositionOccurrence>,
        scope: PracticalEvidenceScope,
        accountUsername: String,
        windowStart: Instant?,
        normalizationCache: MutableMap<NormalizationKey, NormalizedGameOutcome>,
    ): ScopeOutcomeTally {
        var ineligibleGames = 0
        var excludedGames = 0
        var wins = 0
        var draws = 0
        var losses = 0
        var sideCorroborationConflictGames = 0

        for (occurrence in candidateOccurrences) {
            val game = occurrence.game
            val observationTime = game.playedAt ?: game.createdAt
            if (windowStart != null && observationTime.isBefore(windowStart)) {
                ineligibleGames++
                continue
            }

            val normalized =
                normalizationCache.getOrPut(
                    NormalizationKey(game.id, scope.playerColor),
                ) {
                    gameOutcomeNormalizer.normalize(
                        game,
                        scope.playerColor,
                        accountUsername,
                    )
                }

            if (normalized.sideCorroboration == SideCorroboration.CONFLICT) {
                sideCorroborationConflictGames++
            }

            when {
                normalized.ineligibilityReason != null -> ineligibleGames++
                normalized.exclusionReason != null -> excludedGames++
                normalized.outcome == PracticalOutcome.WIN -> wins++
                normalized.outcome == PracticalOutcome.DRAW -> draws++
                normalized.outcome == PracticalOutcome.LOSS -> losses++
                else -> excludedGames++
            }
        }

        return ScopeOutcomeTally(
            wins = wins,
            draws = draws,
            losses = losses,
            ineligibleGames = ineligibleGames,
            excludedGames = excludedGames,
            sideCorroborationConflictGames = sideCorroborationConflictGames,
        )
    }

    internal data class NormalizationKey(
        val gameId: UUID,
        val authoritativeColor: String,
    )

    internal data class OccurrenceScopeKey(
        val chessAccountId: UUID,
        val positionId: UUID,
        val playerColor: String,
    )
}
