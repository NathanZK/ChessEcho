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
            val candidateOccurrences =
                if (scope.chessAccountId != chessAccountId) {
                    emptyList()
                } else {
                    occurrencesByScope[
                        OccurrenceScopeKey(
                            chessAccountId = scope.chessAccountId,
                            positionId = scope.positionId,
                            playerColor = scope.playerColor,
                        ),
                    ].orEmpty()
                        .asSequence()
                        .filter { it.chessAccount.id == chessAccountId }
                        .filter { it.position.id == scope.positionId }
                        .filter { it.playerColor == scope.playerColor }
                        .filter { scope.decisionSan == null || it.movePlayed == scope.decisionSan }
                        .distinctBy { it.game.id }
                        .toList()
                }

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

            val eligibleGames = wins + draws + losses
            PracticalEvidenceSummary(
                scope = scope,
                candidateGames = candidateOccurrences.size,
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

    private data class NormalizationKey(
        val gameId: UUID,
        val authoritativeColor: String,
    )

    private data class OccurrenceScopeKey(
        val chessAccountId: UUID,
        val positionId: UUID,
        val playerColor: String,
    )
}
