package com.chessecho.repository

import com.chessecho.domain.HumanMoveCorpusImport
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HumanMoveCorpusImportRepository : JpaRepository<HumanMoveCorpusImport, UUID>
