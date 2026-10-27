package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/** One (position, move) retained row of one [HumanMoveCorpusProjection]. */
@Entity
@Table(name = "human_move_corpus_projection_row")
class HumanMoveCorpusProjectionRow(
    @Id
    val id: UUID,
    @Column(name = "projection_id", nullable = false)
    val projectionId: UUID,
    @Column(name = "position_hash", nullable = false)
    val positionHash: String,
    @Column(name = "move_played", nullable = false)
    val movePlayed: String,
    @Column(name = "observation_count", nullable = false)
    val observationCount: Int,
)
