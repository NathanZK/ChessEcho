package com.chessecho.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "engine_analysis", uniqueConstraints = [UniqueConstraint(columnNames = ["position_id"])])
class EngineAnalysis(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "position_id", nullable = false)
    val position: Position,
    @Column(nullable = false)
    val depth: Int,
    @Column(name = "baseline_eval_cp")
    val baselineEvalCp: Int?,
    @Column(name = "best_move")
    val bestMove: String?,
    /**
     * Stockfish evaluation of the position after the engine's best move,
     * from the perspective of the player whose move is being evaluated.
     * This serves as the reference evaluation for calculating eval_loss_from_best
     * on each historical move.
     * Example: If best_move_eval_cp = 20 (+0.20 pawns) and a historical move
     * has eval_cp = -80 (-0.80 pawns), the loss is 1.00 pawn.
     */
    @Column(name = "best_move_eval_cp")
    val bestMoveEvalCp: Int?,
    @OneToMany(mappedBy = "engineAnalysis", cascade = [CascadeType.ALL], orphanRemoval = true)
    val moveEvaluations: MutableList<MoveEvaluation> = mutableListOf(),
    @Column(name = "analyzed_at", nullable = false)
    val analyzedAt: Instant = Instant.now(),
)
