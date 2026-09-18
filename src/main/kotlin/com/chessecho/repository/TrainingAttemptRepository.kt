package com.chessecho.repository

import com.chessecho.domain.TrainingAttempt
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TrainingAttemptRepository : JpaRepository<TrainingAttempt, UUID> {
    fun findByPuzzleId(puzzleId: String): List<TrainingAttempt>

    fun findByIdAndPuzzleId(
        id: UUID,
        puzzleId: String,
    ): TrainingAttempt?
}
