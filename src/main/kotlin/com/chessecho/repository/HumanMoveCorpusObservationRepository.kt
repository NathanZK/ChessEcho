package com.chessecho.repository

import com.chessecho.domain.HumanMoveCorpusObservation
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HumanMoveCorpusObservationRepository : JpaRepository<HumanMoveCorpusObservation, UUID> {
    fun findByGameId(gameId: UUID): List<HumanMoveCorpusObservation>
}
