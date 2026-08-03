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
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "engine_analysis")
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
    @Column(name = "baseline_eval_mate")
    val baselineEvalMate: Int?,
    @Column(name = "best_move")
    val bestMove: String?,
    @OneToMany(mappedBy = "engineAnalysis", cascade = [CascadeType.ALL], orphanRemoval = true)
    val moveEvaluations: MutableList<MoveEvaluation> = mutableListOf(),
    @Column(name = "analyzed_at", nullable = false)
    val analyzedAt: Instant = Instant.now(),
)
