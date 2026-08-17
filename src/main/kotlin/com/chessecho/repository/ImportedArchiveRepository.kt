package com.chessecho.repository

import com.chessecho.domain.ChessAccount
import com.chessecho.domain.ImportedArchive
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ImportedArchiveRepository : JpaRepository<ImportedArchive, UUID> {
    fun findByChessAccount(chessAccount: ChessAccount): List<ImportedArchive>

    fun existsByChessAccountAndArchiveUrl(
        chessAccount: ChessAccount,
        archiveUrl: String,
    ): Boolean
}
