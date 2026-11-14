package com.chessprobe.service.similarity

import com.chessprobe.domain.ChessAccount
import com.chessprobe.domain.Position
import com.chessprobe.domain.clustering.PositionContinuationArtifact
import com.chessprobe.domain.clustering.PositionContinuationStatus
import com.chessprobe.domain.clustering.TacticalResolutionStatus
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.repository.clustering.PositionContinuationArtifactRepository
import com.chessprobe.service.clustering.DynamicContinuationCoordinator
import com.chessprobe.similarity.DynamicTacticalScorer
import com.chessprobe.similarity.PositionAnalysisOutcome
import com.chessprobe.similarity.PositionDocument
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

interface PersonalDynamicCorpusProvider {
    fun loadPersonalDynamicPositions(account: ChessAccount): List<PositionDocument>
}

@Component
class JpaPersonalDynamicCorpusProvider(
    private val occurrenceRepository: PositionOccurrenceRepository,
    private val artifactRepository: PositionContinuationArtifactRepository,
    private val dynamicTacticalScorer: DynamicTacticalScorer,
    private val configHash: String = DynamicContinuationCoordinator.computeConfigHash(),
) : PersonalDynamicCorpusProvider {
    @Autowired
    constructor(
        occurrenceRepository: PositionOccurrenceRepository,
        artifactRepository: PositionContinuationArtifactRepository,
        dynamicTacticalScorer: DynamicTacticalScorer,
        coordinator: DynamicContinuationCoordinator,
    ) : this(
        occurrenceRepository = occurrenceRepository,
        artifactRepository = artifactRepository,
        dynamicTacticalScorer = dynamicTacticalScorer,
        configHash = coordinator.defaultConfigHash,
    )

    @Transactional(readOnly = true)
    override fun loadPersonalDynamicPositions(account: ChessAccount): List<PositionDocument> {
        val positions = occurrenceRepository.findDistinctPositionsByChessAccountId(account.id)
        if (positions.isEmpty()) return emptyList()

        val positionIds = positions.map { it.id }.toSet()
        val artifacts =
            artifactRepository
                .findByPositionIdInAndConfigHash(positionIds, configHash)
                .associateBy { it.positionId }

        return positions.map { pos -> resolveDocument(pos, artifacts[pos.id]) }
    }

    private fun resolveDocument(
        pos: Position,
        artifact: PositionContinuationArtifact?,
    ): PositionDocument {
        val outcome: PositionAnalysisOutcome
        val terms: Map<String, Double>

        if (artifact == null) {
            outcome = PositionAnalysisOutcome.UNANALYZED
            terms = emptyMap()
        } else {
            when (artifact.status) {
                PositionContinuationStatus.PENDING -> {
                    outcome = PositionAnalysisOutcome.PENDING
                    terms = emptyMap()
                }
                PositionContinuationStatus.FAILED -> {
                    outcome = PositionAnalysisOutcome.FAILED
                    terms = emptyMap()
                }
                PositionContinuationStatus.COMPLETED -> {
                    val resolved = resolveCompletedArtifact(pos.fen, artifact)
                    outcome = resolved.first
                    terms = resolved.second
                }
            }
        }

        return PositionDocument(
            positionId = pos.id,
            fen = pos.fen,
            canonicalFen = PositionDocument.canonicalizeFen(pos.fen),
            terms = terms,
            analysisOutcome = outcome,
        )
    }

    private fun resolveCompletedArtifact(
        fen: String,
        artifact: PositionContinuationArtifact,
    ): Pair<PositionAnalysisOutcome, Map<String, Double>> =
        when (artifact.resolutionStatus) {
            TacticalResolutionStatus.RESOLVED_QUIET,
            TacticalResolutionStatus.RESOLVED_TERMINAL,
            -> {
                val tokens = dynamicTacticalScorer.extractDynamicTokensFromResolved(fen, artifact.resolvedMoves)
                if (tokens.isNotEmpty()) {
                    Pair(PositionAnalysisOutcome.EVALUATED_WITH_TOKENS, tokens)
                } else {
                    Pair(PositionAnalysisOutcome.EVALUATED_ZERO_TOKENS, emptyMap())
                }
            }
            TacticalResolutionStatus.INCOMPLETE_LIMIT,
            TacticalResolutionStatus.OPERATIONAL_FAILURE,
            null,
            -> Pair(PositionAnalysisOutcome.INCOMPLETE_RESOLUTION, emptyMap())
        }
}
