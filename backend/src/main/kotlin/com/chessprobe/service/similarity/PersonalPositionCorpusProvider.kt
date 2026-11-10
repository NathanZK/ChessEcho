package com.chessprobe.service.similarity

import com.chessprobe.domain.ChessAccount
import com.chessprobe.repository.PositionOccurrenceRepository
import com.chessprobe.similarity.PositionDocument
import com.chessprobe.similarity.PositionTokenExtractor
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
