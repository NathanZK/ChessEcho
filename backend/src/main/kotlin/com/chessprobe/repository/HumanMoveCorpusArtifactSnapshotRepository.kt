package com.chessprobe.repository

import com.chessprobe.domain.HumanMoveCorpusArtifactSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HumanMoveCorpusArtifactSnapshotRepository : JpaRepository<HumanMoveCorpusArtifactSnapshot, String> {
    fun findBySourceRunId(sourceRunId: UUID): List<HumanMoveCorpusArtifactSnapshot>
}
