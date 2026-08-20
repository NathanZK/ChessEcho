package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "human_move_distribution")
class HumanMoveDistribution(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "position_id", nullable = false)
    val positionId: UUID,
    @Column(name = "rating_band", nullable = false)
    val ratingBand: String,
    @Column(name = "move_played", nullable = false)
    val movePlayed: String,
    @Column(name = "observation_count", nullable = false)
    val observationCount: Int = 0,
)
