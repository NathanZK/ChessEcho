package com.chessecho.repository

import com.chessecho.domain.Position
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface PositionRepository : JpaRepository<Position, UUID> {
    @Query("SELECT p FROM Position p WHERE p.hash IN :hashes")
    fun findByHashIn(hashes: List<String>): List<Position>
}
