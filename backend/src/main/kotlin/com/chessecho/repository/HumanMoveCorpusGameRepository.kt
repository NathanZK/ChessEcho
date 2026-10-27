package com.chessecho.repository

import com.chessecho.domain.HumanMoveCorpusGame
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HumanMoveCorpusGameRepository : JpaRepository<HumanMoveCorpusGame, UUID> {
    fun findByRunIdAndProviderGameId(
        runId: UUID,
        providerGameId: String,
    ): HumanMoveCorpusGame?
}
