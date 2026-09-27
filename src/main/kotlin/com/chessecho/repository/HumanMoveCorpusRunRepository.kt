package com.chessecho.repository

import com.chessecho.domain.HumanMoveCorpusRun
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HumanMoveCorpusRunRepository : JpaRepository<HumanMoveCorpusRun, UUID> {
    fun findAllByOrderByCreatedAtDesc(): List<HumanMoveCorpusRun>
}
