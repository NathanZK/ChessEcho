package com.chessecho.repository

import com.chessecho.domain.HumanMoveCorpusImportedGame
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HumanMoveCorpusImportedGameRepository : JpaRepository<HumanMoveCorpusImportedGame, UUID> {
    fun findBySourceRunIdOrderByQualifyingOrdinal(sourceRunId: UUID): List<HumanMoveCorpusImportedGame>
}
