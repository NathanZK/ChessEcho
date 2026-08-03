package com.chessecho.repository

import com.chessecho.domain.Game
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface GameRepository : JpaRepository<Game, UUID> {
    fun existsByChessAccountAndPlatformGameId(
        chessAccount: com.chessecho.domain.ChessAccount,
        platformGameId: String,
    ): Boolean

    @Query("SELECT g.platformGameId FROM Game g WHERE g.chessAccount = :chessAccount AND g.platformGameId IN :platformGameIds")
    fun findPlatformGameIdsByChessAccountAndPlatformGameIdIn(
        chessAccount: com.chessecho.domain.ChessAccount,
        platformGameIds: List<String>,
    ): List<String>
}
