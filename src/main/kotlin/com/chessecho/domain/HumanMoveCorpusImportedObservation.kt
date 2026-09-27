package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.util.UUID

/** One imported (position, move) contribution of an imported corpus game. */
@Entity
@Immutable
@Table(name = "human_move_corpus_imported_observation")
class HumanMoveCorpusImportedObservation(
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
