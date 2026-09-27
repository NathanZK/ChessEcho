package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.util.UUID

/**
 * One (position, move) contribution of a committed corpus game. [positionHash]
 * is the immutable snapshot taken at commit time; checkpoints aggregate by it.
 */
@Entity
@Immutable
@Table(name = "human_move_corpus_observation")
class HumanMoveCorpusObservation(
    @Id
    val id: UUID,
    @Column(name = "game_id", nullable = false)
    val gameId: UUID,
    @Column(name = "position_id", nullable = false)
    val positionId: UUID,
    @Column(name = "position_hash", nullable = false)
    val positionHash: String,
    @Column(name = "move_played", nullable = false)
    val movePlayed: String,
    @Column(name = "observation_count", nullable = false)
    val observationCount: Int,
)
