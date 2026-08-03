package com.chessecho.repository

import com.chessecho.domain.PositionOccurrence
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PositionOccurrenceRepository : JpaRepository<PositionOccurrence, UUID>
