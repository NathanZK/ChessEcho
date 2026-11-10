package com.chessprobe.repository

import com.chessprobe.domain.HumanMoveCorpusImport
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface HumanMoveCorpusImportRepository : JpaRepository<HumanMoveCorpusImport, UUID>
