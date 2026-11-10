package com.chessprobe.repository

import com.chessprobe.domain.HumanMoveCorpusImportedObservation
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HumanMoveCorpusImportedObservationRepository : JpaRepository<HumanMoveCorpusImportedObservation, UUID> {
    fun findByGameId(gameId: UUID): List<HumanMoveCorpusImportedObservation>
}
