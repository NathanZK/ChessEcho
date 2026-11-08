package com.chessecho.service.similarity

import com.chessecho.domain.ChessAccount
import com.chessecho.repository.EngineAnalysisRepository
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.similarity.DynamicTacticalScorer
import com.chessecho.similarity.PositionDocument
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

interface PersonalDynamicCorpusProvider {
    fun loadPersonalDynamicPositions(account: ChessAccount): List<PositionDocument>
}

@Component
class JpaPersonalDynamicCorpusProvider(
    private val occurrenceRepository: PositionOccurrenceRepository,
    private val engineAnalysisRepository: EngineAnalysisRepository,
    private val dynamicTacticalScorer: DynamicTacticalScorer,
) : PersonalDynamicCorpusProvider {
    @Transactional(readOnly = true)
    override fun loadPersonalDynamicPositions(account: ChessAccount): List<PositionDocument> {
        val positions = occurrenceRepository.findDistinctPositionsByChessAccountId(account.id)
        if (positions.isEmpty()) return emptyList()

        val positionIds = positions.map { it.id }.toSet()
        val analyses =
            engineAnalysisRepository
                .findByPositionIdInWithMoveEvaluations(positionIds)
                .associateBy { it.position.id }

        return positions.map { pos ->
            val analysis = analyses[pos.id]
            val moves =
                if (analysis?.bestMove != null) {
                    listOf(analysis.bestMove)
                } else {
                    emptyList()
                }
            val dynamicTokens =
                if (moves.isNotEmpty()) {
                    dynamicTacticalScorer.extractDynamicTokens(pos.fen, moves)
                } else {
                    emptyMap()
                }
            val canonicalFen = PositionDocument.canonicalizeFen(pos.fen)
            PositionDocument(
                positionId = pos.id,
                fen = pos.fen,
                canonicalFen = canonicalFen,
                terms = dynamicTokens,
            )
        }
    }
}
