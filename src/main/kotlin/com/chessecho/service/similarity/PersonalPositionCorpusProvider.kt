package com.chessecho.service.similarity

import com.chessecho.domain.ChessAccount
import com.chessecho.repository.PositionOccurrenceRepository
import com.chessecho.similarity.PositionDocument
import com.chessecho.similarity.PositionTokenExtractor
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

interface PersonalPositionCorpusProvider {
    fun loadPersonalPositions(account: ChessAccount): List<PositionDocument>
}

@Component
class JpaPersonalPositionCorpusProvider(
    private val occurrenceRepository: PositionOccurrenceRepository,
    private val tokenExtractor: PositionTokenExtractor,
) : PersonalPositionCorpusProvider {
    @Transactional(readOnly = true)
    override fun loadPersonalPositions(account: ChessAccount): List<PositionDocument> {
        val positions = occurrenceRepository.findDistinctPositionsByChessAccountId(account.id)
        return positions.map { pos ->
            tokenExtractor.extractDocument(pos.id, pos.fen, includeReachability = true)
        }
    }
}
