package com.chessecho.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "engine_move_evaluation")
class MoveEvaluation(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "engine_analysis_id", nullable = false)
    val engineAnalysis: EngineAnalysis,
    @Column(nullable = false)
    val move: String,
    @Column(name = "eval_cp")
    val evalCp: Int?,
    /**
     * Represents the evaluation loss of this move relative to Stockfish's best move,
     * from the perspective of the player making the move.
     *
     * Future invariant: Once the new analysis pipeline is implemented, every persisted
     * MoveEvaluation will have this value calculated. The calculation must:
     * - Correctly handle both White and Black perspectives
     * - Correctly handle both centipawn and mate evaluations
     * - Be calculated once during the engine-analysis pipeline (not during puzzle/weakness requests)
     *
     * When the analysis pipeline guarantees this value is always populated,
     * the database column can be made NOT NULL.
     */
    @Column(name = "eval_loss_from_best")
    val evalLossFromBest: Double?,
)
