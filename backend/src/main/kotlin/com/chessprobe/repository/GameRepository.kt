package com.chessprobe.repository

import com.chessprobe.domain.Game
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface GameRepository : JpaRepository<Game, UUID> {
    fun existsByChessAccountAndPlatformGameId(
        chessAccount: com.chessprobe.domain.ChessAccount,
        platformGameId: String,
    ): Boolean

    @Query("SELECT g.platformGameId FROM Game g WHERE g.chessAccount = :chessAccount AND g.platformGameId IN :platformGameIds")
    fun findPlatformGameIdsByChessAccountAndPlatformGameIdIn(
        chessAccount: com.chessprobe.domain.ChessAccount,
        platformGameIds: List<String>,
    ): List<String>

    fun findAllByChessAccountOrderByPlayedAtDesc(
        chessAccount: com.chessprobe.domain.ChessAccount,
        pageable: Pageable,
    ): Page<Game>

    fun findAllByChessAccountOrderByPlayedAtDesc(chessAccount: com.chessprobe.domain.ChessAccount): List<Game>

    fun findAllByImportedArchiveIdOrderByPlayedAtAsc(id: UUID): List<Game>
}
